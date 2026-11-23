package co.solventa.core.almacen

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Un archivo cifrado con [cifrado] que se reemplaza entero. Cada escritura es atómica (temporal, `fsync`
 * y `rename`): un corte deja la versión anterior o la nueva, nunca una a medias. JVM puro: se prueba con
 * una carpeta temporal. No es seguro entre hilos por sí solo: quien lo usa serializa los accesos.
 */
class ArchivoCifrado(private val archivo: File, private val cifrado: PuertoCifrado) {

    val existe: Boolean get() = archivo.isFile

    /** Reemplaza el contenido. Si falla, queda el anterior y se lanza la [IOException]. */
    fun escribir(contenido: ByteArray) {
        val carpeta = archivo.parentFile
        if (carpeta != null && !carpeta.mkdirs() && !carpeta.isDirectory) throw IOException("No se pudo crear $carpeta")
        val temporal = File(carpeta, archivo.name + TEMPORAL)
        try {
            FileOutputStream(temporal).use { salida ->
                cifrado.cifrar(salida).use { it.write(contenido) }
                salida.flush()
                salida.fd.sync()
            }
            Files.move(
                temporal.toPath(),
                archivo.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: IOException) {
            temporal.delete()
            throw error
        }
    }

    /** El contenido en claro, o null si no existe. @throws ArchivoIlegible */
    fun leer(): ByteArray? {
        if (!archivo.isFile) return null
        return try {
            archivo.inputStream().use { entrada -> cifrado.descifrar(entrada).use(InputStream::readBytes) }
        } catch (error: IOException) {
            throw clasificar(error)
        }
    }

    /** Borra el archivo y un temporal que haya dejado un corte. */
    fun borrar() {
        archivo.delete()
        File(archivo.parentFile, archivo.name + TEMPORAL).delete()
    }

    private companion object {
        const val TEMPORAL = ".tmp"
    }
}
