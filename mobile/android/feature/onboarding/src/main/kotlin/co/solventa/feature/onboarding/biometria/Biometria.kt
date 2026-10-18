package co.solventa.feature.onboarding.biometria

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import co.solventa.feature.onboarding.R
import javax.crypto.Cipher

/** Máximo de lecturas fallidas antes de ofrecer la contraseña (MOB-F01, criterio 5). */
const val FALLOS_BIOMETRICOS = 3

enum class DisponibilidadBiometrica { DISPONIBLE, SIN_ENROLAR, SIN_SENSOR }

sealed interface ResultadoBiometrico {
    /** La huella o el rostro desbloquearon la llave: [cipher] ya puede descifrar. */
    data class Desbloqueado(val cipher: Cipher) : ResultadoBiometrico

    /** Tres lecturas fallidas o bloqueo del sensor: se vuelve a la contraseña sin bloquear la cuenta. */
    data object Fallido : ResultadoBiometrico

    /** El usuario eligió usar su contraseña o cerró el diálogo. */
    data object Cancelado : ResultadoBiometrico

    data object NoDisponible : ResultadoBiometrico
}

/** Para qué se pide la biometría: cambia el título del diálogo. */
enum class PropositoBiometrico { ACTIVAR, INGRESAR }

/**
 * Lectura biométrica local. Solventa nunca recibe el dato biométrico: el sistema solo desbloquea
 * el [Cipher] de una llave del Android Keystore.
 */
interface Biometria {
    fun disponibilidad(): DisponibilidadBiometrica

    fun autenticar(cipher: Cipher, proposito: PropositoBiometrico, alTerminar: (ResultadoBiometrico) -> Unit)
}

/**
 * Reglas de un diálogo biométrico, independientes de Android: cuenta los fallos, cancela al
 * tercero y entrega un único resultado aunque el sistema avise después de la cancelación.
 */
class SesionBiometrica(
    private val cancelar: () -> Unit,
    private val alTerminar: (ResultadoBiometrico) -> Unit,
) {
    private var fallos = 0
    private var terminada = false

    fun exito(cipher: Cipher?) =
        terminar(cipher?.let(ResultadoBiometrico::Desbloqueado) ?: ResultadoBiometrico.NoDisponible)

    fun fallo() {
        fallos += 1
        if (fallos >= FALLOS_BIOMETRICOS && !terminada) {
            terminar(ResultadoBiometrico.Fallido)
            cancelar()
        }
    }

    fun error(codigo: Int) = terminar(
        when (codigo) {
            BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_USER_CANCELED -> ResultadoBiometrico.Cancelado
            BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> ResultadoBiometrico.Fallido
            else -> ResultadoBiometrico.NoDisponible
        },
    )

    private fun terminar(resultado: ResultadoBiometrico) {
        if (terminada) return
        terminada = true
        alTerminar(resultado)
    }
}

fun disponibilidadDe(estado: Int): DisponibilidadBiometrica = when (estado) {
    BiometricManager.BIOMETRIC_SUCCESS -> DisponibilidadBiometrica.DISPONIBLE
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> DisponibilidadBiometrica.SIN_ENROLAR
    else -> DisponibilidadBiometrica.SIN_SENSOR
}

/** [Biometria] sobre BiometricPrompt; solo admite biometría fuerte, la única que ata llaves del Keystore. */
class BiometriaAndroid(private val activity: FragmentActivity) : Biometria {
    override fun disponibilidad() = disponibilidadDe(BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG))

    override fun autenticar(cipher: Cipher, proposito: PropositoBiometrico, alTerminar: (ResultadoBiometrico) -> Unit) {
        var prompt: BiometricPrompt? = null
        val sesion = SesionBiometrica(cancelar = { prompt?.cancelAuthentication() }, alTerminar = alTerminar)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                sesion.exito(result.cryptoObject?.cipher)

            override fun onAuthenticationFailed() = sesion.fallo()

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = sesion.error(errorCode)
        }
        prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        val titulo = when (proposito) {
            PropositoBiometrico.ACTIVAR -> R.string.prompt_titulo_activar
            PropositoBiometrico.INGRESAR -> R.string.prompt_titulo
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(titulo))
            .setSubtitle(activity.getString(R.string.prompt_subtitulo))
            .setNegativeButtonText(activity.getString(R.string.prompt_usar_contrasena))
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
