package co.solventa.mobile

import co.solventa.core.network.BffApi
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.SesionBff

object BffProvider {
    fun create(tokenAcceso: () -> String? = { null }): BffApi =
        NetworkClient.create(BuildConfig.BASE_URL, tokenAcceso = tokenAcceso)

    /** Cliente de la app: renueva la sesión ante un 401 de una ruta protegida. */
    fun create(sesion: SesionBff): BffApi = NetworkClient.create(BuildConfig.BASE_URL, sesion = sesion)
}
