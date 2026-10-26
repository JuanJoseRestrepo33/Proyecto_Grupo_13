package co.solventa.core.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

// Cliente del contrato contratos/bff-movil/openapi.yaml. Las rutas son relativas al prefijo
// /api/v1/movil/ de BuildConfig.BASE_URL. Un error HTTP llega como retrofit2.HttpException;
// ErrorBff.desde(...) recupera su código estable.
@Suppress("TooManyFunctions")
interface BffApi {
    @POST("registros")
    suspend fun crearRegistro(@Body solicitud: SolicitudRegistro): Registro

    @POST("registros/{id}/otp/verificacion")
    suspend fun verificarOtp(@Path("id") idRegistro: String, @Body solicitud: SolicitudOtp): Sesion

    @POST("registros/{id}/otp/reenvio")
    suspend fun reenviarOtp(@Path("id") idRegistro: String): Otp

    @POST("sesiones")
    suspend fun iniciarSesion(@Body credenciales: Credenciales): Sesion

    @POST("sesiones/renovacion")
    suspend fun renovarSesion(@Body solicitud: SolicitudRenovacion): Sesion

    @GET("sesion")
    suspend fun obtenerSesion(): Cliente

    @GET("identidad/verificacion")
    suspend fun obtenerVerificacionIdentidad(): VerificacionIdentidad

    @POST("identidad/verificacion")
    suspend fun solicitarVerificacionIdentidad(): VerificacionIdentidad

    // ── Consentimiento (MOB-F02) ───────────────────────────────────────────────────────────────
    // Rutas que verifica el humo de staging (scripts/humo.py); todas exigen sesión. El contrato
    // bff-movil v0.2 las publica con #160: si cambia algo, se ajusta aquí y en el mock.

    @GET("consentimientos/finalidades")
    suspend fun listarFinalidades(): List<FinalidadConsentimiento>

    @GET("consentimientos")
    suspend fun listarConsentimientos(): List<Consentimiento>

    @POST("consentimientos")
    suspend fun otorgarConsentimiento(@Body solicitud: SolicitudConsentimiento): Consentimiento

    @POST("consentimientos/{id}/revocacion")
    suspend fun revocarConsentimiento(@Path("id") idConsentimiento: String): Consentimiento

    @POST("consentimientos/{id}/renovacion")
    suspend fun renovarConsentimiento(@Path("id") idConsentimiento: String): Consentimiento
}
