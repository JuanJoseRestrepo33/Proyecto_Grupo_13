package co.solventa.mobile

import android.content.Context
import co.solventa.core.network.BffApi
import co.solventa.core.network.NetworkClient

object BffProvider {
    fun create(context: Context): BffApi = NetworkClient.create(
        BuildConfig.BASE_URL,
        listOf(DebugMockInterceptor {
            context.assets.open("mocks/health.json").bufferedReader().use { it.readText() }
        }),
    )
}
