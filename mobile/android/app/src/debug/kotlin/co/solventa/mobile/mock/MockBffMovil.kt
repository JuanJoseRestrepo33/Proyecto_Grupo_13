package co.solventa.mobile.mock

import co.solventa.core.network.ErrorBff
import java.time.Instant
import kotlinx.serialization.SerializationException

private val RUTA_OTP = Regex("^registros/([^/]+)/otp/(verificacion|reenvio)$")

// Rutas de consentimiento con segmentos variables (patrón análogo a RUTA_OTP).
// Se ajustarán cuando #160 publique el contrato de bff-movil v0.2.
private val RUTA_CON_ACCION = Regex(
    "^consentimiento/consentimientos/([^/]+)/(revocacion|renovacion)$",
)

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

    /** @param ruta relativa al prefijo del BFF, sin "/" inicial (p. ej. "registros"). */
    fun responder(metodo: String, ruta: String, cuerpo: String, autorizacion: String?): RespuestaMock = try {
        enrutar(metodo, ruta, cuerpo, autorizacion)
    } catch (invalida: SerializationException) {
        respuesta(422, ErrorBff("solicitud_invalida", invalida.message.orEmpty()))
    }

    @Suppress("ReturnCount")
    private fun enrutar(metodo: String, ruta: String, cuerpo: String, autorizacion: String?): RespuestaMock {
        val fija = enrutarFija(metodo, ruta, cuerpo, autorizacion)
        if (fija != null) return fija
        return enrutarConSegmentos(metodo, ruta, cuerpo, autorizacion)
    }

    private fun enrutarFija(
        metodo: String,
        ruta: String,
        cuerpo: String,
        autorizacion: String?,
    ): RespuestaMock? = when ("$metodo $ruta") {
        "POST registros" -> registro.crear(cuerpo)
        "POST sesiones" -> sesiones.iniciar(cuerpo)
        "POST sesiones/renovacion" -> sesiones.renovar(cuerpo)
        "GET sesion" -> sesiones.sesion(autorizacion)
        "GET identidad/verificacion" -> identidad.consultar(autorizacion)
        "POST identidad/verificacion" -> identidad.solicitar(autorizacion)
        "GET consentimiento/finalidades" -> consentimiento.finalidades()
        "GET consentimiento/consentimientos" -> consentimiento.listar(autorizacion)
        "POST consentimiento/consentimientos" -> consentimiento.otorgar(autorizacion, cuerpo)
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
