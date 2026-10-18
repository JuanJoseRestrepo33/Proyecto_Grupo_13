package co.solventa.mobile.mock

import co.solventa.core.network.ErrorCampo
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.Registro
import co.solventa.core.network.SolicitudOtp
import co.solventa.core.network.SolicitudRegistro
import java.time.Duration

private val DOCUMENTO = Regex("^[A-Za-z0-9]{5,15}$")
private val CORREO = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
private val CELULAR = Regex("^3\\d{9}$")
private const val CONTRASENA_MINIMA = 8
private const val CONTRASENA_MAXIMA = 64

/** Registro y OTP: `POST /registros`, `/registros/{id}/otp/verificacion` y `/otp/reenvio`. */
internal class MockRegistro(private val estado: EstadoMock) {
    fun crear(cuerpo: String): RespuestaMock {
        val solicitud = NetworkClient.json.decodeFromString(SolicitudRegistro.serializer(), cuerpo)
        val campos = validar(solicitud)
        return when {
            campos.isNotEmpty() -> fallo(422, "solicitud_invalida", campos)
            solicitud.correo in estado.cuentas -> fallo(409, "correo_ya_registrado")
            estado.cuentas.values.any { it.numeroDocumento == solicitud.numeroDocumento } ->
                fallo(409, "documento_ya_registrado")
            else -> registrar(solicitud)
        }
    }

    fun verificarOtp(id: String, cuerpo: String): RespuestaMock {
        val codigo = NetworkClient.json.decodeFromString(SolicitudOtp.serializer(), cuerpo).codigo
        val registro = estado.registros[id]
        val cuenta = registro?.let { estado.cuentas[it.correo] }
        return when {
            registro == null || cuenta == null -> fallo(404, "registro_no_encontrado")
            cuenta.correoVerificado -> fallo(409, "registro_ya_verificado")
            registro.intentosRestantes == 0 -> fallo(409, "otp_bloqueado")
            !estado.reloj().isBefore(registro.expiraEn) -> fallo(409, "otp_vencido")
            codigo != CODIGO_OTP -> fallarIntento(registro)
            else -> {
                cuenta.correoVerificado = true
                respuesta(200, estado.abrirSesion(cuenta))
            }
        }
    }

    fun reenviar(id: String): RespuestaMock {
        val registro = estado.registros[id]
        val ahora = estado.reloj()
        return when {
            registro == null -> fallo(404, "registro_no_encontrado")
            estado.cuentas[registro.correo]?.correoVerificado == true -> fallo(409, "registro_ya_verificado")
            ahora.isBefore(registro.reenvioDesde) -> {
                val espera = Duration.between(ahora, registro.reenvioDesde).seconds.coerceAtLeast(1)
                fallo(429, "otp_reenvio_prematuro").copy(encabezados = mapOf("Retry-After" to espera.toString()))
            }
            else -> {
                registro.renovar(ahora)
                respuesta(202, registro.otp())
            }
        }
    }

    private fun registrar(solicitud: SolicitudRegistro): RespuestaMock {
        val cuenta = CuentaMock(
            id = estado.siguiente("CLI"),
            nombreCompleto = solicitud.nombreCompleto.trim(),
            correo = solicitud.correo,
            numeroDocumento = solicitud.numeroDocumento,
            contrasena = solicitud.contrasena,
        )
        val registro = RegistroMock(estado.siguiente("REG"), solicitud.correo, estado.reloj())
        estado.cuentas[cuenta.correo] = cuenta
        estado.registros[registro.id] = registro
        return respuesta(201, Registro(registro.id, cuenta.estadoIdentidad, registro.otp()))
    }

    /** Al tercer fallo el código queda bloqueado; la cuenta no. */
    private fun fallarIntento(registro: RegistroMock): RespuestaMock {
        registro.intentosRestantes -= 1
        return if (registro.intentosRestantes == 0) {
            fallo(409, "otp_bloqueado")
        } else {
            fallo(422, "otp_invalido", intentosRestantes = registro.intentosRestantes)
        }
    }

    // Mismas reglas que SolicitudRegistro en el contrato.
    private fun validar(solicitud: SolicitudRegistro): List<ErrorCampo> = buildList {
        if (solicitud.nombreCompleto.trim().length !in 3..120) add(ErrorCampo("nombre_completo", "longitud_invalida"))
        if (!DOCUMENTO.matches(solicitud.numeroDocumento)) add(ErrorCampo("numero_documento", "formato_invalido"))
        if (!CORREO.matches(solicitud.correo)) add(ErrorCampo("correo", "formato_invalido"))
        if (!CELULAR.matches(solicitud.celular)) add(ErrorCampo("celular", "formato_invalido"))
        if (!contrasenaValida(solicitud.contrasena)) add(ErrorCampo("contrasena", "contrasena_debil"))
        if (solicitud.versionTerminos.isBlank()) add(ErrorCampo("version_terminos", "requerido"))
    }

    private fun contrasenaValida(contrasena: String) =
        contrasena.length in CONTRASENA_MINIMA..CONTRASENA_MAXIMA &&
            contrasena.any(Char::isLetter) && contrasena.any(Char::isDigit)
}
