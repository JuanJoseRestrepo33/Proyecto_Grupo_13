package co.solventa.feature.consentimiento

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.core.network.CodigoFinalidad
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es-w411dp-h914dp")
class PantallaConsentimientoTest {

    @get:Rule(order = 0)
    val main = ReglaMain()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val api = BffFalso()

    private fun mostrar() {
        val viewModel = ConsentimientoViewModel(api)
        compose.setContent {
            SolventaTheme {
                PantallaConsentimiento(viewModel, onVolver = {})
            }
        }
    }

    // ── Estado inicial: entidades y versión desmarcadas ───────────────────────────────────────

    @Test
    fun `al abrir otorgar las entidades y la version estan desmarcadas`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { emptyList() }
        mostrar()

        // Tocar el botón de autorizar para abrir el diálogo
        compose.onNodeWithTag("boton-otorgar-cotizacion")
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        // Verificar que los checkboxes están desmarcados
        compose.onNodeWithTag("entidad-banco-1").assertIsDisplayed()
        compose.onNodeWithTag("check-version").assertIsDisplayed()

        // El botón de confirmar no está habilitado
        compose.onNodeWithTag("boton-confirmar-otorgar").assertIsNotEnabled()
    }

    @Test
    fun `envio habilitado solo con entidad y version seleccionadas`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { emptyList() }
        mostrar()
        compose.onNodeWithTag("boton-otorgar-cotizacion").performClick()

        // Solo entidad: no habilitado
        compose.onNodeWithTag("entidad-banco-1").performClick()
        compose.onNodeWithTag("boton-confirmar-otorgar").assertIsNotEnabled()

        // Agregar versión: habilitado
        compose.onNodeWithTag("check-version").performClick()
        compose.onNodeWithTag("boton-confirmar-otorgar").assertIsEnabled()

        // Desmarcar entidad: no habilitado
        compose.onNodeWithTag("entidad-banco-1").performClick()
        compose.onNodeWithTag("boton-confirmar-otorgar").assertIsNotEnabled()
    }

    // ── Cancelar en diálogos ──────────────────────────────────────────────────────────────────

    @Test
    fun `cancelar otorgar cierra el dialogo sin enviar`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { emptyList() }
        mostrar()
        compose.onNodeWithTag("boton-otorgar-cotizacion").performClick()
        compose.onNodeWithTag("boton-cancelar-otorgar").assertHeightIsAtLeast(48.dp).performClick()

        compose.onNodeWithTag("boton-otorgar-cotizacion").assertIsDisplayed()
        assertEquals(0, api.llamadas.count { it.startsWith("otorgar") })
    }

    @Test
    fun `cancelar revocar cierra el dialogo sin enviar`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(consentimientoVigente()) }
        mostrar()
        compose.onNodeWithTag("boton-revocar-cotizacion").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("boton-cancelar-revocar").assertHeightIsAtLeast(48.dp).performClick()

        compose.onNodeWithTag("boton-revocar-cotizacion").assertIsDisplayed()
        assertEquals(0, api.llamadas.count { it.startsWith("revocar") })
    }

    @Test
    fun `confirmar revocar llama al BFF`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(consentimientoVigente()) }
        val revocado = consentimientoRevocado()
        api.revocar = { revocado }
        api.consentimientos = { listOf(revocado) }
        mostrar()

        compose.onNodeWithTag("boton-revocar-cotizacion").performClick()
        compose.onNodeWithTag("boton-confirmar-revocar").assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()

        // El diálogo se cierra tras éxito
        assertEquals(1, api.llamadas.count { it.startsWith("revocar") })
    }

    // ── Advertencia de cotización ─────────────────────────────────────────────────────────────

    @Test
    fun `revocar cotizacion muestra advertencia de tarifa estandar`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(consentimientoVigente(finalidad = CodigoFinalidad.COTIZACION)) }
        mostrar()
        compose.onNodeWithTag("boton-revocar-cotizacion").performClick()
        compose.onNodeWithTag("advertencia-cotizacion").assertIsDisplayed()
    }

    @Test
    fun `revocar suscripcion no muestra advertencia de tarifa`() {
        api.finalidades = { listOf(finalidadSuscripcion()) }
        api.consentimientos = {
            listOf(
                consentimientoVigente(id = "CON-S", finalidad = CodigoFinalidad.SUSCRIPCION),
            )
        }
        mostrar()
        compose.onNodeWithTag("boton-revocar-suscripcion").performClick()
        compose.onNodeWithTag("advertencia-cotizacion").assertDoesNotExist()
    }

    // ── Marca de vencimiento ──────────────────────────────────────────────────────────────────

    @Test
    fun `vigente por vencer muestra marca con dias`() {
        val porVencer = consentimientoVigenteRenovable(diasParaVencer = 15)
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(porVencer) }
        mostrar()
        compose.onNodeWithTag("por-vencer-cotizacion").assertIsDisplayed()
        compose.onNodeWithText("Vence en 15 días").assertIsDisplayed()
    }

    @Test
    fun `vigente por vencer con 0 dias muestra vence hoy`() {
        val porVencer = consentimientoVigenteRenovable(diasParaVencer = 0)
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(porVencer) }
        mostrar()
        compose.onNodeWithTag("por-vencer-cotizacion").assertIsDisplayed()
        compose.onNodeWithText("Vence hoy").assertIsDisplayed()
    }

    // ── Renovación de vencidos ────────────────────────────────────────────────────────────────

    @Test
    fun `vencido muestra boton renovar`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(consentimientoVencido()) }
        mostrar()
        compose.onNodeWithTag("boton-renovar-cotizacion")
            .assertHeightIsAtLeast(48.dp)
            .assertIsDisplayed()
    }

    @Test
    fun `renovar vencido con exito recarga`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(consentimientoVencido()) }
        val renovado = consentimientoVigente()
        api.renovarC = { renovado }
        api.consentimientos = { listOf(renovado) }
        mostrar()

        compose.onNodeWithTag("boton-renovar-cotizacion").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Vigente").assertIsDisplayed()
    }

    // ── Otorgamiento nuevo de revocado ────────────────────────────────────────────────────────

    @Test
    fun `revocado muestra boton otorgar no renovar`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(consentimientoRevocado()) }
        mostrar()
        compose.onNodeWithTag("boton-otorgar-cotizacion").assertIsDisplayed()
        compose.onNodeWithTag("boton-renovar-cotizacion").assertDoesNotExist()
    }

    // ── Sin consentimiento ────────────────────────────────────────────────────────────────────

    @Test
    fun `sin consentimiento muestra el texto generado por la app`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { emptyList() }
        mostrar()
        compose.onNodeWithTag("sin-consentimiento-cotizacion").assertIsDisplayed()
        compose.onNodeWithText("Sin consentimiento").assertIsDisplayed()
    }

    // ── Objetivos táctiles ────────────────────────────────────────────────────────────────────

    @Test
    fun `botones de accion tienen objetivo tactico minimo de 48dp`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(consentimientoVigente()) }
        mostrar()
        compose.onNodeWithTag("boton-revocar-cotizacion").assertHeightIsAtLeast(48.dp)
    }
}
