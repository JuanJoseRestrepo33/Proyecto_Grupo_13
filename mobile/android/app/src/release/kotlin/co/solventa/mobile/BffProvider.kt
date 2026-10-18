package co.solventa.mobile

import co.solventa.core.network.BffApi
import co.solventa.core.network.NetworkClient

object BffProvider {
    fun create(tokenAcceso: () -> String? = { null }): BffApi =
        NetworkClient.create(BuildConfig.BASE_URL, tokenAcceso = tokenAcceso)
}
