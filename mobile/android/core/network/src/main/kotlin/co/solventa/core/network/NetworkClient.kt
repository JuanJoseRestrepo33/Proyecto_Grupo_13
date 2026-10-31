package co.solventa.core.network

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
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
    ): BffApi = retrofit(baseUrl, cliente(interceptors, tokenAcceso)).create(BffApi::class.java)

    /**
     * Cliente con la sesión de la app: firma con su token de acceso y, ante un 401 de una ruta
     * protegida, la renueva una vez con su token de refresco (WEB-F13). La renovación usa el mismo
     * cliente sin el autenticador, así que nunca se renueva en bucle.
     */
    fun create(baseUrl: String, interceptors: List<Interceptor> = emptyList(), sesion: SesionBff): BffApi {
        val base = cliente(interceptors) { sesion.tokenAcceso }
        val renovacion = retrofit(baseUrl, base).create(RenovacionApi::class.java)
        val conRenovacion = base.newBuilder()
            .authenticator(RenovadorSesion(sesion, RenovadorSesion.desde(renovacion)))
            .build()
        return retrofit(baseUrl, conRenovacion).create(BffApi::class.java)
    }

    private fun cliente(interceptors: List<Interceptor>, tokenAcceso: () -> String?) = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(TokenAccesoInterceptor(tokenAcceso))
        .apply { interceptors.forEach { addInterceptor(it) } }
        .authenticator(Authenticator.NONE)
        .build()

    private fun retrofit(baseUrl: String, client: OkHttpClient) = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
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
