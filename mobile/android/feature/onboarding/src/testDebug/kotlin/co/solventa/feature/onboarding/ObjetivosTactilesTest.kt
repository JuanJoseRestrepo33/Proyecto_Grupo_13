package co.solventa.feature.onboarding

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.feature.onboarding.biometria.OfertaBiometriaViewModel
import co.solventa.feature.onboarding.biometria.PantallaOfertaBiometria
import co.solventa.feature.onboarding.biometria.ResultadoBiometrico
import co.solventa.feature.onboarding.ingreso.IngresoViewModel
import co.solventa.feature.onboarding.ingreso.PantallaIngreso
import co.solventa.feature.onboarding.otp.OtpViewModel
import co.solventa.feature.onboarding.otp.PantallaOtp
import co.solventa.feature.onboarding.registro.PantallaRegistro
import co.solventa.feature.onboarding.registro.RegistroViewModel
import co.solventa.feature.onboarding.sesion.GestorSesion
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cada control del recorrido de registro e ingreso mide al menos 48 × 48 dp (WEB-F13, P01). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es-w411dp-h914dp")
class ObjetivosTactilesTest {
    @get:Rule(order = 0)
    val main = ReglaMain()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val api = BffFalso()
    private val almacen = AlmacenFalso("REF-0")
    private val gestor = GestorSesion(almacen).apply { sesion = sesionPrueba() }
    private val biometria = BiometriaFalsa(resultado = { ResultadoBiometrico.Cancelado })
    private val dependencias = DependenciasOnboarding(api, gestor, almacen, biometria)

    /** Todos los nodos que responden a un toque, incluidos los campos de texto. */
    private fun controlesMidenAlMenos48dp(minimo: Int) {
        val controles = compose.onAllNodes(hasClickAction())
        val cantidad = controles.fetchSemanticsNodes().size
        assertTrue("se esperaban al menos $minimo controles y hay $cantidad", cantidad >= minimo)
        repeat(cantidad) { controles[it].assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp) }
    }

    @Test
    fun `ingreso con huella activa`() {
        val viewModel = IngresoViewModel(api, gestor, almacen, biometria.disponibilidad())
        compose.setContent { SolventaTheme { PantallaIngreso(viewModel, dependencias, {}, {}) } }
        // Correo, contraseña, iniciar sesión, huella y crear cuenta.
        controlesMidenAlMenos48dp(minimo = 5)
    }

    @Test
    fun `los tres pasos del registro`() {
        compose.setContent { SolventaTheme { PantallaRegistro(RegistroViewModel(api), {}, {}) } }
        // Nombre, correo, celular y continuar.
        controlesMidenAlMenos48dp(minimo = 4)
        compose.onNodeWithTag("campo-nombre").performTextInput("Juan Pérez")
        compose.onNodeWithTag("campo-correo").performTextInput("juan@ejemplo.co")
        compose.onNodeWithTag("campo-celular").performTextInput("3109876543")
        compose.onNodeWithText("Continuar").performClick()
        // Tres tipos de documento, número, continuar y volver.
        controlesMidenAlMenos48dp(minimo = 5)
        compose.onNodeWithTag("campo-documento").performTextInput("1098765430")
        compose.onNodeWithText("Continuar").performClick()
        // Contraseña, confirmación, términos, crear cuenta y volver.
        controlesMidenAlMenos48dp(minimo = 5)
    }

    @Test
    fun `verificacion del codigo`() {
        val ahora = Instant.parse("2026-10-17T10:00:00Z")
        val viewModel = OtpViewModel(api, gestor, "REG-1", ahora.minusSeconds(1)) { ahora }
        compose.setContent { SolventaTheme { PantallaOtp(viewModel, { ahora }, {}) } }
        compose.onNodeWithTag("campo-otp").performTextInput("123456")
        controlesMidenAlMenos48dp(minimo = 3)
    }

    @Test
    fun `oferta de la huella`() {
        val viewModel = OfertaBiometriaViewModel(gestor, AlmacenFalso())
        compose.setContent { SolventaTheme { PantallaOfertaBiometria(viewModel, biometria, {}) } }
        controlesMidenAlMenos48dp(minimo = 2)
    }
}
