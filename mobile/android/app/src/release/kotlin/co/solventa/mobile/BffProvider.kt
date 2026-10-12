package co.solventa.mobile

import android.content.Context
import co.solventa.core.network.BffApi
import co.solventa.core.network.NetworkClient

object BffProvider {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): BffApi = NetworkClient.create(BuildConfig.BASE_URL)
}
