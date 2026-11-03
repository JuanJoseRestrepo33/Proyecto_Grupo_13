package co.solventa.feature.siniestros.evidencia

import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.feature.siniestros.R
import co.solventa.feature.siniestros.comun.BotonPrincipal
import co.solventa.feature.siniestros.comun.BotonSecundario
import java.io.File

/** Captura dentro de la app. @param onError no hay cámara utilizable: quedan las alternativas. */
@Composable
fun PantallaCamara(destino: () -> File, onCapturada: (String) -> Unit, onCancelar: () -> Unit, onError: () -> Unit) {
    val contexto = LocalContext.current
    val ciclo = LocalLifecycleOwner.current
    val captura = remember { ImageCapture.Builder().build() }
    var disparando by remember { mutableStateOf(false) }
    // Capturar antes de que el proveedor enlace la cámara falla y cierra la pantalla.
    var enlazada by remember { mutableStateOf(false) }
    // El ciclo de vida es el de la navegación, que sigue activo al cerrar esta pantalla: hay que
    // soltar la cámara a mano, y no enlazarla si el proveedor responde cuando ya se salió.
    val enlace = remember { EnlaceCamara() }
    DisposableEffect(enlace) { onDispose(enlace::soltar) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).also { vista ->
                    val futuro = ProcessCameraProvider.getInstance(ctx)
                    futuro.addListener(
                        {
                            if (!enlace.activa) return@addListener
                            runCatching {
                                val proveedor = futuro.get()
                                val previa = Preview.Builder().build()
                                previa.surfaceProvider = vista.surfaceProvider
                                proveedor.unbindAll()
                                proveedor.bindToLifecycle(ciclo, CameraSelector.DEFAULT_BACK_CAMERA, previa, captura)
                                enlace.recordar(proveedor, previa, captura)
                                enlazada = true
                            }.onFailure { onError() }
                        },
                        ContextCompat.getMainExecutor(ctx),
                    )
                }
            },
        )
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(SolventaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
        ) {
            BotonPrincipal(
                texto = stringResource(R.string.camara_capturar),
                habilitado = enlazada,
                cargando = disparando,
                onClick = {
                    disparando = true
                    val archivo = destino()
                    val salida = ImageCapture.OutputFileOptions.Builder(archivo).build()
                    val ejecutor = ContextCompat.getMainExecutor(contexto)
                    captura.takePicture(
                        salida,
                        ejecutor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(resultado: ImageCapture.OutputFileResults) {
                                onCapturada(Uri.fromFile(archivo).toString())
                            }

                            override fun onError(error: ImageCaptureException) {
                                disparando = false
                                onError()
                            }
                        },
                    )
                },
            )
            BotonSecundario(stringResource(R.string.boton_cancelar), onClick = onCancelar)
        }
    }
}

/** Lo enlazado por esta pantalla, para soltarlo al cerrarla. */
private class EnlaceCamara {
    var activa = true
        private set
    private var proveedor: ProcessCameraProvider? = null
    private var casos: List<UseCase> = emptyList()

    fun recordar(proveedor: ProcessCameraProvider, vararg casos: UseCase) {
        this.proveedor = proveedor
        this.casos = casos.toList()
    }

    fun soltar() {
        activa = false
        casos.forEach { proveedor?.unbind(it) }
    }
}
