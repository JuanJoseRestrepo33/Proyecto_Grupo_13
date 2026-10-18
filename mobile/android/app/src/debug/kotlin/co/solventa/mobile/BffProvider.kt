package co.solventa.mobile

import co.solventa.core.network.BffApi
import co.solventa.core.network.NetworkClient
import co.solventa.mobile.mock.MockBffMovil

object BffProvider {
    // Un solo mock por proceso: el estado (registros, sesiones) sobrevive entre pantallas.
    private val mock by lazy { MockBffMovil() }

    fun create(tokenAcceso: () -> String? = { null }): BffApi =
        NetworkClient.create(BuildConfig.BASE_URL, listOf(DebugMockInterceptor(mock)), tokenAcceso)
}
