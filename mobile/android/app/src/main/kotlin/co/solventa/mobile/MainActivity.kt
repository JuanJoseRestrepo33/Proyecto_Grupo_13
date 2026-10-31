package co.solventa.mobile

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.core.network.BffApi
import co.solventa.feature.consentimiento.DependenciasConsentimiento
import co.solventa.feature.onboarding.DependenciasOnboarding
import co.solventa.feature.onboarding.biometria.AlmacenTokenKeystore
import co.solventa.feature.onboarding.biometria.BiometriaAndroid
import co.solventa.feature.onboarding.sesion.GestorSesion
import co.solventa.mobile.navigation.SolventaRaiz

// FragmentActivity: BiometricPrompt la requiere para mostrar el diálogo del sistema.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val proceso = Proceso.de(applicationContext)
        val dependencias = DependenciasOnboarding(
            api = proceso.bffApi,
            sesion = proceso.sesion,
            almacen = proceso.almacen,
            biometria = BiometriaAndroid(this),
        )
        val dependenciasConsentimiento = DependenciasConsentimiento(
            api = proceso.bffApi,
        )
        setContent {
            SolventaTheme { SolventaRaiz(dependencias, dependenciasConsentimiento) }
        }
    }
}

/**
 * Punto de composición: la sesión y el cliente del BFF sobreviven a la recreación de la Activity.
 * La sesión y el ingreso comparten el almacén para rotar el mismo token de refresco.
 */
private class Proceso(context: Context) {
    val almacen = AlmacenTokenKeystore(context)
    val sesion = GestorSesion(almacen)
    val bffApi: BffApi by lazy { BffProvider.create(sesion) }

    companion object {
        @Volatile
        private var instancia: Proceso? = null

        fun de(context: Context): Proceso = instancia ?: synchronized(this) {
            instancia ?: Proceso(context).also { instancia = it }
        }
    }
}
