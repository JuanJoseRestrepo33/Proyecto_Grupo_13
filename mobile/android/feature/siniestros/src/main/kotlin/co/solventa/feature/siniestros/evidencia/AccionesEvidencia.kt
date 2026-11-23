package co.solventa.feature.siniestros.evidencia

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Lo que el paso de evidencia le pide al sistema; las pruebas de pantalla usan los valores por defecto. */
class AccionesEvidencia(
    val abrirCamara: () -> Unit = {},
    val pedirPermisos: () -> Unit = {},
    val abrirGaleria: () -> Unit = {},
    val abrirDocumentos: () -> Unit = {},
    val abrirAjustes: () -> Unit = {},
)

private const val PREFERENCIAS = "permisos_evidencia"
private const val PEDIDOS = "pedidos"
private const val PERMISO_UBICACION = Manifest.permission.ACCESS_FINE_LOCATION
private val PERMISOS = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    PERMISO_UBICACION,
)

/** Registra los selectores y los permisos, y mantiene el estado de los permisos al volver de ajustes. */
@Composable
fun rememberAccionesEvidencia(gestor: GestorEvidencia, onAbrirCamara: () -> Unit): AccionesEvidencia {
    val contexto = LocalContext.current
    val preferencias = remember { contexto.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE) }
    val actualizar = {
        val pedidos = preferencias.getBoolean(PEDIDOS, false)
        gestor.permisos(
            camara = estadoDe(contexto, Manifest.permission.CAMERA, pedidos),
            ubicacion = estadoDe(contexto, PERMISO_UBICACION, pedidos, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }
    LifecycleResumeEffect(gestor) {
        actualizar()
        onPauseOrDispose {}
    }
    val pedirVarios = ActivityResultContracts.RequestMultiplePermissions()
    val permisos = rememberLauncherForActivityResult(pedirVarios) { resultado ->
        preferencias.edit().putBoolean(PEDIDOS, true).apply()
        actualizar()
        if (resultado[Manifest.permission.CAMERA] == true) onAbrirCamara()
    }
    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { gestor.elegido(it.toString()) }
    }
    val documentos = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { gestor.elegido(it.toString()) }
    }
    return remember(gestor) {
        AccionesEvidencia(
            abrirCamara = onAbrirCamara,
            pedirPermisos = { permisos.launch(PERMISOS) },
            abrirGaleria = {
                galeria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            abrirDocumentos = { documentos.launch(arrayOf(FormatoEvidencia.PDF.tipoContenido)) },
            abrirAjustes = {
                val destino = Uri.fromParts("package", contexto.packageName, null)
                contexto.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, destino))
            },
        )
    }
}

private fun estadoDe(contexto: Context, permiso: String, pedidos: Boolean, alterno: String? = null): EstadoPermiso {
    val concedido = listOfNotNull(permiso, alterno).any {
        contexto.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
    val debeExplicar = contexto.actividad()?.shouldShowRequestPermissionRationale(permiso) == true
    return clasificarPermiso(concedido, debeExplicar, pedidos)
}

private fun Context.actividad(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.actividad()
    else -> null
}
