package co.solventa.feature.siniestros.borrador

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Si hay una red con salida a internet comprobada. Detrás de un puerto para probar el envío en la JVM. */
interface MonitorConectividad {
    val conectado: StateFlow<Boolean>

    /** Siempre conectado: el asistente envía como en #169 (pruebas y vistas previas). */
    object Siempre : MonitorConectividad {
        override val conectado: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    }
}

/**
 * `NetworkCallback` sobre la red por defecto. Solo cuenta como conectada la que tiene
 * `NET_CAPABILITY_VALIDATED`: un wifi sin internet o un portal cautivo no disparan el envío.
 */
class MonitorConectividadAndroid(contexto: Context) : MonitorConectividad {
    private val gestor = contexto.getSystemService(ConnectivityManager::class.java)
    private val estado = MutableStateFlow(validada(gestor.getNetworkCapabilities(gestor.activeNetwork)))
    override val conectado: StateFlow<Boolean> = estado.asStateFlow()

    init {
        gestor.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(red: Network, capacidades: NetworkCapabilities) {
                    estado.value = validada(capacidades)
                }

                override fun onLost(red: Network) {
                    estado.value = false
                }
            },
        )
    }

    private fun validada(capacidades: NetworkCapabilities?) =
        capacidades?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
