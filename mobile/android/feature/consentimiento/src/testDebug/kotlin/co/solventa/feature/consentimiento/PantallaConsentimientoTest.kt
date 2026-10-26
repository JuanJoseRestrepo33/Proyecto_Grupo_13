package co.solventa.feature.consentimiento

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.core.network.CodigoFinalidad
import co.solventa.core.network.Consentimiento
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
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
    private var volvio = 0
    private var sesionDelegada = 0

    /** Ciclo de vida controlado por la prueba para simular salir y volver a primer plano. */
    private class CicloPrueba : LifecycleOwner {
        val registro: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registro
    }

    private fun mostrar(
        consentimientos: List<Consentimiento> = emptyList(),
        ciclo: CicloPrueba = CicloPrueba().apply { registro.currentState = Lifecycle.State.RESUMED },
    ) {
        api.finalidades = { listOf(finalidadCotizacion(), finalidadSuscripcion()) }
        api.consentimientos = { consentimientos }
        val viewModel = ConsentimientoViewModel(api)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides ciclo) {
                SolventaTheme {
                    PantallaConsentimiento(viewModel, onVolver = { volvio++ }, onSesionVencida = { sesionDelegada++ })
                }
            }
        }
    }

    private fun consultas() = api.llamadas.count { it == "consentimientos" }

    // ── Carga y actualización entre canales ───────────────────────────────────────────────────

    @Test
    fun `consulta al entrar y de nuevo al volver a primer plano`() {
        val ciclo = CicloPrueba().apply { registro.currentState = Lifecycle.State.RESUMED }
        mostrar(ciclo = ciclo)
        compose.waitForIdle()
        assertEquals(1, consultas())

        api.consentimientos = { listOf(consentimientoVigente()) }
        compose.runOnIdle { ciclo.registro.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { ciclo.registro.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()

        assertEquals(2, consultas())
        compose.onNodeWithTag("boton-revocar-cotizacion").assertIsDisplayed()
    }

    @Test
    fun `pull-to-refresh vuelve a consultar`() {
        mostrar()
        api.consentimientos = { listOf(consentimientoVigente()) }
        compose.onNodeWithTag("pull-to-refresh").performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertEquals(2, consultas())
        compose.onNodeWithTag("boton-revocar-cotizacion").assertIsDisplayed()
    }

    @Test
    fun `carga fallida muestra error con reintentar y no una lista vacia`() {
        api.finalidades = { throw IOException("sin red") }
        api.consentimientos = { emptyList() }
        val viewModel = ConsentimientoViewModel(api)
        compose.setContent { SolventaTheme { PantallaConsentimiento(viewModel, onVolver = {}) } }

        compose.onNodeWithTag("error-carga").assertIsDisplayed()
        compose.onNodeWithTag("sin-consentimiento-cotizacion").assertDoesNotExist()

        api.finalidades = { listOf(finalidadCotizacion()) }
        compose.onNodeWithTag("boton-reintentar").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("sin-consentimiento-cotizacion").assertIsDisplayed()
    }

    @Test
    fun `sin acciones mientras la carga esta en curso`() {
        mostrar(consentimientos = listOf(consentimientoVigente()))
        val pendiente = CompletableDeferred<List<Consentimiento>>()
        api.consentimientos = { pendiente.await() }
        compose.onNodeWithTag("pull-to-refresh").performTouchInput { swipeDown() }

        compose.onNodeWithTag("boton-revocar-cotizacion").assertIsNotEnabled()
        compose.runOnIdle { pendiente.complete(listOf(consentimientoVigente())) }
        compose.onNodeWithTag("boton-revocar-cotizacion").assertIsEnabled()
    }

    @Test
    fun `sin consentimiento muestra el texto generado por la app`() {
        mostrar()
        compose.onNodeWithTag("sin-consentimiento-cotizacion").assertIsDisplayed()
    }

    @Test
    fun `volver regresa a Perfil`() {
        mostrar()
        compose.onNodeWithContentDescription("Volver a Perfil").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, volvio)
    }

    // ── Otorgar ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `entidades y aceptacion inician desmarcadas`() {
        mostrar()
        compose.onNodeWithTag("boton-otorgar-cotizacion").assertHeightIsAtLeast(48.dp).performClick()

        compose.onNodeWithTag("entidad-banco-1").assertIsOff().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("entidad-caja-1").assertIsOff()
        compose.onNodeWithTag("check-version").assertIsOff().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("boton-confirmar-otorgar").assertIsNotEnabled()
    }

    @Test
    fun `envio habilitado solo con entidad y aceptacion`() {
        mostrar()
        compose.onNodeWithTag("boton-otorgar-cotizacion").performClick()

        compose.onNodeWithTag("entidad-banco-1").performClick().assertIsOn()
        compose.onNodeWithTag("boton-confirmar-otorgar").assertIsNotEnabled()
        compose.onNodeWithTag("check-version").performClick().assertIsOn()
        compose.onNodeWithTag("boton-confirmar-otorgar").assertIsEnabled()
        compose.onNodeWithTag("entidad-banco-1").performClick()
        compose.onNodeWithTag("boton-confirmar-otorgar").assertIsNotEnabled()
    }

    @Test
    fun `otorgar confirma tras la respuesta del servidor`() {
        mostrar()
        api.otorgar = { consentimientoVigente() }
        compose.onNodeWithTag("boton-otorgar-cotizacion").performClick()
        compose.onNodeWithTag("entidad-banco-1").performClick()
        compose.onNodeWithTag("check-version").performClick()
        api.consentimientos = { listOf(consentimientoVigente()) }
        compose.onNodeWithTag("boton-confirmar-otorgar").performClick()

        compose.onNodeWithTag("mensaje-exito").assertIsDisplayed()
        compose.onNodeWithTag("boton-revocar-cotizacion").assertIsDisplayed()
    }

    @Test
    fun `version desactualizada se explica dentro del formulario y exige aceptar otra vez`() {
        mostrar()
        compose.onNodeWithTag("boton-otorgar-cotizacion").performClick()
        compose.onNodeWithTag("entidad-banco-1").performClick()
        compose.onNodeWithTag("check-version").performClick()
        api.otorgar = { throw fallaBff(422, "version_texto_desactualizada") }
        api.finalidades = {
            listOf(finalidadCotizacion().copy(versionTexto = "cotizacion-2026.2"), finalidadSuscripcion())
        }
        compose.onNodeWithTag("boton-confirmar-otorgar").performClick()

        compose.onNodeWithTag("error-dialogo").assertIsDisplayed()
        compose.onNodeWithText("cotizacion-2026.2", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("check-version").assertIsOff()
        compose.onNodeWithTag("boton-confirmar-otorgar").assertIsNotEnabled()
    }

    @Test
    fun `revocado ofrece otorgamiento nuevo y no renovacion`() {
        mostrar(consentimientos = listOf(consentimientoRevocado()))
        compose.onNodeWithTag("boton-otorgar-cotizacion").assertIsDisplayed()
        compose.onNodeWithTag("boton-renovar-cotizacion").assertDoesNotExist()
    }

    // ── Revocar ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `cancelar revocar no envia nada`() {
        mostrar(consentimientos = listOf(consentimientoVigente()))
        compose.onNodeWithTag("boton-revocar-cotizacion").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("boton-cancelar-revocar").assertHeightIsAtLeast(48.dp).performClick()

        compose.onNodeWithTag("boton-confirmar-revocar").assertDoesNotExist()
        assertEquals(0, api.llamadas.count { it.startsWith("revocar") })
    }

    @Test
    fun `confirmar revocar muestra exito solo tras la respuesta`() {
        mostrar(consentimientos = listOf(consentimientoVigente()))
        val respuesta = CompletableDeferred<Consentimiento>()
        api.revocar = { respuesta.await() }
        compose.onNodeWithTag("boton-revocar-cotizacion").performClick()
        compose.onNodeWithTag("boton-confirmar-revocar").assertHeightIsAtLeast(48.dp).performClick()

        compose.onNodeWithTag("mensaje-exito").assertDoesNotExist()
        compose.onNodeWithTag("boton-confirmar-revocar").assertIsNotEnabled()

        val revocado = consentimientoRevocado(id = "CON-1")
        api.consentimientos = { listOf(revocado) }
        compose.runOnIdle { respuesta.complete(revocado) }
        compose.onNodeWithTag("mensaje-exito").assertIsDisplayed()
        compose.onNodeWithTag("boton-otorgar-cotizacion").assertIsDisplayed()
        assertEquals(1, api.llamadas.count { it.startsWith("revocar") })
    }

    @Test
    fun `revocar cotizacion advierte la tarifa estandar`() {
        mostrar(consentimientos = listOf(consentimientoVigente()))
        compose.onNodeWithTag("boton-revocar-cotizacion").performClick()
        compose.onNodeWithTag("advertencia-cotizacion").assertIsDisplayed()
    }

    @Test
    fun `revocar otra finalidad no advierte la tarifa`() {
        mostrar(consentimientos = listOf(consentimientoVigente(id = "CON-S", finalidad = CodigoFinalidad.SUSCRIPCION)))
        compose.onNodeWithTag("boton-revocar-suscripcion").performClick()
        compose.onNodeWithTag("advertencia-cotizacion").assertDoesNotExist()
    }

    @Test
    fun `revocar sin conexion pide actualizar la lista antes de repetir`() {
        mostrar(consentimientos = listOf(consentimientoVigente()))
        api.revocar = { throw IOException("sin red") }
        compose.onNodeWithTag("boton-revocar-cotizacion").performClick()
        compose.onNodeWithTag("boton-confirmar-revocar").performClick()

        compose.onNodeWithTag("mensaje-error").assertIsDisplayed()
        compose.onNodeWithTag("boton-revocar-cotizacion").assertIsNotEnabled()
        compose.onNodeWithTag("mensaje-error-accion").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("boton-revocar-cotizacion").assertIsEnabled()
        assertEquals(1, api.llamadas.count { it.startsWith("revocar") })
    }

    @Test
    fun `401 se delega al flujo de sesion`() {
        mostrar(consentimientos = listOf(consentimientoVigente()))
        api.revocar = { throw fallaBff(401, "no_autenticado") }
        compose.onNodeWithTag("boton-revocar-cotizacion").performClick()
        compose.onNodeWithTag("boton-confirmar-revocar").performClick()
        compose.waitForIdle()
        assertEquals(1, sesionDelegada)
    }

    // ── Vencimiento y renovación ──────────────────────────────────────────────────────────────

    @Test
    fun `por vencer muestra la marca con dias`() {
        mostrar(consentimientos = listOf(consentimientoVigenteRenovable(diasParaVencer = 15)))
        compose.onNodeWithTag("por-vencer-cotizacion").assertIsDisplayed()
        compose.onNodeWithText("Vence en 15 días").assertIsDisplayed()
        compose.onNodeWithContentDescription("Próximo a vencer").assertExists()
    }

    @Test
    fun `por vencer con 0 dias muestra vence hoy y permite renovar`() {
        mostrar(consentimientos = listOf(consentimientoVigenteRenovable(diasParaVencer = 0)))
        compose.onNodeWithText("Vence hoy").assertIsDisplayed()
        compose.onNodeWithTag("boton-renovar-cotizacion").assertHeightIsAtLeast(48.dp).assertIsEnabled()
    }

    @Test
    fun `vigente que no esta por vencer no ofrece renovar`() {
        mostrar(consentimientos = listOf(consentimientoVigente()))
        compose.onNodeWithTag("por-vencer-cotizacion").assertDoesNotExist()
        compose.onNodeWithTag("boton-renovar-cotizacion").assertDoesNotExist()
    }

    @Test
    fun `renovar vencido muestra la nueva vence_en del servidor`() {
        mostrar(consentimientos = listOf(consentimientoVencido()))
        val renovado = consentimientoVigente(id = "CON-3").copy(venceEn = "2027-01-15T10:00:00Z")
        api.renovarC = { renovado }
        api.consentimientos = { listOf(renovado) }
        compose.onNodeWithTag("boton-renovar-cotizacion").performClick()

        compose.onNodeWithTag("nueva-vence-en").assertIsDisplayed()
        compose.onNodeWithText("Nueva fecha de vencimiento: 2027-01-15").assertIsDisplayed()
        compose.onNodeWithTag("boton-renovar-cotizacion").assertDoesNotExist()
    }
}
