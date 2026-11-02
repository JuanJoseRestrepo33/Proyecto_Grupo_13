package co.solventa.feature.siniestros

import co.solventa.core.network.BffApi
import co.solventa.core.network.Cliente
import co.solventa.core.network.Consentimiento
import co.solventa.core.network.Credenciales
import co.solventa.core.network.ErrorBff
import co.solventa.core.network.ErrorCampo
import co.solventa.core.network.EstadoPoliza
import co.solventa.core.network.EstadoSiniestro
import co.solventa.core.network.FinalidadConsentimiento
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.Otp
import co.solventa.core.network.PolizaResumen
import co.solventa.core.network.Registro
import co.solventa.core.network.Sesion
import co.solventa.core.network.SiguientePaso
import co.solventa.core.network.Siniestro
import co.solventa.core.network.SolicitudConsentimiento
import co.solventa.core.network.SolicitudOtp
import co.solventa.core.network.SolicitudRegistro
import co.solventa.core.network.SolicitudRenovacion
import co.solventa.core.network.SolicitudSiniestro
import co.solventa.core.network.VerificacionIdentidad
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ReglaMain : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(UnconfinedTestDispatcher())
    override fun finished(description: Description) = Dispatchers.resetMain()
}

fun fallaBff(http: Int, error: ErrorBff): HttpException {
    val cuerpo = NetworkClient.json.encodeToString(ErrorBff.serializer(), error)
    val crudo = okhttp3.Response.Builder()
        .code(http)
        .message("Prueba")
        .protocol(Protocol.HTTP_1_1)
        .request(Request.Builder().url("https://bff.prueba/api/v1/movil/").build())
        .build()
    return HttpException(Response.error<Any>(cuerpo.toResponseBody("application/json".toMediaType()), crudo))
}

fun fallaBff(http: Int, codigo: String, campos: List<ErrorCampo> = emptyList()) =
    fallaBff(http, ErrorBff(codigo, "prueba", campos))

// ── Datos de prueba ────────────────────────────────────────────────────────────────────────────

val HOY: LocalDate = LocalDate.parse("2026-10-26")

val POLIZA_VIGENTE = PolizaResumen("POL-2026-000101", "auto", "2026-09-26", "2027-09-26", EstadoPoliza.VIGENTE)
val POLIZA_VENCIDA = PolizaResumen("POL-2026-000102", "hogar", "2025-09-26", "2026-09-26", EstadoPoliza.VENCIDA)

fun siniestroDe(solicitud: SolicitudSiniestro, referencia: String = "SIN-2026-000123") = Siniestro(
    id = "SIN-1",
    referencia = referencia,
    numeroPoliza = solicitud.numeroPoliza,
    tipoEvento = solicitud.tipoEvento,
    ocurridoEl = solicitud.ocurridoEl,
    descripcion = solicitud.descripcion,
    montoEstimado = solicitud.montoEstimado,
    estado = EstadoSiniestro.REPORTADO,
    siguientePaso = SiguientePaso.ADJUNTAR_EVIDENCIA,
    canal = "movil",
    reportadoEn = "2026-10-26T15:00:00Z",
)

/** Claves predecibles: clave-1, clave-2… */
class Claves {
    var generadas = 0
        private set

    fun nueva(): String {
        generadas += 1
        return "clave-$generadas"
    }
}

// ── BFF doble ─────────────────────────────────────────────────────────────────────────────────

class BffFalso : BffApi {
    var polizas: suspend () -> List<PolizaResumen> = { listOf(POLIZA_VIGENTE, POLIZA_VENCIDA) }
    var reporte: suspend (String, SolicitudSiniestro) -> Siniestro = { _, solicitud -> siniestroDe(solicitud) }

    /** Clave y solicitud de cada `reportarSiniestro`, en orden. */
    val reportes = mutableListOf<Pair<String, SolicitudSiniestro>>()
    var consultasPolizas = 0
        private set

    override suspend fun listarPolizas(): List<PolizaResumen> {
        consultasPolizas += 1
        return polizas()
    }

    override suspend fun reportarSiniestro(claveIdempotencia: String, solicitud: SolicitudSiniestro): Siniestro {
        reportes += claveIdempotencia to solicitud
        return reporte(claveIdempotencia, solicitud)
    }

    override suspend fun crearRegistro(solicitud: SolicitudRegistro): Registro = error("no se usa en siniestros")

    override suspend fun verificarOtp(idRegistro: String, solicitud: SolicitudOtp): Sesion =
        error("no se usa en siniestros")

    override suspend fun reenviarOtp(idRegistro: String): Otp = error("no se usa en siniestros")

    override suspend fun iniciarSesion(credenciales: Credenciales): Sesion = error("no se usa en siniestros")

    override suspend fun renovarSesion(solicitud: SolicitudRenovacion): Sesion = error("no se usa en siniestros")

    override suspend fun obtenerSesion(): Cliente = error("no se usa en siniestros")

    override suspend fun obtenerVerificacionIdentidad(): VerificacionIdentidad = error("no se usa en siniestros")

    override suspend fun solicitarVerificacionIdentidad(): VerificacionIdentidad = error("no se usa en siniestros")

    override suspend fun listarFinalidades(): List<FinalidadConsentimiento> = error("no se usa en siniestros")

    override suspend fun listarConsentimientos(): List<Consentimiento> = error("no se usa en siniestros")

    override suspend fun otorgarConsentimiento(solicitud: SolicitudConsentimiento): Consentimiento =
        error("no se usa en siniestros")

    override suspend fun revocarConsentimiento(idConsentimiento: String): Consentimiento =
        error("no se usa en siniestros")

    override suspend fun renovarConsentimiento(idConsentimiento: String): Consentimiento =
        error("no se usa en siniestros")
}
