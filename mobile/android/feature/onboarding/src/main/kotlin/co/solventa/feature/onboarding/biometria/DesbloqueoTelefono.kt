package co.solventa.feature.onboarding.biometria

import android.app.KeyguardManager
import android.os.Build
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import co.solventa.feature.onboarding.R

/**
 * Desbloqueo local del teléfono (MOB-F03, §7): huella, rostro, PIN o patrón. Protege la entrada sin
 * sesión a la billetera de pólizas. No desbloquea ninguna llave: solo confirma que quien tiene el teléfono
 * conoce su bloqueo de pantalla.
 */
interface DesbloqueoTelefono {
    /** El teléfono tiene bloqueo de pantalla. Sin él no hay desbloqueo local y la entrada no se ofrece. */
    fun disponible(): Boolean

    /** [alTerminar] recibe true solo si el desbloqueo se superó; cancelar o fallar da false. */
    fun desbloquear(alTerminar: (Boolean) -> Unit)
}

/**
 * Los autenticadores del diálogo según la versión de Android. `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` es lo
 * que pide el issue, pero en Android 9 y 10 (API 28 y 29) la librería no admite esa combinación: ahí se usa
 * `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`, que sigue exigiendo el desbloqueo del teléfono.
 */
fun autenticadoresDesbloqueo(sdk: Int): Int = when (sdk) {
    Build.VERSION_CODES.P, Build.VERSION_CODES.Q -> BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    else -> BIOMETRIC_STRONG or DEVICE_CREDENTIAL
}

/** [DesbloqueoTelefono] sobre `BiometricPrompt`, con el PIN, patrón o contraseña del teléfono como respaldo. */
class DesbloqueoTelefonoAndroid(private val activity: FragmentActivity) : DesbloqueoTelefono {
    override fun disponible(): Boolean = activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    override fun desbloquear(alTerminar: (Boolean) -> Unit) {
        if (!disponible()) {
            alTerminar(false)
            return
        }
        var terminado = false
        val terminar = { superado: Boolean ->
            if (!terminado) {
                terminado = true
                alTerminar(superado)
            }
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = terminar(true)

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = terminar(false)
        }
        // Con DEVICE_CREDENTIAL el diálogo no lleva botón negativo: el sistema ofrece el PIN o el patrón.
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.desbloqueo_titulo))
            .setSubtitle(activity.getString(R.string.desbloqueo_subtitulo))
            .setAllowedAuthenticators(autenticadoresDesbloqueo(Build.VERSION.SDK_INT))
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }
}
