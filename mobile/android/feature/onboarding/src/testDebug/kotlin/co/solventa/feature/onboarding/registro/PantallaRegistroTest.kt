package co.solventa.feature.onboarding.registro

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.core.network.EstadoIdentidad
import co.solventa.core.network.Registro
import co.solventa.feature.onboarding.BffFalso
import co.solventa.feature.onboarding.ReglaMain
import co.solventa.feature.onboarding.otp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es-w411dp-h914dp")
class PantallaRegistroTest {
    @get:Rule(order = 0)
    val main = ReglaMain()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val api = BffFalso().apply {
        registro = { Registro("REG-9", EstadoIdentidad.PENDIENTE_VERIFICACION, otp()) }
    }
    private var registrado: Registro? = null
    private var salio = false

    private fun mostrar() {
        compose.setContent {
            SolventaTheme { PantallaRegistro(RegistroViewModel(api), { registrado = it }, { salio = true }) }
        }
    }

    private fun escribir(campo: Campo, texto: String) =
        compose.onNodeWithTag("campo-${campo.name.lowercase()}").performTextInput(texto)

    private fun continuar() = compose.onNodeWithText("Continuar").performClick()

    @Test
    fun `el error de un campo aparece al salir de el, sin esperar a continuar`() {
        mostrar()
        escribir(Campo.CORREO, "juan@")
        compose.onNodeWithText("Escribe un correo válido, por ejemplo nombre@correo.com.").assertDoesNotExist()
        escribir(Campo.CELULAR, "3")
        compose.onNodeWithText("Escribe un correo válido, por ejemplo nombre@correo.com.").assertIsDisplayed()
    }

    @Test
    fun `continuar con datos faltantes muestra que son obligatorios`() {
        mostrar()
        continuar()
        compose.onNodeWithTag("paso-datos").assertIsDisplayed()
        assertEquals(3, compose.onAllNodesWithText("Este dato es obligatorio.").fetchSemanticsNodes().size)
    }

    @Test
    fun `el asistente recorre los tres pasos y crea la cuenta`() {
        mostrar()
        compose.onNodeWithText("Paso 1 de 3: Tus datos").assertIsDisplayed()
        escribir(Campo.NOMBRE, "Juan Pérez")
        escribir(Campo.CORREO, "juan@ejemplo.co")
        escribir(Campo.CELULAR, "3109876543")
        continuar()

        compose.onNodeWithText("Paso 2 de 3: Tu documento").assertIsDisplayed()
        compose.onNodeWithText("Pasaporte").performClick()
        escribir(Campo.DOCUMENTO, "AB123456")
        continuar()

        compose.onNodeWithText("Paso 3 de 3: Tu contraseña").assertIsDisplayed()
        escribir(Campo.CONTRASENA, "Clave2026")
        escribir(Campo.CONFIRMACION, "Clave2026")
        compose.onNodeWithTag("terminos").performClick()
        compose.onNodeWithText("Crear cuenta").performClick()

        compose.waitForIdle()
        assertEquals("REG-9", registrado?.id)
    }

    @Test
    fun `volver desde el primer paso sale del asistente y desde los demas retrocede`() {
        mostrar()
        escribir(Campo.NOMBRE, "Juan Pérez")
        escribir(Campo.CORREO, "juan@ejemplo.co")
        escribir(Campo.CELULAR, "3109876543")
        continuar()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithTag("paso-datos").assertIsDisplayed()
        compose.onNodeWithText("Juan Pérez").assertIsDisplayed()
        compose.onNodeWithContentDescription("Volver").performClick()
        assertTrue(salio)
    }

    @Test
    fun `los controles tactiles miden al menos 48 dp`() {
        mostrar()
        compose.onNodeWithText("Continuar").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("¿Ya tienes cuenta? Inicia sesión").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Volver").assertHeightIsAtLeast(48.dp)
        escribir(Campo.NOMBRE, "Juan Pérez")
        escribir(Campo.CORREO, "juan@ejemplo.co")
        escribir(Campo.CELULAR, "3109876543")
        continuar()
        compose.onNode(hasClickAction() and hasText("Pasaporte")).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("campo-documento").assertHeightIsAtLeast(48.dp)
    }
}
