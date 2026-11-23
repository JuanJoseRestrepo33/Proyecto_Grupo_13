package co.solventa.feature.polizas

import co.solventa.core.network.BffApi
import co.solventa.core.network.Cliente
import co.solventa.core.network.Cobertura
import co.solventa.core.network.Consentimiento
import co.solventa.core.network.Credenciales
import co.solventa.core.network.Dinero
import co.solventa.core.network.Dispositivo
import co.solventa.core.network.DuenioSolicitud
import co.solventa.core.network.ErrorBff
import co.solventa.core.network.EstadoPoliza
import co.solventa.core.network.Evidencia
import co.solventa.core.network.FinalidadConsentimiento
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.Notificacion
import co.solventa.core.network.Otp
import co.solventa.core.network.PolizaDetalle
import co.solventa.core.network.PolizaResumen
import co.solventa.core.network.Registro
import co.solventa.core.network.Sesion
import co.solventa.core.network.Siniestro
import co.solventa.core.network.SiniestroDetalle
import co.solventa.core.network.SolicitudConsentimiento
import co.solventa.core.network.SolicitudDispositivo
import co.solventa.core.network.SolicitudOtp
import co.solventa.core.network.SolicitudRegistro
import co.solventa.core.network.SolicitudRenovacion
import co.solventa.core.network.SolicitudSiniestro
import co.solventa.core.network.VerificacionIdentidad
import co.solventa.feature.polizas.detalle.DocumentosPoliza
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ReglaMain : TestWatcher() {
    val dispatcher = UnconfinedTestDispatcher()

    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

fun fallaBff(http: Int, codigo: String? = null): HttpException {
    val cuerpo = codigo?.let { NetworkClient.json.encodeToString(ErrorBff.serializer(), ErrorBff(it, "prueba")) }
        ?: "<html>Bad gateway</html>"
    val crudo = okhttp3.Response.Builder()
        .code(http)
        .message("Prueba")
        .protocol(Protocol.HTTP_1_1)
        .request(Request.Builder().url("https://bff.prueba/api/v1/movil/").build())
        .build()
    return HttpException(Response.error<Any>(cuerpo.toResponseBody("application/json".toMediaType()), crudo))
}

// ── Datos de prueba ────────────────────────────────────────────────────────────────────────────

const val NUMERO = "POL-2026-001101"

val VIGENTE = PolizaResumen(
    numero = NUMERO,
    producto = "auto",
    inicio = "2026-10-15",
    fin = "2027-10-14",
    estado = EstadoPoliza.VIGENTE,
    nombreProducto = "Seguro de Auto — Plan Full",
    primaMensual = Dinero.pesos(185_000),
    documentoUrl = "/api/v1/movil/polizas/$NUMERO/documento",
)

/** Como las de la semilla de staging: sin nombre comercial, prima ni documento. */
val VENCIDA = PolizaResumen("POL-2026-001102", "hogar", "2025-01-01", "2025-12-31", EstadoPoliza.VENCIDA)

val POR_INICIAR = PolizaResumen("POL-2026-001103", "viaje", "2026-11-25", "2026-12-25", EstadoPoliza.POR_INICIAR)

val COMPLETA: PolizaDetalle = PolizaDetalle.de(
    VIGENTE,
    coberturas = listOf(
        Cobertura("Responsabilidad civil", "$1.000.000.000"),
        Cobertura("Pérdida total", "100 %"),
        Cobertura("Asistencia en viaje", "Incluida"),
    ),
    deducible = "5% del valor asegurado",
    valorAsegurado = "$60.000.000",
    emitidaEn = "2026-10-15T17:00:00Z",
)

val SIN_DATOS: PolizaDetalle = PolizaDetalle.de(VENCIDA, emptyList(), null, null, null)

/** Guarda el PDF en memoria y devuelve una ruta inventada. */
class DocumentosFalsos : DocumentosPoliza {
    val guardados = mutableMapOf<String, ByteArray>()
    var fallo: Exception? = null

    override fun guardar(numero: String, contenido: InputStream): String {
        fallo?.let { throw it }
        guardados[numero] = contenido.readBytes()
        return "/cache/polizas/$numero.pdf"
    }
}

// ── BFF doble ─────────────────────────────────────────────────────────────────────────────────

class BffFalso : BffApi {
    var polizas: suspend () -> List<PolizaResumen> = { listOf(VIGENTE, VENCIDA, POR_INICIAR) }
    var consultasPolizas = 0
        private set

    override suspend fun listarPolizas(): List<PolizaResumen> {
        consultasPolizas += 1
        return polizas()
    }

    var detalle: suspend (String) -> PolizaDetalle = { COMPLETA }
    var consultasDetalle = 0
        private set

    override suspend fun obtenerPoliza(numero: String): PolizaDetalle {
        consultasDetalle += 1
        return detalle(numero)
    }

    var documento: suspend (String) -> ResponseBody = {
        "%PDF-1.4 prueba".toResponseBody("application/pdf".toMediaType())
    }
    val descargas = mutableListOf<String>()

    override suspend fun descargarDocumentoPoliza(numero: String): ResponseBody {
        descargas += numero
        return documento(numero)
    }

    override suspend fun crearRegistro(solicitud: SolicitudRegistro): Registro = error(NO_SE_USA)

    override suspend fun verificarOtp(idRegistro: String, solicitud: SolicitudOtp): Sesion = error(NO_SE_USA)

    override suspend fun reenviarOtp(idRegistro: String): Otp = error(NO_SE_USA)

    override suspend fun iniciarSesion(credenciales: Credenciales): Sesion = error(NO_SE_USA)

    override suspend fun renovarSesion(solicitud: SolicitudRenovacion): Sesion = error(NO_SE_USA)

    override suspend fun obtenerSesion(): Cliente = error(NO_SE_USA)

    override suspend fun obtenerVerificacionIdentidad(): VerificacionIdentidad = error(NO_SE_USA)

    override suspend fun solicitarVerificacionIdentidad(): VerificacionIdentidad = error(NO_SE_USA)

    override suspend fun listarFinalidades(): List<FinalidadConsentimiento> = error(NO_SE_USA)

    override suspend fun listarConsentimientos(): List<Consentimiento> = error(NO_SE_USA)

    override suspend fun otorgarConsentimiento(solicitud: SolicitudConsentimiento): Consentimiento =
        error(NO_SE_USA)

    override suspend fun revocarConsentimiento(idConsentimiento: String): Consentimiento = error(NO_SE_USA)

    override suspend fun renovarConsentimiento(idConsentimiento: String): Consentimiento = error(NO_SE_USA)

    override suspend fun reportarSiniestro(
        claveIdempotencia: String,
        solicitud: SolicitudSiniestro,
        duenio: DuenioSolicitud?,
    ): Siniestro = error(NO_SE_USA)

    override suspend fun listarSiniestros(duenio: DuenioSolicitud?): List<Siniestro> = error(NO_SE_USA)

    override suspend fun obtenerSiniestro(idSiniestro: String): SiniestroDetalle = error(NO_SE_USA)

    @Suppress("LongParameterList")
    override suspend fun adjuntarEvidencia(
        idSiniestro: String,
        archivo: MultipartBody.Part,
        tomadaEn: RequestBody?,
        latitud: RequestBody?,
        longitud: RequestBody?,
        lugar: RequestBody?,
        idRequerimiento: RequestBody?,
        duenio: DuenioSolicitud?,
    ): Evidencia = error(NO_SE_USA)

    override suspend fun listarEvidencias(idSiniestro: String, duenio: DuenioSolicitud?): List<Evidencia> =
        error(NO_SE_USA)

    // Push (MOB-F06): lo prueba feature/notificaciones.
    override suspend fun registrarDispositivo(solicitud: SolicitudDispositivo): Dispositivo = error(NO_SE_USA)

    override suspend fun darDeBajaDispositivo(idDispositivo: String, autorizacion: String?) = error(NO_SE_USA)

    override suspend fun listarNotificaciones(): List<Notificacion> = error(NO_SE_USA)

    override suspend fun marcarNotificacionLeida(idNotificacion: String) = error(NO_SE_USA)

    private companion object {
        const val NO_SE_USA = "no se usa en pólizas"
    }
}
