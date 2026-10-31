package co.solventa.mobile

import co.solventa.core.network.BffApi
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.SesionBff
import co.solventa.mobile.mock.MockBffMovil
import okhttp3.Interceptor

object BffProvider {
    // Un solo mock por proceso: el estado (registros, sesiones) sobrevive entre pantallas.
    private val mock by lazy { MockBffMovil() }

    fun create(tokenAcceso: () -> String? = { null }): BffApi =
        NetworkClient.create(BuildConfig.BASE_URL, interceptores(BuildConfig.BFF_MOCK), tokenAcceso)

    /** Cliente de la app: renueva la sesión ante un 401 de una ruta protegida. */
    fun create(sesion: SesionBff): BffApi =
        NetworkClient.create(BuildConfig.BASE_URL, interceptores(BuildConfig.BFF_MOCK), sesion)

    /** Con -PBFF_MOCK=false no se instala el mock y las llamadas van al BFF de BuildConfig.BASE_URL. */
    internal fun interceptores(simulado: Boolean): List<Interceptor> =
        if (simulado) listOf(DebugMockInterceptor(mock)) else emptyList()
}
