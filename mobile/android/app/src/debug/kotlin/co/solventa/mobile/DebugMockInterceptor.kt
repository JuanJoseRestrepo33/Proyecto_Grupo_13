package co.solventa.mobile

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class DebugMockInterceptor(private val readHealth: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val isHealth = request.method == "GET" && request.url.encodedPath ==
            BuildConfig.BASE_URL.toHttpBasePath() + "health"
        val body = if (isHealth) readHealth() else """{"error":"mock_not_found"}"""
        // Nunca se llama chain.proceed: debug no sale a la red, ni ante rutas desconocidas.
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (isHealth) 200 else 404)
            .message(if (isHealth) "OK" else "Not Found")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun String.toHttpBasePath(): String = java.net.URI(this).rawPath
}
