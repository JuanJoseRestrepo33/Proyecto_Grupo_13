package co.solventa.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.core.network.BffApi
import co.solventa.mobile.navigation.SolventaApp

class MainActivity : ComponentActivity() {
    // Punto de composición: las futuras features reciben la API por inyección.
    val bffApi: BffApi by lazy { BffProvider.create() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SolventaTheme { SolventaApp() }
        }
    }
}
