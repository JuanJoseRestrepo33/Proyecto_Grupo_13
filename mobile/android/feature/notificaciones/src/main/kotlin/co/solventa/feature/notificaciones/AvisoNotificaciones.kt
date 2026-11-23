package co.solventa.feature.notificaciones

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import co.solventa.core.designsystem.SolventaSpacing

/**
 * El aviso con su estado real: lo recalcula al volver a la app (p. ej. desde Ajustes). «Activar» pide el
 * permiso mientras Android lo deje pedir; después abre los ajustes de notificaciones de la app.
 *
 * @param onVerSiniestros la alternativa: el seguimiento, donde el estado se ve sin notificaciones.
 */
@Composable
fun AvisoPermisoNotificaciones(almacen: AlmacenPush, onVerSiniestros: (() -> Unit)?, modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    val pedible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val calcular = { estadoPermiso(notificacionesPermitidas(contexto), pedible, almacen.permisoPedido()) }
    var estado by remember { mutableStateOf(calcular()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { estado = calcular() }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { estado = calcular() }
    AvisoNotificaciones(
        estado = estado,
        onActivar = {
            if (pedible && puedePedir(contexto, almacen.permisoPedido())) {
                almacen.marcarPermisoPedido()
                pedir.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                abrirAjustes(contexto)
            }
        },
        onAhoraNo = {
            almacen.marcarPermisoPedido()
            estado = calcular()
        },
        onVerSiniestros = onVerSiniestros,
        modifier = modifier,
    )
}

/** Sin estado propio: las pruebas lo dibujan con cualquier [estado]. */
@Composable
fun AvisoNotificaciones(
    estado: EstadoPermiso,
    onActivar: () -> Unit,
    onAhoraNo: () -> Unit,
    onVerSiniestros: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (estado == EstadoPermiso.CONCEDIDO) return
    val sinPedir = estado == EstadoPermiso.SIN_PEDIR
    OutlinedCard(modifier.fillMaxWidth().testTag("aviso-notificaciones")) {
        Column(
            verticalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
            modifier = Modifier.padding(SolventaSpacing.md),
        ) {
            Text(
                stringResource(if (sinPedir) R.string.aviso_pedir_titulo else R.string.aviso_negado_titulo),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(if (sinPedir) R.string.aviso_pedir_texto else R.string.aviso_negado_texto),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (sinPedir) {
                Boton(R.string.aviso_activar, "activar-notificaciones", onActivar, principal = true)
                Boton(R.string.aviso_ahora_no, "notificaciones-ahora-no", onAhoraNo)
            } else {
                onVerSiniestros?.let {
                    Boton(R.string.aviso_ver_siniestros, "aviso-ver-siniestros", it, principal = true)
                }
                Boton(R.string.aviso_activar_ajustes, "activar-notificaciones", onActivar)
            }
        }
    }
}

@Composable
private fun Boton(texto: Int, etiqueta: String, onClick: () -> Unit, principal: Boolean = false) {
    val modificador = Modifier.fillMaxWidth().heightIn(min = SolventaSpacing.touchTarget).testTag(etiqueta)
    if (principal) {
        Button(onClick = onClick, modifier = modificador) { Text(stringResource(texto)) }
    } else {
        TextButton(onClick = onClick, modifier = modificador) { Text(stringResource(texto)) }
    }
}

/** Android deja pedirlo la primera vez y otra más tras un «No permitir»; después solo queda Ajustes. */
private fun puedePedir(contexto: Context, pedido: Boolean): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    return !pedido || contexto.actividad()?.shouldShowRequestPermissionRationale(
        Manifest.permission.POST_NOTIFICATIONS,
    ) == true
}

private fun abrirAjustes(contexto: Context) {
    val intento = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, contexto.packageName)
    // Sin pantalla de ajustes (algún emulador recortado) no hay a dónde llevarlo: el aviso sigue ahí.
    runCatching { contexto.startActivity(intento) }
}

private tailrec fun Context.actividad(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.actividad()
    else -> null
}
