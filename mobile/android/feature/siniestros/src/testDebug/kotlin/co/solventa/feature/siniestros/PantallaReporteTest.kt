package co.solventa.feature.siniestros

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.core.network.ErrorBff
import co.solventa.core.network.ErrorCampo
import co.solventa.feature.siniestros.reporte.Campo
import co.solventa.feature.siniestros.reporte.PantallaReporte
import co.solventa.feature.siniestros.reporte.ReporteViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es-w411dp-h914dp")
class PantallaReporteTest {
    @get:Rule(order = 0)
    val main = ReglaMain()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val api = BffFalso()
    private val claves = Claves()
    private var termino = false
    private var sesionVencida = false
    private lateinit var viewModel: ReporteViewModel

    private fun mostrar() {
        viewModel = ReporteViewModel(api, hoy = { HOY }, nuevaClave = claves::nueva)
        compose.setContent {
            SolventaTheme { PantallaReporte(viewModel, { termino = true }, { sesionVencida = true }) }
        }
    }

    private fun tocar(texto: String) = compose.onNodeWithText(texto).performScrollTo().performClick()

    private fun continuar() = compose.onNodeWithTag("continuar").performScrollTo().performClick()

    /** Paso 1 y 2 completos; la fecha se fija en el ViewModel porque el calendario es del sistema. */
    private fun hastaRevision() {
        mostrar()
        compose.onNodeWithTag("poliza-${POLIZA_VIGENTE.numero}").performClick()
        continuar()
        tocar("Hurto")
        compose.runOnIdle { viewModel.actualizar(Campo.FECHA) { it.copy(fecha = HOY.minusDays(3)) } }
        compose.onNodeWithTag("campo-descripcion").performTextInput("Me robaron el celular en el bus")
        compose.onNodeWithTag("campo-monto").performTextInput("1.500.000")
        continuar()
        compose.onNodeWithText("Paso 3 de 4 · Evidencia").assertIsDisplayed()
        continuar()
    }

    @Test
    fun `el asistente recorre los cuatro pasos y confirma con la referencia y el siguiente paso`() {
        hastaRevision()
        compose.onNodeWithText("Paso 4 de 4 · Revisión").assertIsDisplayed()
        compose.onNodeWithText("Seguro de auto · POL-2026-000101").assertIsDisplayed()
        compose.onNodeWithText("23 de octubre de 2026").assertIsDisplayed()
        compose.onNodeWithText("$ 1.500.000").assertIsDisplayed()
        compose.onNodeWithTag("enviar").performScrollTo().performClick()

        compose.onNodeWithText("Reporte enviado").assertIsDisplayed()
        compose.onNodeWithTag("referencia").assertIsDisplayed()
        compose.onNodeWithText("SIN-2026-000123").assertIsDisplayed()
        compose.onNodeWithText("Estado: Reportado").assertIsDisplayed()
        compose.onNodeWithTag("siguiente-paso").assertIsDisplayed()
        tocar("Volver al inicio")
        assertTrue(termino)
    }

    @Test
    fun `la lista de polizas muestra producto, numero, vigencia y estado`() {
        mostrar()
        compose.onNodeWithText("Paso 1 de 4 · Póliza").assertIsDisplayed()
        compose.onNodeWithText("Seguro de hogar").assertIsDisplayed()
        compose.onNodeWithText("POL-2026-000102").assertIsDisplayed()
        compose.onNodeWithText("Vigencia: 26 de septiembre de 2025 al 26 de septiembre de 2026").assertIsDisplayed()
        compose.onNodeWithText("Vencida").assertIsDisplayed()
        compose.onNodeWithTag("poliza-${POLIZA_VENCIDA.numero}").performClick().assertIsSelected()
    }

    @Test
    fun `sin elegir poliza no avanza e indica que falta`() {
        mostrar()
        continuar()
        compose.onNodeWithText("Paso 1 de 4 · Póliza").assertIsDisplayed()
        compose.onNodeWithText("Elige la póliza afectada.").assertIsDisplayed()
    }

    @Test
    fun `sin datos en la informacion indica que falta en cada campo`() {
        mostrar()
        compose.onNodeWithTag("poliza-${POLIZA_VIGENTE.numero}").performClick()
        continuar()
        continuar()
        compose.onNodeWithText("Elige qué tipo de evento ocurrió.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Indica la fecha del evento.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Describe qué pasó.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `una cuenta sin polizas explica que necesita una y no muestra el formulario`() {
        api.polizas = { emptyList() }
        mostrar()
        compose.onNodeWithTag("sin-polizas").assertIsDisplayed()
        compose.onNodeWithTag("continuar").assertDoesNotExist()
        tocar("Volver al inicio")
        assertTrue(termino)
    }

    @Test
    fun `fecha futura del BFF muestra el error sobre la fecha`() {
        api.reporte = { _, _ ->
            throw fallaBff(422, "fecha_futura", listOf(ErrorCampo("ocurrido_el", "fecha_futura")))
        }
        hastaRevision()
        compose.onNodeWithTag("enviar").performScrollTo().performClick()
        compose.onNodeWithText("Paso 2 de 4 · Información").assertIsDisplayed()
        compose.onNodeWithText("La fecha del evento no puede ser posterior a hoy.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `un evento fuera de vigencia explica las fechas de la poliza sin confirmar`() {
        api.reporte = { _, _ ->
            throw fallaBff(
                409,
                ErrorBff("evento_fuera_de_vigencia", "x", vigenteDesde = "2025-09-26", vigenteHasta = "2026-09-26"),
            )
        }
        hastaRevision()
        compose.onNodeWithTag("enviar").performScrollTo().performClick()
        compose.onNodeWithText(
            "El evento ocurrió fuera de la vigencia de la póliza, que cubre del 26 de septiembre de 2025 al " +
                "26 de septiembre de 2026. El reporte no se registró: revisa la fecha o elige otra póliza.",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reporte enviado").assertDoesNotExist()
    }

    @Test
    fun `mientras se envia el boton queda ocupado y un segundo toque no reenvia`() {
        val respuesta = CompletableDeferred<Unit>()
        api.reporte = { _, solicitud ->
            respuesta.await()
            siniestroDe(solicitud)
        }
        hastaRevision()
        compose.onNodeWithTag("enviar").performScrollTo().performClick()
        compose.onNodeWithTag("enviar").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Cargando").assertIsDisplayed()
        compose.onNodeWithTag("enviar").performClick()
        compose.runOnIdle { respuesta.complete(Unit) }
        compose.onNodeWithText("SIN-2026-000123").assertIsDisplayed()
        assertEquals(1, api.reportes.size)
    }

    @Test
    fun `sin red se explica que reintentar no duplica y reintenta con la misma clave`() {
        api.reporte = { _, _ -> throw java.io.IOException("sin red") }
        hastaRevision()
        compose.onNodeWithTag("enviar").performScrollTo().performClick()
        compose.onNodeWithText(
            "No pudimos confirmar el envío por falta de conexión. Toca «Enviar reporte» para reintentar con los " +
                "mismos datos: no se creará un reporte duplicado.",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Cambiar póliza").assertIsNotEnabled()
        api.reporte = { _, solicitud -> siniestroDe(solicitud) }
        compose.onNodeWithTag("enviar").performScrollTo().performClick()
        compose.onNodeWithText("SIN-2026-000123").assertIsDisplayed()
        assertEquals(listOf("clave-1", "clave-1"), api.reportes.map { it.first })
    }

    @Test
    fun `desde la revision se puede volver a cada paso`() {
        hastaRevision()
        compose.onNodeWithContentDescription("Cambiar póliza").performScrollTo().performClick()
        compose.onNodeWithText("Paso 1 de 4 · Póliza").assertIsDisplayed()
        continuar()
        continuar()
        continuar()
        compose.onNodeWithContentDescription("Cambiar descripción").performScrollTo().performClick()
        compose.onNodeWithText("Paso 2 de 4 · Información").assertIsDisplayed()
    }

    @Test
    fun `el calendario se abre desde la fecha y no acepta sin elegir un dia`() {
        mostrar()
        compose.onNodeWithTag("poliza-${POLIZA_VIGENTE.numero}").performClick()
        continuar()
        compose.onNodeWithContentDescription("Elegir la fecha del evento").performScrollTo().performClick()
        compose.onNodeWithTag("aceptar-fecha").assertIsNotEnabled()
        compose.onNodeWithText("Cancelar").performClick()
        compose.onNodeWithTag("aceptar-fecha").assertDoesNotExist()
    }

    @Test
    fun `un 401 se delega al flujo de sesion`() {
        api.polizas = { throw fallaBff(401, "sesion_no_vigente") }
        mostrar()
        compose.waitForIdle()
        assertTrue(sesionVencida)
    }

    @Test
    fun `los controles miden al menos 48 dp y tienen descripcion para TalkBack`() {
        hastaRevision()
        compose.onNodeWithTag("enviar").assertHeightIsAtLeast(48.dp)
        val cambiar = compose.onAllNodesWithContentDescription("Cambiar", substring = true).fetchSemanticsNodes()
        assertEquals(6, cambiar.size)
        cambiar.forEach { assertTrue(it.size.height >= with(compose.density) { 48.dp.roundToPx() }) }
        compose.onNodeWithContentDescription("Volver").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Cambiar póliza").performClick()
        compose.onNodeWithTag("poliza-${POLIZA_VIGENTE.numero}").assertHeightIsAtLeast(48.dp)
        continuar()
        compose.onNodeWithTag("tipo-hurto").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("abrir-fecha").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Elegir la fecha del evento").assert(hasClickAction())
    }
}
