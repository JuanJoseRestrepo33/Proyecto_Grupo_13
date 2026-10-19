package co.solventa.feature.onboarding.otp

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.core.network.BffApi
import co.solventa.core.network.Cliente
import co.solventa.core.network.SolicitudOtp
import co.solventa.feature.onboarding.R
import co.solventa.feature.onboarding.comun.Resultado
import co.solventa.feature.onboarding.comun.llamarBff
import co.solventa.feature.onboarding.sesion.GestorSesion
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.launch

const val LONGITUD_OTP = 6
const val INTENTOS_OTP = 3

data class EstadoOtp(
    val codigo: String = "",
    val intentosRestantes: Int = INTENTOS_OTP,
    /** Tres intentos fallidos: el código no sirve más, pero la cuenta sigue activa. */
    val bloqueado: Boolean = false,
    val vencido: Boolean = false,
    val enviando: Boolean = false,
    val reenvioDisponibleEn: Instant,
    @StringRes val error: Int? = null,
    @StringRes val aviso: Int? = null,
    /** Correo verificado: la pantalla continúa a la verificación de identidad y lo consume. */
    val verificado: Cliente? = null,
) {
    val puedeVerificar get() = !bloqueado && !vencido && !enviando && codigo.length == LONGITUD_OTP

    fun segundosParaReenvio(ahora: Instant): Long =
        Duration.between(ahora, reenvioDisponibleEn).seconds.coerceAtLeast(0)
}

class OtpViewModel(
    private val api: BffApi,
    private val sesion: GestorSesion,
    private val idRegistro: String,
    reenvioDisponibleEn: Instant,
    private val reloj: () -> Instant,
) : ViewModel() {
    var estado by mutableStateOf(EstadoOtp(reenvioDisponibleEn = reenvioDisponibleEn))
        private set

    fun cambiarCodigo(valor: String) {
        estado = estado.copy(codigo = valor.filter(Char::isDigit).take(LONGITUD_OTP), error = null)
    }

    fun verificar() {
        if (!estado.puedeVerificar) return
        val codigo = estado.codigo
        estado = estado.copy(enviando = true, error = null, aviso = null)
        viewModelScope.launch {
            estado = when (val resultado = llamarBff { api.verificarOtp(idRegistro, SolicitudOtp(codigo)) }) {
                is Resultado.Exito -> {
                    sesion.sesion = resultado.valor
                    estado.copy(enviando = false, verificado = resultado.valor.cliente)
                }
                is Resultado.Falla -> rechazado(resultado).copy(enviando = false, codigo = "")
                Resultado.SinConexion -> estado.copy(enviando = false, error = R.string.error_sin_conexion)
            }
        }
    }

    fun reenviar() {
        if (estado.enviando || estado.segundosParaReenvio(reloj()) > 0) return
        estado = estado.copy(enviando = true, error = null, aviso = null)
        viewModelScope.launch {
            estado = when (val resultado = llamarBff { api.reenviarOtp(idRegistro) }) {
                is Resultado.Exito -> EstadoOtp(
                    intentosRestantes = resultado.valor.intentosRestantes,
                    reenvioDisponibleEn = instante(resultado.valor.reenvioDisponibleEn),
                    aviso = R.string.otp_reenviado,
                )
                is Resultado.Falla -> estado.copy(
                    enviando = false,
                    reenvioDisponibleEn = reloj().plusSeconds(resultado.reintentarEnSegundos ?: 0),
                    error = if (resultado.http == HTTP_DEMASIADAS) {
                        R.string.otp_espera_reenvio
                    } else {
                        R.string.error_generico
                    },
                )
                Resultado.SinConexion -> estado.copy(enviando = false, error = R.string.error_sin_conexion)
            }
        }
    }

    fun verificacionAtendida() {
        estado = estado.copy(verificado = null)
    }

    private fun rechazado(falla: Resultado.Falla): EstadoOtp = when (falla.codigo) {
        "otp_invalido" -> estado.copy(
            intentosRestantes = falla.error?.intentosRestantes ?: (estado.intentosRestantes - 1).coerceAtLeast(0),
            error = R.string.otp_incorrecto,
        )
        "otp_bloqueado" -> estado.copy(bloqueado = true, intentosRestantes = 0, error = R.string.otp_bloqueado)
        "otp_vencido" -> estado.copy(vencido = true, error = R.string.otp_vencido)
        "registro_ya_verificado" -> estado.copy(error = R.string.otp_ya_verificado)
        else -> estado.copy(error = R.string.error_generico)
    }

    private fun instante(iso: String): Instant =
        runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrElse { reloj() }

    private companion object {
        const val HTTP_DEMASIADAS = 429
    }
}
