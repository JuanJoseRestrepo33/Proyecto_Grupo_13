package co.solventa.feature.siniestros.borrador

import co.solventa.core.network.NetworkClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/** Un borrador tal como quedó en disco: se lee, o se sabe por qué no. */
sealed interface EntradaBorrador {
    val id: String

    data class Legible(val borrador: Borrador) : EntradaBorrador {
        override val id: String get() = borrador.id
    }

    data class Ilegible(override val id: String, val permanente: Boolean) : EntradaBorrador
}

/**
 * Borradores cifrados en `<raiz>/<cliente.id>/<id>/` (en la app, `noBackupFilesDir/borradores`, fuera de
 * las copias de seguridad): `borrador.bin` con el manifiesto y un `<adjunto>.bin` por archivo. Cada
 * escritura es atómica (temporal, `fsync` y `rename`), así que un corte deja la versión anterior o la
 * nueva, nunca una a medias. La caché de pólizas y los registros enviados viven en la misma carpeta del
 * cliente. JVM puro: se prueba con una carpeta temporal.
 *
 * No es seguro entre hilos por sí solo: el [EnviadorBorradores] serializa los accesos.
 */
@Suppress("TooManyFunctions")
class AlmacenBorradoresCifrado(private val raiz: File, private val cifrado: PuertoCifrado) {

    /**
     * Guarda un borrador nuevo con el contenido de sus archivos. [archivos] lleva el id de cada adjunto
     * a su archivo en claro de la caché; quien llama los borra después, ya confirmado el guardado.
     * El manifiesto se escribe al final: si algo falla, no queda nada y se lanza la [IOException].
     */
    fun guardarNuevo(borrador: Borrador, archivos: Map<String, File>) {
        val carpeta = carpetaDe(borrador.duenio, borrador.id)
        try {
            if (!carpeta.mkdirs() && !carpeta.isDirectory) throw IOException("No se pudo crear $carpeta")
            archivos.forEach { (idAdjunto, origen) ->
                escribirAtomico(File(carpeta, archivoDe(idAdjunto))) { salida ->
                    origen.inputStream().use { it.copyTo(salida) }
                }
            }
            escribirManifiesto(borrador)
        } catch (error: IOException) {
            carpeta.deleteRecursively()
            throw error
        }
    }

    /** Reemplaza el manifiesto. Si falla, queda el anterior. */
    fun actualizar(borrador: Borrador) = escribirManifiesto(borrador)

    /**
     * Agrega archivos a un borrador existente y reemplaza su manifiesto (la corrección tras un rechazo).
     * Si falla, se borran los archivos nuevos y queda el manifiesto anterior.
     */
    fun actualizarConArchivos(borrador: Borrador, archivos: Map<String, File>) {
        val carpeta = carpetaDe(borrador.duenio, borrador.id)
        val escritos = mutableListOf<File>()
        try {
            archivos.forEach { (idAdjunto, origen) ->
                val destino = File(carpeta, archivoDe(idAdjunto))
                escribirAtomico(destino) { salida -> origen.inputStream().use { it.copyTo(salida) } }
                escritos += destino
            }
            escribirManifiesto(borrador)
        } catch (error: IOException) {
            escritos.forEach(File::delete)
            throw error
        }
    }

    fun existe(duenio: String, id: String): Boolean = File(carpetaDe(duenio, id), MANIFIESTO).isFile

    /** @throws BorradorIlegible */
    fun leer(duenio: String, id: String): Borrador = leerCifrado(
        File(carpetaDe(duenio, id), MANIFIESTO),
        Borrador.serializer(),
    ).also { if (it.duenio != duenio || it.id != id) throw BorradorIlegible(permanente = true) }

    /** Los borradores de [duenio], en orden de creación; los ilegibles van al final. */
    fun listar(duenio: String): List<EntradaBorrador> {
        val carpetas = carpetaCliente(duenio).listFiles().orEmpty().filter { it.isDirectory && it.name != ENVIADOS }
        val entradas = carpetas.mapNotNull { carpeta ->
            // Sin manifiesto, el guardado se cortó antes de confirmarse: lo que quedó no es de nadie.
            if (!File(carpeta, MANIFIESTO).isFile) {
                carpeta.deleteRecursively()
                return@mapNotNull null
            }
            try {
                EntradaBorrador.Legible(leer(duenio, carpeta.name))
            } catch (ilegible: BorradorIlegible) {
                EntradaBorrador.Ilegible(carpeta.name, ilegible.permanente)
            }
        }
        val (legibles, ilegibles) = entradas.partition { it is EntradaBorrador.Legible }
        return legibles.sortedBy { (it as EntradaBorrador.Legible).borrador.creadoEn } + ilegibles.sortedBy { it.id }
    }

    /** El contenido en claro de un archivo, descifrado en flujo. @throws BorradorIlegible */
    fun abrirArchivo(duenio: String, id: String, idAdjunto: String): InputStream {
        val archivo = File(carpetaDe(duenio, id), archivoDe(idAdjunto))
        val entrada = try {
            archivo.inputStream()
        } catch (perdido: IOException) {
            throw BorradorIlegible(permanente = !archivo.exists(), perdido)
        }
        return try {
            cifrado.descifrar(entrada)
        } catch (error: IOException) {
            entrada.close()
            throw clasificar(error)
        }
    }

    /** Lee el archivo completo sin guardar nada: falla igual que lo haría la carga. @throws BorradorIlegible */
    fun verificarArchivo(duenio: String, id: String, idAdjunto: String) {
        abrirArchivo(duenio, id, idAdjunto).use { entrada ->
            val bloque = ByteArray(BLOQUE)
            while (entrada.read(bloque) >= 0) Unit
        }
    }

    fun borrarArchivo(duenio: String, id: String, idAdjunto: String) {
        File(carpetaDe(duenio, id), archivoDe(idAdjunto)).delete()
    }

    /** Lo borra del teléfono con su evidencia. No toca un siniestro ya registrado. */
    fun borrar(duenio: String, id: String) {
        carpetaDe(duenio, id).deleteRecursively()
    }

    // ── Registros enviados ────────────────────────────────────────────────────────────────────────

    fun guardarEnviado(duenio: String, registro: RegistroEnviado) {
        val carpeta = File(carpetaCliente(duenio), ENVIADOS).apply { mkdirs() }
        escribirCifrado(File(carpeta, "${seguro(registro.id)}$EXTENSION"), RegistroEnviado.serializer(), registro)
    }

    fun listarEnviados(duenio: String): List<RegistroEnviado> =
        File(carpetaCliente(duenio), ENVIADOS).listFiles().orEmpty()
            .filter { it.name.endsWith(EXTENSION) }
            .mapNotNull { archivo ->
                try {
                    leerCifrado(archivo, RegistroEnviado.serializer())
                } catch (ignored: BorradorIlegible) {
                    null
                }
            }
            .sortedBy { it.enviadoEn }

    fun borrarEnviado(duenio: String, id: String) {
        File(File(carpetaCliente(duenio), ENVIADOS), "${seguro(id)}$EXTENSION").delete()
    }

    // ── Caché de pólizas ─────────────────────────────────────────────────────────────────────────

    fun guardarPolizas(duenio: String, polizas: PolizasGuardadas) {
        carpetaCliente(duenio).mkdirs()
        escribirCifrado(File(carpetaCliente(duenio), POLIZAS), PolizasGuardadas.serializer(), polizas)
    }

    /** null si nunca cargaron o ya no se pueden leer: el paso 1 explica que hace falta conexión. */
    fun leerPolizas(duenio: String): PolizasGuardadas? {
        val archivo = File(carpetaCliente(duenio), POLIZAS)
        if (!archivo.isFile) return null
        return try {
            leerCifrado(archivo, PolizasGuardadas.serializer())
        } catch (ignored: BorradorIlegible) {
            null
        }
    }

    // ── Escritura y lectura ───────────────────────────────────────────────────────────────────────

    private fun escribirManifiesto(borrador: Borrador) {
        val carpeta = carpetaDe(borrador.duenio, borrador.id)
        if (!carpeta.isDirectory) throw IOException("El borrador ${borrador.id} ya no existe")
        escribirCifrado(File(carpeta, MANIFIESTO), Borrador.serializer(), borrador)
    }

    private fun <T> escribirCifrado(destino: File, serializador: KSerializer<T>, valor: T) {
        val texto = NetworkClient.json.encodeToString(serializador, valor).toByteArray(Charsets.UTF_8)
        escribirAtomico(destino) { it.write(texto) }
    }

    @Suppress("ThrowsCount")
    private fun <T> leerCifrado(archivo: File, serializador: KSerializer<T>): T {
        val bytes = try {
            archivo.inputStream().use { entrada -> cifrado.descifrar(entrada).use(InputStream::readBytes) }
        } catch (error: IOException) {
            throw clasificar(error)
        }
        return try {
            NetworkClient.json.decodeFromString(serializador, bytes.toString(Charsets.UTF_8))
        } catch (corrupto: SerializationException) {
            throw BorradorIlegible(permanente = true, corrupto)
        } catch (corrupto: IllegalArgumentException) {
            throw BorradorIlegible(permanente = true, corrupto)
        }
    }

    /** Temporal, `fsync` y `rename`: el destino nunca queda escrito a medias. */
    private fun escribirAtomico(destino: File, escribir: (OutputStream) -> Unit) {
        val temporal = File(destino.parentFile, destino.name + TEMPORAL)
        try {
            FileOutputStream(temporal).use { archivo ->
                cifrado.cifrar(archivo).use(escribir)
                archivo.flush()
                archivo.fd.sync()
            }
            Files.move(
                temporal.toPath(),
                destino.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: IOException) {
            temporal.delete()
            throw error
        }
    }

    private fun carpetaCliente(duenio: String) = File(raiz, seguro(duenio))

    private fun carpetaDe(duenio: String, id: String) = File(carpetaCliente(duenio), seguro(id))

    private fun archivoDe(idAdjunto: String) = "${seguro(idAdjunto)}$EXTENSION"

    private companion object {
        const val MANIFIESTO = "borrador.bin"
        const val POLIZAS = "polizas.bin"
        const val ENVIADOS = "enviados"
        const val EXTENSION = ".bin"
        const val TEMPORAL = ".tmp"
        const val BLOQUE = 8192
        val PERMITIDO = Regex("^[A-Za-z0-9_-]{1,80}$")

        /** Los nombres de carpeta salen de ids del BFF y de UUID: nada que pueda salir de la raíz. */
        fun seguro(nombre: String): String {
            require(PERMITIDO.matches(nombre)) { "Identificador no válido para el almacén" }
            return nombre
        }
    }
}
