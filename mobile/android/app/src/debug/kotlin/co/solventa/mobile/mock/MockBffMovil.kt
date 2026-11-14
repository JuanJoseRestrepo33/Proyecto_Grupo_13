package co.solventa.mobile.mock

import co.solventa.core.network.ErrorBff
import java.time.Instant
import kotlinx.serialization.SerializationException

private val RUTA_OTP = Regex("^registros/([^/]+)/otp/(verificacion|reenvio)$")

// Rutas de consentimiento con segmentos variables (patrón análogo a RUTA_OTP).
// Se ajustarán cuando #160 publique el contrato de bff-movil v0.2.
private val RUTA_CON_ACCION = Regex(
    "^consentimientos/([^/]+)/(revocacion|renovacion)$",
)
private val RUTA_EVIDENCIAS = Regex("^siniestros/([^/]+)/evidencias$")
private val RUTA_SINIESTRO = Regex("^siniestros/([^/]+)$")

/**
 * Implementación en memoria de contratos/bff-movil/openapi.yaml para el build debug.
 * Escenarios: ver README de mobile/android.
 */
class MockBffMovil(reloj: () -> Instant = Instant::now) {
    private val estado = EstadoMock(reloj).apply { sembrarClienteDemo() }
    private val registro = MockRegistro(estado)
    private val sesiones = MockSesiones(estado)
    private val identidad = MockIdentidad(estado)
    private val consentimiento = MockConsentimiento(estado)
    private val siniestros = MockSiniestros(estado)
    private val evidencias = MockEvidencias(estado, siniestros)

    /**
     * @param ruta relativa al prefijo del BFF, sin "/" inicial (p. ej. "registros").
     * @param claveIdempotencia el encabezado `Idempotency-Key`, que exige el reporte de siniestros.
     */
    fun responder(
        metodo: String,
        ruta: String,
        cuerpo: String,
        autorizacion: String?,
        claveIdempotencia: String? = null,
    ): RespuestaMock = try {
        enrutar(metodo, ruta, cuerpo, autorizacion, claveIdempotencia)
    } catch (invalida: SerializationException) {
        respuesta(422, ErrorBff("solicitud_invalida", invalida.message.orEmpty()))
    }

    /** `multipart/form-data`: el interceptor ya separó el archivo y sus campos. */
    fun responderCarga(ruta: String, autorizacion: String?, carga: CargaMock): RespuestaMock {
        val id = RUTA_EVIDENCIAS.matchEntire(ruta)?.groupValues?.get(1) ?: return fallo(404, "ruta_no_simulada")
        return evidencias.adjuntar(autorizacion, id, carga)
    }

    @Suppress("ReturnCount")
    private fun enrutar(
        metodo: String,
        ruta: String,
        cuerpo: String,
        autorizacion: String?,
        claveIdempotencia: String?,
    ): RespuestaMock {
        val fija = enrutarFija(metodo, ruta, cuerpo, autorizacion, claveIdempotencia)
        if (fija != null) return fija
        return enrutarConSegmentos(metodo, ruta, cuerpo, autorizacion)
    }

    private fun enrutarFija(
        metodo: String,
        ruta: String,
        cuerpo: String,
        autorizacion: String?,
        claveIdempotencia: String?,
    ): RespuestaMock? = when ("$metodo $ruta") {
        "POST registros" -> registro.crear(cuerpo)
        "POST sesiones" -> sesiones.iniciar(cuerpo)
        "POST sesiones/renovacion" -> sesiones.renovar(cuerpo)
        "GET sesion" -> sesiones.sesion(autorizacion)
        "GET identidad/verificacion" -> identidad.consultar(autorizacion)
        "POST identidad/verificacion" -> identidad.solicitar(autorizacion)
        "GET consentimientos/finalidades" -> consentimiento.finalidades(autorizacion)
        "GET consentimientos" -> consentimiento.listar(autorizacion)
        "POST consentimientos" -> consentimiento.otorgar(autorizacion, cuerpo)
        "GET polizas" -> siniestros.polizas(autorizacion)
        "POST siniestros" -> siniestros.reportar(autorizacion, cuerpo, claveIdempotencia)
        "GET siniestros" -> siniestros.listar(autorizacion)
        else -> null
    }

    @Suppress("ReturnCount")
    private fun enrutarConSegmentos(
        metodo: String,
        ruta: String,
        cuerpo: String,
        autorizacion: String?,
    ): RespuestaMock {
        val otpMatch = RUTA_OTP.matchEntire(ruta)?.destructured
        if (otpMatch != null) return enrutarOtp(metodo, otpMatch.component1(), otpMatch.component2(), cuerpo)
        val conMatch = RUTA_CON_ACCION.matchEntire(ruta)?.destructured
        if (conMatch != null && metodo == "POST") {
            return enrutarConAccion(conMatch.component1(), conMatch.component2(), autorizacion)
        }
        val evidencia = RUTA_EVIDENCIAS.matchEntire(ruta)?.groupValues?.get(1)
        if (evidencia != null && metodo == "GET") return evidencias.listar(autorizacion, evidencia)
        val siniestro = RUTA_SINIESTRO.matchEntire(ruta)?.groupValues?.get(1)
        if (siniestro != null && metodo == "GET") return siniestros.detalle(autorizacion, siniestro)
        return fallo(404, "ruta_no_simulada")
    }

    private fun enrutarOtp(metodo: String, id: String, accion: String, cuerpo: String): RespuestaMock {
        if (metodo != "POST") return fallo(404, "ruta_no_simulada")
        return if (accion == "verificacion") registro.verificarOtp(id, cuerpo) else registro.reenviar(id)
    }

    private fun enrutarConAccion(id: String, accion: String, autorizacion: String?): RespuestaMock =
        when (accion) {
            "revocacion" -> consentimiento.revocar(autorizacion, id)
            "renovacion" -> consentimiento.renovar(autorizacion, id)
            else -> fallo(404, "ruta_no_simulada")
        }
}
