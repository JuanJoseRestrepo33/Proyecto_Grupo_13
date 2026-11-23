package co.solventa.feature.siniestros.evidencia

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** 10 MB, el mismo límite del BFF y del núcleo. */
const val TAMANO_MAXIMO = 10L * 1024 * 1024

/** Bytes del inicio del archivo que bastan para reconocer su formato. */
const val BYTES_FIRMA = 12

/** Formatos del contrato. El núcleo exige que el tipo declarado coincida con el contenido. */
enum class FormatoEvidencia(val tipoContenido: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    HEIC("image/heic", "heic"),
    MP4("video/mp4", "mp4"),
    PDF("application/pdf", "pdf"),
    ;

    companion object {
        private val JPEG_FIRMA = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val PNG_FIRMA = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val PDF_FIRMA = "%PDF-".toByteArray(Charsets.ISO_8859_1)
        private val MARCAS_HEIC = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")

        // También son ISO-BMFF, pero el núcleo los tomaría por un video que no son.
        private val MARCAS_OTRAS = setOf("avif", "avis", "M4A ", "M4B ")

        /** El formato según los primeros bytes, con el criterio de `tipo_real` del núcleo. */
        fun desdeFirma(inicio: ByteArray): FormatoEvidencia? = when {
            empiezaCon(inicio, JPEG_FIRMA) -> JPEG
            empiezaCon(inicio, PNG_FIRMA) -> PNG
            empiezaCon(inicio, PDF_FIRMA) -> PDF
            inicio.size >= BYTES_FIRMA && texto(inicio, 4, 8) == "ftyp" -> delContenedor(texto(inicio, 8, 12))
            else -> null
        }

        private fun delContenedor(marca: String): FormatoEvidencia? = when (marca) {
            in MARCAS_HEIC -> HEIC
            in MARCAS_OTRAS -> null
            else -> MP4
        }

        private fun empiezaCon(datos: ByteArray, firma: ByteArray) =
            datos.size >= firma.size && firma.indices.all { datos[it] == firma[it] }

        private fun texto(datos: ByteArray, desde: Int, hasta: Int) =
            String(datos, desde, hasta - desde, Charsets.ISO_8859_1)
    }
}

/** Presupuesto de bits para que un video quepa en el límite, con margen para el contenedor. */
object PresupuestoVideo {
    private const val MARGEN = 0.9
    private const val BITS_AUDIO = 128_000L
    private const val BITS_VIDEO_MINIMOS = 150_000L
    private const val BITS_VIDEO_MAXIMOS = 4_000_000L
    private const val MILIS = 1000L

    /** Bits por segundo para el video, o null si con esa duración no quedaría legible. */
    fun bitsPorSegundo(duracionMs: Long): Int? {
        if (duracionMs <= 0) return null
        val total = (TAMANO_MAXIMO * Byte.SIZE_BITS * MARGEN).toLong() * MILIS / duracionMs
        val video = total - BITS_AUDIO
        return video.takeIf { it >= BITS_VIDEO_MINIMOS }?.coerceAtMost(BITS_VIDEO_MAXIMOS)?.toInt()
    }
}

/**
 * Quita de un MP4 los metadatos donde viaja la ubicación (`©xyz` en `udta`, las claves de `meta`):
 * esas cajas pasan a ser `free` con su contenido en cero. El archivo conserva su tamaño y sus
 * desplazamientos, así que el video no se vuelve a escribir.
 */
object LimpiadorMp4 {
    private const val CABECERA = 8L
    private const val CABECERA_LARGA = 16L
    private const val BLOQUE = 8192
    private const val TAMANO_HASTA_EL_FINAL = 0L
    private const val TAMANO_LARGO = 1L
    private val METADATOS = setOf("udta", "meta", "uuid")
    // Cajas que solo contienen otras cajas (ISO/IEC 14496-12): un `udta` o un `uuid` puede ir en
    // cualquiera de ellas, no solo en `moov` o `trak`.
    private val CONTENEDORES = setOf(
        "moov", "trak", "mdia", "minf", "stbl", "dinf", "edts", "mvex", "moof", "traf", "mfra", "tref",
    )

    /** @throws IOException si la estructura no se puede recorrer: mejor no enviar que enviar sin limpiar. */
    fun limpiar(archivo: File) = RandomAccessFile(archivo, "rw").use { recorrer(it, 0, it.length()) }

    private fun recorrer(mp4: RandomAccessFile, inicio: Long, fin: Long) {
        var posicion = inicio
        while (posicion + CABECERA <= fin) {
            mp4.seek(posicion)
            val declarado = mp4.readInt().toLong() and 0xFFFFFFFFL
            val tipo = ByteArray(Int.SIZE_BYTES).also(mp4::readFully).toString(Charsets.ISO_8859_1)
            val (tamano, cabecera) = when (declarado) {
                TAMANO_HASTA_EL_FINAL -> (fin - posicion) to CABECERA
                TAMANO_LARGO -> mp4.readLong() to CABECERA_LARGA
                else -> declarado to CABECERA
            }
            if (tamano < cabecera || posicion + tamano > fin) throw IOException("Caja MP4 inválida: $tipo")
            when (tipo) {
                in METADATOS -> anular(mp4, posicion, cabecera, tamano)
                in CONTENEDORES -> recorrer(mp4, posicion + cabecera, posicion + tamano)
            }
            posicion += tamano
        }
    }

    private fun anular(mp4: RandomAccessFile, posicion: Long, cabecera: Long, tamano: Long) {
        mp4.seek(posicion + Int.SIZE_BYTES)
        mp4.write("free".toByteArray(Charsets.ISO_8859_1))
        mp4.seek(posicion + cabecera)
        val ceros = ByteArray(BLOQUE)
        var restante = tamano - cabecera
        while (restante > 0) {
            val parte = minOf(restante, BLOQUE.toLong()).toInt()
            mp4.write(ceros, 0, parte)
            restante -= parte
        }
    }
}

/** Qué se le puede ofrecer al cliente con un permiso. */
enum class EstadoPermiso { CONCEDIDO, SIN_PEDIR, NEGADO, PERMANENTE }

/**
 * Android no informa la denegación permanente: se deduce porque ya se pidió y el sistema no quiere
 * que se explique otra vez. Cerrar el diálogo sin elegir se ve igual; en ese caso solo se muestra
 * de más la indicación de los ajustes, y la alternativa sigue disponible.
 */
fun clasificarPermiso(concedido: Boolean, debeExplicar: Boolean, yaPedido: Boolean): EstadoPermiso = when {
    concedido -> EstadoPermiso.CONCEDIDO
    debeExplicar -> EstadoPermiso.NEGADO
    yaPedido -> EstadoPermiso.PERMANENTE
    else -> EstadoPermiso.SIN_PEDIR
}
