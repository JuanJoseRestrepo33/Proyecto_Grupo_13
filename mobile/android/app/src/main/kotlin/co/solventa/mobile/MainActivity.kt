package co.solventa.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.core.network.BffApi
import co.solventa.feature.onboarding.DependenciasOnboarding
import co.solventa.feature.onboarding.biometria.AlmacenTokenKeystore
import co.solventa.feature.onboarding.biometria.BiometriaAndroid
import co.solventa.feature.onboarding.sesion.GestorSesion
import co.solventa.mobile.navigation.SolventaRaiz

// FragmentActivity: BiometricPrompt la requiere para mostrar el diálogo del sistema.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dependencias = DependenciasOnboarding(
            api = Proceso.bffApi,
            sesion = Proceso.sesion,
            almacen = AlmacenTokenKeystore(applicationContext),
            biometria = BiometriaAndroid(this),
        )
        setContent {
            SolventaTheme { SolventaRaiz(dependencias) }
        }
    }
}

/** Punto de composición: la sesión y el cliente del BFF sobreviven a la recreación de la Activity. */
private object Proceso {
    val sesion = GestorSesion()
    val bffApi: BffApi by lazy { BffProvider.create(tokenAcceso = { sesion.tokenAcceso }) }
}
