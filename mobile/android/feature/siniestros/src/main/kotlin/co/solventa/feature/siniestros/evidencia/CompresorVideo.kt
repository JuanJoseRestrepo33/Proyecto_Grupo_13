package co.solventa.feature.siniestros.evidencia

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val ALTO_VIDEO = 720
private const val ESPERA_PROGRESO_MS = 300L
private const val PORCENTAJE = 100f

/**
 * Baja un video a 720p con el presupuesto de bits de [PresupuestoVideo]. Transformer se controla desde
 * el hilo principal, pero codifica en sus propios hilos: la pantalla sigue respondiendo.
 */
@OptIn(UnstableApi::class)
class CompresorVideo(private val contexto: Context) {
    /** @return true si [salida] quedó dentro del límite. */
    suspend fun comprimir(entrada: File, salida: File, onProgreso: (Float) -> Unit): Boolean {
        val bits = PresupuestoVideo.bitsPorSegundo(duracionMs(entrada)) ?: return false
        return withContext(Dispatchers.Main) { transformar(entrada, salida, bits, onProgreso) } &&
            salida.length() in 1..TAMANO_MAXIMO
    }

    private fun duracionMs(archivo: File): Long {
        val lector = MediaMetadataRetriever()
        return try {
            lector.setDataSource(archivo.path)
            lector.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (ignored: IllegalArgumentException) {
            throw IOException("Video ilegible", ignored)
        } finally {
            lector.release()
        }
    }

    private suspend fun transformar(entrada: File, salida: File, bits: Int, onProgreso: (Float) -> Unit): Boolean {
        val fin = CompletableDeferred<Boolean>()
        val ajustes = VideoEncoderSettings.Builder().setBitrate(bits).build()
        val codificador = DefaultEncoderFactory.Builder(contexto).setRequestedVideoEncoderSettings(ajustes).build()
        val transformer = Transformer.Builder(contexto)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(codificador)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, resultado: ExportResult) {
                    fin.complete(true)
                }

                override fun onError(composition: Composition, resultado: ExportResult, error: ExportException) {
                    fin.complete(false)
                }
            })
            .build()
        val efectos = Effects(emptyList(), listOf(Presentation.createForHeight(ALTO_VIDEO)))
        val medio = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(entrada)))
            .setEffects(efectos)
            .build()
        transformer.start(medio, salida.path)
        val avance = ProgressHolder()
        try {
            while (fin.isActive) {
                if (transformer.getProgress(avance) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgreso(avance.progress / PORCENTAJE)
                }
                delay(ESPERA_PROGRESO_MS)
            }
            return fin.await()
        } catch (cancelada: CancellationException) {
            transformer.cancel()
            throw cancelada
        }
    }

}
