package co.solventa.core.network

import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Tag

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

    // ── Siniestros (WEB-F05) ───────────────────────────────────────────────────────────────────
    // Contrato bff-movil 0.3.0 (#168); las mismas rutas y códigos que la web.

    @GET("polizas")
    suspend fun listarPolizas(): List<PolizaResumen>

    // [duenio] ata la llamada a la cuenta que guardó el borrador (MOB-F05-02): InterceptorDuenio la
    // aborta si la sesión vigente es de otra. Las pantallas en línea no la pasan.

    /** La misma [claveIdempotencia] en un reintento devuelve el mismo siniestro (200), no otro. */
    @POST("siniestros")
    suspend fun reportarSiniestro(
        @Header("Idempotency-Key") claveIdempotencia: String,
        @Body solicitud: SolicitudSiniestro,
        @Tag duenio: DuenioSolicitud? = null,
    ): Siniestro

    /** Siniestros del cliente desde cualquier canal, del más reciente al más antiguo. */
    @GET("siniestros")
    suspend fun listarSiniestros(@Tag duenio: DuenioSolicitud? = null): List<Siniestro>

    // ── Seguimiento (WEB-F06) ──────────────────────────────────────────────────────────────────
    // Contrato bff-movil 0.7.0 (#197; vigente 0.8.0). Un siniestro de otro cliente responde 404
    // `siniestro_no_encontrado`, igual que uno que no existe.

    /** El siniestro con su historial (del reporte al estado actual) y sus requerimientos. */
    @GET("siniestros/{id}")
    suspend fun obtenerSiniestro(@Path("id") idSiniestro: String): SiniestroDetalle

    // ── Evidencia (MOB-F05) ────────────────────────────────────────────────────────────────────
    // Contrato bff-movil 0.4.0. El BFF toma `nombre_original` del nombre de archivo de [archivo].
    // Los campos son RequestBody para que el convertidor JSON no les agregue comillas; ver
    // `parteArchivo` y `parteTexto`. Con [idRequerimiento] (0.7.0, WEB-F06) la carga responde un
    // requerimiento pendiente y lo deja atendido; sin él, la carga es la de siempre.

    @Suppress("LongParameterList")
    @Multipart
    @POST("siniestros/{id}/evidencias")
    suspend fun adjuntarEvidencia(
        @Path("id") idSiniestro: String,
        @Part archivo: MultipartBody.Part,
        @Part("tomada_en") tomadaEn: RequestBody?,
        @Part("latitud") latitud: RequestBody?,
        @Part("longitud") longitud: RequestBody?,
        @Part("lugar") lugar: RequestBody?,
        @Part("id_requerimiento") idRequerimiento: RequestBody? = null,
        @Tag duenio: DuenioSolicitud? = null,
    ): Evidencia

    @GET("siniestros/{id}/evidencias")
    suspend fun listarEvidencias(
        @Path("id") idSiniestro: String,
        @Tag duenio: DuenioSolicitud? = null,
    ): List<Evidencia>
}

fun parteArchivo(archivo: File, nombre: String, tipoContenido: String): MultipartBody.Part =
    MultipartBody.Part.createFormData("archivo", nombre, archivo.asRequestBody(tipoContenido.toMediaType()))

/** Archivo cuyo contenido escribe [cuerpo] (p. ej. descifrándolo en flujo, sin copia en claro). */
fun parteArchivo(cuerpo: RequestBody, nombre: String): MultipartBody.Part =
    MultipartBody.Part.createFormData("archivo", nombre, cuerpo)

fun parteTexto(valor: String?): RequestBody? = valor?.toRequestBody("text/plain".toMediaType())
