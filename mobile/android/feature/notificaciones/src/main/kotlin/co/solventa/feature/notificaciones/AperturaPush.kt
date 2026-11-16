package co.solventa.feature.notificaciones

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.solventa.core.network.Notificacion

/** Lo que trae la notificación tocada: qué marcar como leída y qué siniestro abrir. */
data class AperturaPush(val idNotificacion: String, val idSiniestro: String?)

private const val EXTRA_NOTIFICACION = "co.solventa.push.notificacion"
private const val EXTRA_SINIESTRO = "co.solventa.push.siniestro"

/**
 * El intento que lleva la notificación: abre [actividad] encima de la que ya esté, sin otra copia
 * (`singleTop` la recibe en `onNewIntent`).
 */
fun intentoApertura(context: Context, actividad: Class<*>, notificacion: Notificacion): Intent =
    Intent(context, actividad)
        .setAction(Intent.ACTION_VIEW)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_NOTIFICACION, notificacion.id)
        .putExtra(EXTRA_SINIESTRO, notificacion.idSiniestro)

/** La apertura del [intent], o null si no viene de una notificación. */
fun aperturaDe(intent: Intent?): AperturaPush? {
    val notificacion = intent?.getStringExtra(EXTRA_NOTIFICACION)?.takeIf(String::isNotBlank) ?: return null
    return AperturaPush(notificacion, intent.getStringExtra(EXTRA_SINIESTRO)?.takeIf(String::isNotBlank))
}

/** Quita la apertura del [intent]: al recrear la Activity no se vuelve a abrir el detalle. */
fun consumirApertura(intent: Intent?) {
    intent?.removeExtra(EXTRA_NOTIFICACION)
    intent?.removeExtra(EXTRA_SINIESTRO)
}

/**
 * La apertura espera aquí hasta que la navegación principal esté lista: si la sesión ya no estaba, el
 * cliente inicia sesión primero y después se abre el detalle.
 */
class AperturasPush {
    var pendiente: AperturaPush? by mutableStateOf(null)
        private set

    fun recibir(apertura: AperturaPush) {
        pendiente = apertura
    }

    /** La navegación ya abrió el detalle (o no había qué abrir). */
    fun atendida(apertura: AperturaPush) {
        if (pendiente == apertura) pendiente = null
    }
}
