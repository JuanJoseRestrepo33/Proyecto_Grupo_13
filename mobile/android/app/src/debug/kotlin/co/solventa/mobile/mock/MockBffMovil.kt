package co.solventa.mobile.mock

import co.solventa.core.network.ErrorBff
import java.time.Instant
import kotlinx.serialization.SerializationException

private val RUTA_OTP = Regex("^registros/([^/]+)/otp/(verificacion|reenvio)$")

/**
 * Implementación en memoria de contratos/bff-movil/openapi.yaml para el build debug.
 * Escenarios: ver README de mobile/android.
 */
class MockBffMovil(reloj: () -> Instant = Instant::now) {
    private val estado = EstadoMock(reloj).apply { sembrarClienteDemo() }
    private val registro = MockRegistro(estado)
    private val sesiones = MockSesiones(estado)
    private val identidad = MockIdentidad(estado)

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
            else -> enrutarOtp(metodo, ruta, cuerpo)
        }

    private fun enrutarOtp(metodo: String, ruta: String, cuerpo: String): RespuestaMock {
        val otp = RUTA_OTP.matchEntire(ruta)?.destructured
        return when {
            metodo != "POST" || otp == null -> fallo(404, "ruta_no_simulada")
            otp.component2() == "verificacion" -> registro.verificarOtp(otp.component1(), cuerpo)
            else -> registro.reenviar(otp.component1())
        }
    }
}
