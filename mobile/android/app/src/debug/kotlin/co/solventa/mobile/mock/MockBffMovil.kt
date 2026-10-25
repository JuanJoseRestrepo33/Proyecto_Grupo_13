package co.solventa.mobile.mock

import co.solventa.core.network.ErrorBff
import java.time.Instant
import kotlinx.serialization.SerializationException

private val RUTA_OTP = Regex("^registros/([^/]+)/otp/(verificacion|reenvio)$")

// Rutas de consentimiento con segmentos variables (patrón análogo a RUTA_OTP).
// Se ajustarán cuando #160 publique el contrato de bff-movil v0.2.
// - POST  consentimiento/consentimientos
// - GET   consentimiento/consentimientos
// - GET   consentimiento/finalidades
// - POST  consentimiento/consentimientos/{id}/revocacion
// - POST  consentimiento/consentimientos/{id}/renovacion
private val RUTA_CONSENTIMIENTO_ACCION = Regex(
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

    private fun enrutar(metodo: String, ruta: String, cuerpo: String, autorizacion: String?): RespuestaMock =
        when ("$metodo $ruta") {
            "POST registros" -> registro.crear(cuerpo)
            "POST sesiones" -> sesiones.iniciar(cuerpo)
            "POST sesiones/renovacion" -> sesiones.renovar(cuerpo)
            "GET sesion" -> sesiones.sesion(autorizacion)
            "GET identidad/verificacion" -> identidad.consultar(autorizacion)
            "POST identidad/verificacion" -> identidad.solicitar(autorizacion)
            "GET consentimiento/finalidades" -> consentimiento.finalidades()
            "GET consentimiento/consentimientos" -> consentimiento.listar(autorizacion)
            "POST consentimiento/consentimientos" -> consentimiento.otorgar(autorizacion, cuerpo)
            else -> enrutarConSegmentos(metodo, ruta, cuerpo, autorizacion)
        }

    private fun enrutarConSegmentos(metodo: String, ruta: String, cuerpo: String, autorizacion: String?): RespuestaMock {
        val otpMatch = RUTA_OTP.matchEntire(ruta)?.destructured
        if (otpMatch != null) {
            return when {
                metodo != "POST" -> fallo(404, "ruta_no_simulada")
                otpMatch.component2() == "verificacion" -> registro.verificarOtp(otpMatch.component1(), cuerpo)
                else -> registro.reenviar(otpMatch.component1())
            }
        }

        val accionMatch = RUTA_CONSENTIMIENTO_ACCION.matchEntire(ruta)?.destructured
        if (accionMatch != null && metodo == "POST") {
            val idConsentimiento = accionMatch.component1()
            return when (accionMatch.component2()) {
                "revocacion" -> consentimiento.revocar(autorizacion, idConsentimiento)
                "renovacion" -> consentimiento.renovar(autorizacion, idConsentimiento)
                else -> fallo(404, "ruta_no_simulada")
            }
        }

        return fallo(404, "ruta_no_simulada")
    }
}
