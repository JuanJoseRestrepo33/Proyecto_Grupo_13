package co.solventa.feature.onboarding.biometria

import android.os.Build
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import org.junit.Assert.assertEquals
import org.junit.Test

class DesbloqueoTelefonoTest {
    @Test
    fun `pide huella fuerte o el bloqueo del telefono`() {
        listOf(Build.VERSION_CODES.O, Build.VERSION_CODES.O_MR1, Build.VERSION_CODES.R, 35).forEach { sdk ->
            assertEquals("API $sdk", BIOMETRIC_STRONG or DEVICE_CREDENTIAL, autenticadoresDesbloqueo(sdk))
        }
    }

    @Test
    fun `en Android 9 y 10 la libreria no admite huella fuerte con PIN y se usa la debil con PIN`() {
        listOf(Build.VERSION_CODES.P, Build.VERSION_CODES.Q).forEach { sdk ->
            assertEquals("API $sdk", BIOMETRIC_WEAK or DEVICE_CREDENTIAL, autenticadoresDesbloqueo(sdk))
        }
    }
}
