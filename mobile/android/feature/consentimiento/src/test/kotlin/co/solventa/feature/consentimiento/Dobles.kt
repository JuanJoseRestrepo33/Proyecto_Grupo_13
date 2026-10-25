package co.solventa.feature.consentimiento

import co.solventa.core.network.BffApi
import co.solventa.core.network.Cliente
import co.solventa.core.network.CodigoFinalidad
import co.solventa.core.network.Consentimiento
import co.solventa.core.network.Credenciales
import co.solventa.core.network.EntidadConsentimiento
import co.solventa.core.network.ErrorBff
import co.solventa.core.network.ErrorCampo
import co.solventa.core.network.EstadoConsentimiento
import co.solventa.core.network.EstadoIdentidad
import co.solventa.core.network.FinalidadConsentimiento
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.Otp
import co.solventa.core.network.Registro
import co.solventa.core.network.Sesion
import co.solventa.core.network.SolicitudConsentimiento
import co.solventa.core.network.SolicitudOtp
import co.solventa.core.network.SolicitudRegistro
import co.solventa.core.network.SolicitudRenovacion
import co.solventa.core.network.VerificacionIdentidad
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

fun fallaBff(
    http: Int,
    codigo: String,
    campos: List<ErrorCampo> = emptyList(),
    encabezados: Map<String, String> = emptyMap(),
): HttpException {
    val error = ErrorBff(codigo, "prueba", campos, null)
    val cuerpo = NetworkClient.json.encodeToString(ErrorBff.serializer(), error)
    val crudo = okhttp3.Response.Builder()
        .code(http)
        .message("Prueba")
        .protocol(Protocol.HTTP_1_1)
        .request(Request.Builder().url("https://bff.prueba/api/v1/movil/").build())
        .apply { encabezados.forEach { (nombre, valor) -> header(nombre, valor) } }
        .build()
    return HttpException(Response.error<Any>(cuerpo.toResponseBody("application/json".toMediaType()), crudo))
}

/** Respuesta de error sin cuerpo del contrato (p. ej., de un proxy): sin código estable. */
fun fallaSinCodigo(http: Int): HttpException {
    val crudo = okhttp3.Response.Builder()
        .code(http)
        .message("Prueba")
        .protocol(Protocol.HTTP_1_1)
        .request(Request.Builder().url("https://bff.prueba/api/v1/movil/").build())
        .build()
    return HttpException(Response.error<Any>("<html></html>".toResponseBody("text/html".toMediaType()), crudo))
}

// ── Datos de prueba ────────────────────────────────────────────────────────────────────────────

val ENTIDAD_BANCO = EntidadConsentimiento("banco-1", "Banco Uno")
val ENTIDAD_CAJA = EntidadConsentimiento("caja-1", "Caja de Ahorros")

fun finalidadCotizacion() = FinalidadConsentimiento(
    codigo = CodigoFinalidad.COTIZACION,
    nombre = "Cotización",
    descripcion = "Permite cotizar con datos de Open Finance",
    alcance = listOf("saldos", "movimientos"),
    vigenciaMeses = 6,
    entidadesOrigen = listOf(ENTIDAD_BANCO, ENTIDAD_CAJA),
    versionTexto = "cotizacion-2026.1",
)

fun finalidadSuscripcion() = FinalidadConsentimiento(
    codigo = CodigoFinalidad.SUSCRIPCION,
    nombre = "Suscripción",
    descripcion = "Permite suscripción con perfil financiero",
    alcance = listOf("saldos"),
    vigenciaMeses = 12,
    entidadesOrigen = listOf(ENTIDAD_BANCO),
    versionTexto = "suscripcion-2026.1",
)

fun consentimientoVigente(
    id: String = "CON-1",
    finalidad: CodigoFinalidad = CodigoFinalidad.COTIZACION,
    diasParaVencer: Int? = 60,
    porVencer: Boolean = false,
) = Consentimiento(
    id = id,
    finalidad = finalidad,
    entidadesOrigen = listOf(ENTIDAD_BANCO),
    estado = EstadoConsentimiento.VIGENTE,
    canal = "movil",
    otorgadoEn = "2026-01-01T10:00:00Z",
    venceEn = "2026-07-01T10:00:00Z",
    revocadoEn = null,
    versionTexto = "cotizacion-2026.1",
    porVencer = porVencer,
    diasParaVencer = diasParaVencer,
)

fun consentimientoRevocado(id: String = "CON-2", finalidad: CodigoFinalidad = CodigoFinalidad.COTIZACION) =
    Consentimiento(
        id = id,
        finalidad = finalidad,
        entidadesOrigen = listOf(ENTIDAD_BANCO),
        estado = EstadoConsentimiento.REVOCADO,
        canal = "movil",
        otorgadoEn = "2025-01-01T10:00:00Z",
        venceEn = "2025-07-01T10:00:00Z",
        revocadoEn = "2025-06-01T10:00:00Z",
        versionTexto = "cotizacion-2026.1",
        porVencer = false,
        diasParaVencer = null,
    )

fun consentimientoVencido(id: String = "CON-3", finalidad: CodigoFinalidad = CodigoFinalidad.COTIZACION) =
    Consentimiento(
        id = id,
        finalidad = finalidad,
        entidadesOrigen = listOf(ENTIDAD_BANCO),
        estado = EstadoConsentimiento.VENCIDO,
        canal = "movil",
        otorgadoEn = "2025-01-01T10:00:00Z",
        venceEn = "2025-07-01T10:00:00Z",
        revocadoEn = null,
        versionTexto = "cotizacion-2026.1",
        porVencer = false,
        diasParaVencer = null,
    )

fun consentimientoVigenteRenovable(id: String = "CON-4", diasParaVencer: Int = 0) = consentimientoVigente(
    id = id,
    porVencer = true,
    diasParaVencer = diasParaVencer,
)

// ── BFF doble ─────────────────────────────────────────────────────────────────────────────────

class BffFalso : BffApi {
    var registro: suspend (SolicitudRegistro) -> Registro = { error("no programado") }
    var verificacionOtp: suspend (String, SolicitudOtp) -> Sesion = { _, _ -> error("no programado") }
    var reenvio: suspend (String) -> Otp = { error("no programado") }
    var ingreso: suspend (Credenciales) -> Sesion = { error("no programado") }
    var renovacion: suspend (SolicitudRenovacion) -> Sesion = { error("no programado") }
    var consulta: suspend () -> VerificacionIdentidad = { error("no programado") }
    var solicitud: suspend () -> VerificacionIdentidad = { error("no programado") }
    var finalidades: suspend () -> List<FinalidadConsentimiento> = { error("listarFinalidades no programado") }
    var consentimientos: suspend () -> List<Consentimiento> = { error("listarConsentimientos no programado") }
    var otorgar: suspend (SolicitudConsentimiento) -> Consentimiento = { error("otorgarConsentimiento no programado") }
    var revocar: suspend (String) -> Consentimiento = { error("revocarConsentimiento no programado") }
    var renovarC: suspend (String) -> Consentimiento = { error("renovarConsentimiento no programado") }
    val llamadas = mutableListOf<String>()

    override suspend fun crearRegistro(s: SolicitudRegistro): Registro {
        llamadas += "registro"; return registro(s)
    }

    override suspend fun verificarOtp(id: String, s: SolicitudOtp): Sesion {
        llamadas += "otp"; return verificacionOtp(id, s)
    }

    override suspend fun reenviarOtp(id: String): Otp {
        llamadas += "reenvio"; return reenvio(id)
    }

    override suspend fun iniciarSesion(c: Credenciales): Sesion {
        llamadas += "ingreso"; return ingreso(c)
    }

    override suspend fun renovarSesion(s: SolicitudRenovacion): Sesion {
        llamadas += "renovacion"; return renovacion(s)
    }

    override suspend fun obtenerSesion(): Cliente = error("no se usa en consentimiento")

    override suspend fun obtenerVerificacionIdentidad(): VerificacionIdentidad {
        llamadas += "consulta"; return consulta()
    }

    override suspend fun solicitarVerificacionIdentidad(): VerificacionIdentidad {
        llamadas += "solicitud"; return solicitud()
    }

    override suspend fun listarFinalidades(): List<FinalidadConsentimiento> {
        llamadas += "finalidades"
        return finalidades()
    }

    override suspend fun listarConsentimientos(): List<Consentimiento> {
        llamadas += "consentimientos"
        return consentimientos()
    }

    override suspend fun otorgarConsentimiento(s: SolicitudConsentimiento): Consentimiento {
        llamadas += "otorgar:${s.finalidad}"
        return otorgar(s)
    }

    override suspend fun revocarConsentimiento(id: String): Consentimiento {
        llamadas += "revocar:$id"
        return revocar(id)
    }

    override suspend fun renovarConsentimiento(id: String): Consentimiento {
        llamadas += "renovar:$id"
        return renovarC(id)
    }
}

fun sesionesPorDefecto(api: BffFalso) {
    api.finalidades = { listOf(finalidadCotizacion(), finalidadSuscripcion()) }
    api.consentimientos = { emptyList() }
}
