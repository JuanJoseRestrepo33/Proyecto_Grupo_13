package co.solventa.core.network

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object NetworkClient {
    val json = Json { ignoreUnknownKeys = true }

    /**
     * @param tokenAcceso devuelve el token de la sesión vigente, o null si no hay sesión. Las
     * operaciones públicas del contrato (registro, ingreso) funcionan sin él.
     */
    fun create(
        baseUrl: String,
        interceptors: List<Interceptor> = emptyList(),
        tokenAcceso: () -> String? = { null },
    ): BffApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(TokenAccesoInterceptor(tokenAcceso))
            .apply { interceptors.forEach { addInterceptor(it) } }
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BffApi::class.java)
    }
}

internal class TokenAccesoInterceptor(private val tokenAcceso: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val token = tokenAcceso()
        val request = if (token == null) {
            chain.request()
        } else {
            chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        }
        return chain.proceed(request)
    }
}
