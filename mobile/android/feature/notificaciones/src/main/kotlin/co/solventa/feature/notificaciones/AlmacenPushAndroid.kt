package co.solventa.feature.notificaciones

import android.content.Context
import java.util.UUID

/**
 * En las preferencias privadas de la app, que no entran al respaldo (`allowBackup=false`): reinstalar da
 * un identificador nuevo, y el dispositivo se registra de nuevo al iniciar sesión (criterio 4).
 */
class AlmacenPushAndroid(context: Context) : AlmacenPush {
    private val preferencias = context.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

    @Synchronized
    override fun tokenInstalacion(): String = preferencias.getString(TOKEN, null)
        ?: "instalacion-${UUID.randomUUID()}".also { preferencias.edit().putString(TOKEN, it).apply() }

    override fun vistas(cliente: String): Set<String>? = preferencias.getStringSet(VISTAS + cliente, null)?.toSet()

    override fun guardarVistas(cliente: String, ids: Set<String>) {
        preferencias.edit().putStringSet(VISTAS + cliente, ids).apply()
    }

    override fun permisoPedido(): Boolean = preferencias.getBoolean(PERMISO_PEDIDO, false)

    override fun marcarPermisoPedido() {
        preferencias.edit().putBoolean(PERMISO_PEDIDO, true).apply()
    }

    private companion object {
        const val ARCHIVO = "push"
        const val TOKEN = "token_instalacion"
        const val VISTAS = "vistas_"
        const val PERMISO_PEDIDO = "permiso_pedido"
    }
}
