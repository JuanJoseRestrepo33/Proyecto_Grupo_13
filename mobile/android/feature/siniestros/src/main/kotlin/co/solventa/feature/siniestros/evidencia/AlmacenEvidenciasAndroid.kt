package co.solventa.feature.siniestros.evidencia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lleva lo elegido a un archivo que el BFF acepta: el tipo sale del contenido, se comprime lo que
 * pase de 10 MB y se quitan las coordenadas de los metadatos (RC-04). HEIC siempre pasa a JPEG, porque
 * ExifInterface no puede reescribirlo. Los PDF se envían tal cual.
 */
@Suppress("TooManyFunctions")
class AlmacenEvidenciasAndroid(
    contexto: Context,
    private val compresorVideo: CompresorVideo = CompresorVideo(contexto),
) : AlmacenEvidencias {
    private val resolver = contexto.contentResolver
    private val carpeta = File(contexto.cacheDir, "evidencias").apply { mkdirs() }

    override suspend fun preparar(
        uri: String,
        onProgreso: (Float) -> Unit,
    ): Preparado = withContext(Dispatchers.IO) {
        val crudo = File(carpeta, "${UUID.randomUUID()}.tmp")
        val origen = Uri.parse(uri)
        // Toda salida que no termine entregada se borra, también ante un error o una cancelación.
        val salidas = mutableListOf<File>()
        var entregada: String? = null
        try {
            val entrada = resolver.openInputStream(origen) ?: throw IOException("Sin contenido")
            entrada.use { lectura -> crudo.outputStream().use { lectura.copyTo(it) } }
            normalizar(crudo, salidas, onProgreso).also { entregada = (it as? Preparado.Listo)?.ruta }
        } catch (ignored: IOException) {
            Preparado.Rechazado(MotivoRechazo.ILEGIBLE)
        } catch (ignored: SecurityException) {
            Preparado.Rechazado(MotivoRechazo.ILEGIBLE)
        } finally {
            crudo.delete()
            salidas.filter { it.path != entregada }.forEach(File::delete)
            if (origen.scheme == "file") origen.path?.let { File(it).delete() }
        }
    }

    private suspend fun normalizar(crudo: File, salidas: MutableList<File>, onProgreso: (Float) -> Unit): Preparado {
        val tamano = crudo.length()
        if (tamano == 0L) return Preparado.Rechazado(MotivoRechazo.VACIO)
        return when (val formato = formatoDe(crudo)) {
            null -> Preparado.Rechazado(MotivoRechazo.FORMATO)
            FormatoEvidencia.JPEG, FormatoEvidencia.PNG, FormatoEvidencia.HEIC -> imagen(crudo, formato, salidas)
            FormatoEvidencia.MP4 -> video(crudo, salidas, onProgreso)
            FormatoEvidencia.PDF -> if (tamano > TAMANO_MAXIMO) {
                Preparado.Rechazado(MotivoRechazo.NO_CABE, formato, tamano)
            } else {
                listo(crudo.copyTo(destino(formato, salidas)), formato)
            }
        }
    }

    private fun formatoDe(archivo: File): FormatoEvidencia? {
        val inicio = ByteArray(BYTES_FIRMA)
        val leidos = archivo.inputStream().use { it.read(inicio) }
        return FormatoEvidencia.desdeFirma(inicio.copyOf(leidos))
    }

    private fun imagen(crudo: File, formato: FormatoEvidencia, salidas: MutableList<File>): Preparado {
        if (formato != FormatoEvidencia.HEIC && crudo.length() <= TAMANO_MAXIMO) {
            val copia = crudo.copyTo(destino(formato, salidas))
            if (limpiarExif(copia)) return listo(copia, formato)
            copia.delete()
        }
        return aJpeg(crudo, salidas)
    }

    /** Sin metadatos: `Bitmap.compress` no escribe EXIF. */
    private fun aJpeg(crudo: File, salidas: MutableList<File>): Preparado {
        val salida = destino(FormatoEvidencia.JPEG, salidas)
        val mapa = decodificar(crudo)
        return when {
            mapa == null -> Preparado.Rechazado(MotivoRechazo.FORMATO)
            comprimirJpeg(mapa, salida) -> listo(salida, FormatoEvidencia.JPEG)
            else -> {
                salida.delete()
                Preparado.Rechazado(MotivoRechazo.NO_CABE, FormatoEvidencia.JPEG, crudo.length())
            }
        }
    }

    private suspend fun video(crudo: File, salidas: MutableList<File>, onProgreso: (Float) -> Unit): Preparado {
        val salida = destino(FormatoEvidencia.MP4, salidas)
        val cabe = if (crudo.length() <= TAMANO_MAXIMO) {
            crudo.copyTo(salida)
            true
        } else {
            compresorVideo.comprimir(crudo, salida, onProgreso)
        }
        if (!cabe) {
            salida.delete()
            return Preparado.Rechazado(MotivoRechazo.NO_CABE, FormatoEvidencia.MP4, crudo.length())
        }
        LimpiadorMp4.limpiar(salida)
        return listo(salida, FormatoEvidencia.MP4)
    }

    private fun destino(formato: FormatoEvidencia, salidas: MutableList<File>) =
        File(carpeta, "evidencia-${UUID.randomUUID()}.${formato.extension}").also(salidas::add)

    private fun listo(archivo: File, formato: FormatoEvidencia) =
        Preparado.Listo(archivo.path, archivo.name, formato, archivo.length())

    override fun nuevaCaptura(): File = File(carpeta, "captura-${UUID.randomUUID()}.jpg")

    override fun archivo(ruta: String): File? = File(ruta).takeIf(File::isFile)

    override fun borrar(ruta: String) {
        File(ruta).delete()
    }

    override fun conservarSolo(rutas: Set<String>) {
        carpeta.listFiles().orEmpty().filter { it.path !in rutas }.forEach(File::delete)
    }
}

private val ETIQUETAS_UBICACION = listOf(
    ExifInterface.TAG_GPS_VERSION_ID,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_TIMESTAMP,
    ExifInterface.TAG_GPS_DATESTAMP,
    ExifInterface.TAG_GPS_SATELLITES,
    ExifInterface.TAG_GPS_STATUS,
    ExifInterface.TAG_GPS_MEASURE_MODE,
    ExifInterface.TAG_GPS_DOP,
    ExifInterface.TAG_GPS_SPEED_REF,
    ExifInterface.TAG_GPS_SPEED,
    ExifInterface.TAG_GPS_TRACK_REF,
    ExifInterface.TAG_GPS_TRACK,
    ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
    ExifInterface.TAG_GPS_IMG_DIRECTION,
    ExifInterface.TAG_GPS_MAP_DATUM,
    ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
    ExifInterface.TAG_GPS_DEST_LATITUDE,
    ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
    ExifInterface.TAG_GPS_DEST_LONGITUDE,
    ExifInterface.TAG_GPS_DEST_BEARING_REF,
    ExifInterface.TAG_GPS_DEST_BEARING,
    ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
    ExifInterface.TAG_GPS_DEST_DISTANCE,
    ExifInterface.TAG_GPS_PROCESSING_METHOD,
    ExifInterface.TAG_GPS_AREA_INFORMATION,
    ExifInterface.TAG_GPS_DIFFERENTIAL,
    ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
    ExifInterface.TAG_XMP,
)

/** @return false si no se pudo reescribir: quien llama vuelve a codificar la imagen, sin metadatos. */
internal fun limpiarExif(archivo: File): Boolean = try {
    val exif = ExifInterface(archivo)
    ETIQUETAS_UBICACION.forEach { exif.setAttribute(it, null) }
    exif.saveAttributes()
    true
} catch (ignored: IOException) {
    false
} catch (ignored: UnsupportedOperationException) {
    false
}

private const val LADO_MAXIMO = 4096
private val LADOS = listOf(LADO_MAXIMO, 3072, 2048, 1600)
private val CALIDADES = listOf(85, 70)

/** La imagen derecha y sin pasar de [LADO_MAXIMO]; null si este dispositivo no la puede leer. */
private fun decodificar(archivo: File): Bitmap? {
    val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(archivo.path, medidas)
    val mayor = max(medidas.outWidth, medidas.outHeight)
    var muestra = 1
    while (mayor / muestra > LADO_MAXIMO) muestra *= 2
    val opciones = BitmapFactory.Options().apply { inSampleSize = muestra }
    val giro = runCatching { ExifInterface(archivo).rotationDegrees }.getOrDefault(0)
    return BitmapFactory.decodeFile(archivo.path, opciones)?.girado(giro)
}

private fun Bitmap.girado(grados: Int): Bitmap {
    if (grados == 0) return this
    val matriz = Matrix().apply { postRotate(grados.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matriz, true)
}

/** Baja calidad y tamaño hasta caber. `Bitmap.compress` no escribe EXIF, así que sale sin coordenadas. */
private fun comprimirJpeg(mapa: Bitmap, salida: File): Boolean {
    LADOS.forEach { lado ->
        val escala = lado.toFloat() / max(mapa.width, mapa.height)
        val reducido = if (escala < 1f) {
            Bitmap.createScaledBitmap(mapa, (mapa.width * escala).toInt(), (mapa.height * escala).toInt(), true)
        } else {
            mapa
        }
        CALIDADES.forEach { calidad ->
            salida.outputStream().use { reducido.compress(Bitmap.CompressFormat.JPEG, calidad, it) }
            if (salida.length() in 1..TAMANO_MAXIMO) return true
        }
    }
    return false
}
