package co.solventa.feature.notificaciones

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import co.solventa.core.network.Notificacion

/**
 * La notificación local del sistema con el `titulo` y el `cuerpo` del push. Tocarla abre la app con
 * [intento], que lleva el siniestro a abrir (criterio 2).
 */
class NotificadorAndroid(
    context: Context,
    private val intento: (Notificacion) -> Intent,
) : Notificador {
    private val contexto = context.applicationContext

    override fun mostrar(notificacion: Notificacion): Boolean {
        val gestor = NotificationManagerCompat.from(contexto)
        // El permiso se revisa aquí mismo, junto al aviso del sistema: puede haber cambiado desde la última vez.
        val permitido = notificacionesPermitidas(contexto) &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                )
        if (!permitido) return false
        gestor.createNotificationChannel(
            NotificationChannelCompat.Builder(CANAL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(contexto.getString(R.string.canal_siniestros))
                .setDescription(contexto.getString(R.string.canal_siniestros_descripcion))
                .build(),
        )
        val apertura = PendingIntent.getActivity(
            contexto,
            notificacion.id.hashCode(),
            intento(notificacion),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val aviso = NotificationCompat.Builder(contexto, CANAL)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(notificacion.titulo)
            .setContentText(notificacion.cuerpo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificacion.cuerpo))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(apertura)
            .setAutoCancel(true)
            .build()
        return try {
            gestor.notify(ETIQUETA, notificacion.id.hashCode(), aviso)
            true
        } catch (ignored: SecurityException) {
            false
        }
    }

    private companion object {
        const val CANAL = "siniestros"
        const val ETIQUETA = "push"
    }
}

/** Con el permiso (Android 13+) y las notificaciones de la app encendidas. */
fun notificacionesPermitidas(context: Context): Boolean {
    val permiso = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    return permiso && NotificationManagerCompat.from(context).areNotificationsEnabled()
}
