package co.solventa.mobile.mock

import co.solventa.core.network.Cliente
import co.solventa.core.network.ErrorBff
import co.solventa.core.network.ErrorCampo
import co.solventa.core.network.EstadoIdentidad
import co.solventa.core.network.EstadoVerificacion
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.Otp
import co.solventa.core.network.Sesion
import co.solventa.core.network.VerificacionIdentidad
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.encodeToString

/** Código OTP que acepta el mock. */
const val CODIGO_OTP = "123456"

/** Cliente sembrado, ya verificado, para probar el ingreso sin registrarse. */
const val CORREO_DEMO = "ana@ejemplo.co"
const val CONTRASENA_DEMO = "Solventa2026"

internal const val INTENTOS_OTP = 3
internal val VIGENCIA_OTP: Duration = Duration.ofMinutes(10)
internal val ESPERA_REENVIO: Duration = Duration.ofSeconds(30)
private val VIGENCIA_ACCESO: Duration = Duration.ofMinutes(15)

data class RespuestaMock(val codigo: Int, val cuerpo: String, val encabezados: Map<String, String> = emptyMap())

internal inline fun <reified T> respuesta(codigo: Int, valor: T) =
    RespuestaMock(codigo, NetworkClient.json.encodeToString(valor))

internal fun fallo(
    codigo: Int,
    clave: String,
    campos: List<ErrorCampo> = emptyList(),
    intentosRestantes: Int? = null,
) = respuesta(codigo, ErrorBff(clave, "Mock BFF móvil: $clave", campos, intentosRestantes))

internal fun noAutenticado() = fallo(401, "no_autenticado")

internal class CuentaMock(
    val id: String,
    val nombreCompleto: String,
    val correo: String,
    val numeroDocumento: String,
    val contrasena: String,
) {
    var correoVerificado = false
    var estadoIdentidad = EstadoIdentidad.PENDIENTE_VERIFICACION
        private set
    var verificacion: VerificacionIdentidad? = null
        private set

    fun cliente() = Cliente(id, nombreCompleto, correo, estadoIdentidad)

    fun aplicar(resultado: VerificacionIdentidad) {
        verificacion = resultado
        estadoIdentidad = when (resultado.estado) {
            EstadoVerificacion.APROBADA -> EstadoIdentidad.VERIFICADA
            EstadoVerificacion.RECHAZADA -> EstadoIdentidad.RECHAZADA
            EstadoVerificacion.EN_REVISION -> EstadoIdentidad.EN_REVISION
            EstadoVerificacion.EN_PROCESO -> EstadoIdentidad.PENDIENTE_VERIFICACION
        }
    }
}

internal class RegistroMock(val id: String, val correo: String, ahora: Instant) {
    var intentosRestantes = INTENTOS_OTP
    var expiraEn: Instant = ahora + VIGENCIA_OTP
        private set
    var reenvioDesde: Instant = ahora + ESPERA_REENVIO
        private set

    /** Un código nuevo invalida el anterior y devuelve los intentos. */
    fun renovar(ahora: Instant) {
        intentosRestantes = INTENTOS_OTP
        expiraEn = ahora + VIGENCIA_OTP
        reenvioDesde = ahora + ESPERA_REENVIO
    }

    fun otp() = Otp(expiraEn.toString(), intentosRestantes, reenvioDesde.toString())
}

internal class EstadoMock(val reloj: () -> Instant) {
    val cuentas = mutableMapOf<String, CuentaMock>()
    val registros = mutableMapOf<String, RegistroMock>()
    private val accesos = mutableMapOf<String, String>()
    val refrescos = mutableMapOf<String, String>()
    private var secuencia = 0

    fun siguiente(prefijo: String): String {
        secuencia += 1
        return "$prefijo-2026-${secuencia.toString().padStart(6, '0')}"
    }

    fun abrirSesion(cuenta: CuentaMock): Sesion {
        val acceso = siguiente("ACC")
        val refresco = siguiente("REF")
        accesos[acceso] = cuenta.correo
        refrescos[refresco] = cuenta.correo
        return Sesion(acceso, refresco, (reloj() + VIGENCIA_ACCESO).toString(), cuenta.cliente())
    }

    fun cuentaAutenticada(autorizacion: String?): CuentaMock? =
        autorizacion?.removePrefix("Bearer ")?.let(accesos::get)?.let(cuentas::get)

    fun sembrarClienteDemo() {
        val cuenta = CuentaMock(siguiente("CLI"), "Ana María Rojas", CORREO_DEMO, "1020304050", CONTRASENA_DEMO)
        cuenta.correoVerificado = true
        cuenta.aplicar(
            VerificacionIdentidad(siguiente("KYC"), EstadoVerificacion.APROBADA, actualizadaEn = reloj().toString()),
        )
        cuentas[cuenta.correo] = cuenta
    }
}
