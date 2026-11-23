package co.solventa.feature.siniestros.seguimiento

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.core.network.EstadoSiniestro
import co.solventa.feature.siniestros.BffFalso
import co.solventa.feature.siniestros.ReglaMain
import co.solventa.feature.siniestros.fallaBff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es-w411dp-h914dp")
class PantallasSeguimientoTest {
    @get:Rule(order = 0)
    val main = ReglaMain()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val api = BffFalso()
    private var abierto: String? = null
    private var reportar = 0
    private var volvio = false

    private fun lista(estado: EstadoMisSiniestros, bloqueado: Boolean = false, onActualizar: () -> Unit = {}) {
        compose.setContent {
            SolventaTheme {
                PantallaMisSiniestros(
                    estado = estado,
                    reporteBloqueado = bloqueado,
                    onActualizar = onActualizar,
                    onAbrir = { abierto = it },
                    onReportar = { reportar += 1 },
                    onVolver = { volvio = true },
                )
            }
        }
    }

    private fun mostrarDetalle(caso: co.solventa.core.network.SiniestroDetalle?): DetalleSiniestroViewModel {
        api.detalle = { caso ?: throw fallaBff(404, "siniestro_no_encontrado") }
        val vm = DetalleSiniestroViewModel(api, SavedStateHandle(mapOf("id" to ID_CASO)))
        compose.setContent { SolventaTheme { PantallaDetalleSiniestro(vm, onVolver = { volvio = true }) } }
        return vm
    }

    // ── Mis siniestros ───────────────────────────────────────────────────────────────────────

    @Test
    fun `lista con datos muestra tipo, estado, referencia, poliza, fecha y canal y la tarjeta abre el detalle`() {
        val caso = siniestro(estado = EstadoSiniestro.REQUIERE_INFORMACION, canal = "movil")
        lista(EstadoMisSiniestros(siniestros = listOf(caso), cargando = false))

        val tarjeta = compose.onNodeWithTag("siniestro-$ID_CASO")
        tarjeta.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        // TalkBack lee la tarjeta completa, con el estado en texto (no solo el color).
        compose.onNode(
            hasClickAction() and hasContentDescription("Daño en el hogar, Requiere información", substring = true),
        ).assertIsDisplayed()
        compose.onNode(hasContentDescription("SIN-2026-000042", substring = true)).assertIsDisplayed()
        compose.onNode(hasContentDescription("POL-2026-001101", substring = true)).assertIsDisplayed()
        compose.onNode(hasContentDescription("por App", substring = true)).assertIsDisplayed()

        compose.onNode(hasContentDescription("Abrir el seguimiento", substring = true)).performClick()
        assertEquals(ID_CASO, abierto)
    }

    @Test
    fun `lista vacia lleva a reportar`() {
        lista(EstadoMisSiniestros(siniestros = emptyList(), cargando = false))
        compose.onNodeWithTag("sin-siniestros").assertIsDisplayed()
        compose.onNodeWithText("No tienes siniestros reportados").assertIsDisplayed()
        compose.onNodeWithTag("seguimiento-reportar").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, reportar)
    }

    @Test
    fun `con un reporte por confirmar reportar queda bloqueado con el mensaje de inicio`() {
        lista(EstadoMisSiniestros(siniestros = emptyList(), cargando = false), bloqueado = true)
        compose.onNodeWithTag("seguimiento-reportar").assertIsNotEnabled()
        compose.onNodeWithTag("reporte-bloqueado").assertIsDisplayed()
        assertEquals(0, reportar)
    }

    @Test
    fun `error de carga ofrece reintentar y actualizar tiene descripcion`() {
        var actualizaciones = 0
        lista(EstadoMisSiniestros(cargando = false, error = FalloConsulta.GENERICO)) { actualizaciones += 1 }
        compose.onNodeWithText("No pudimos consultar tus siniestros", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("reintentar").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("Actualizar").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(2, actualizaciones)
    }

    // ── Detalle ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `detalle con requerimiento pendiente muestra historial, resumen y responder`() {
        mostrarDetalle(requiereInformacion())

        compose.onNodeWithTag("encabezado").assertIsDisplayed()
        compose.onNodeWithTag("insignia-estado", useUnmergedTree = true).assertExists()
        compose.onNode(hasContentDescription("Estado: Requiere información")).assertIsDisplayed()
        // Solo el último evento queda resaltado como estado actual.
        compose.onAllNodesWithTag("evento-actual").fetchSemanticsNodes().let { assertEquals(1, it.size) }
        compose.onNode(hasContentDescription("Estado actual", substring = true)).assertExists()
        compose.onNodeWithText("Falta la factura").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Responde los requerimientos pendientes.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("estado-requerimiento-$ID_REQ", useUnmergedTree = true).assertTextEquals("Pendiente")
        compose.onNodeWithTag("responder-$ID_REQ").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Responder: Factura de la reparación").assertExists()
    }

    @Test
    fun `responder abre la hoja con camara, galeria y documento`() {
        mostrarDetalle(requiereInformacion())
        compose.onNodeWithTag("responder-$ID_REQ").performScrollTo().performClick()
        compose.onNodeWithTag("hoja-respuesta").assertExists()
        listOf("tomar-foto", "galeria", "documento").forEach { compose.onNodeWithTag(it).assertExists() }
        compose.onNodeWithTag("enviar-respuesta").assertIsNotEnabled()
    }

    @Test
    fun `requerimiento atendido muestra su fecha y no ofrece responder`() {
        mostrarDetalle(requiereInformacion(atendido()))
        compose.onNodeWithText("Atendido el", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("responder-$ID_REQ").assertDoesNotExist()
    }

    @Test
    fun `requerimiento cerrado sin respuesta no ofrece responder`() {
        mostrarDetalle(requiereInformacion(cerradoSinRespuesta()))
        compose.onNodeWithText("Cerrado sin respuesta").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("responder-$ID_REQ").assertDoesNotExist()
    }

    @Test
    fun `sin monto ni cambios de estado lo dice en el resumen`() {
        mostrarDetalle(detalle(monto = null, actualizadoEn = null))
        compose.onNodeWithText("Sin monto estimado").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sin cambios desde el reporte").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `caso rechazado dice que esta cerrado y no pide esperar`() {
        mostrarDetalle(
            detalle(
                estado = EstadoSiniestro.RECHAZADO,
                siguiente = co.solventa.core.network.SiguientePaso.ESPERAR_EVALUACION,
            ),
        )
        compose.onNodeWithText("El caso está cerrado: fue rechazado.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Te avisaremos cuando cambie el estado.").assertDoesNotExist()
    }

    @Test
    fun `no encontrado ofrece volver a la lista`() {
        mostrarDetalle(null)
        compose.onNodeWithTag("siniestro-no-encontrado").assertIsDisplayed()
        compose.onNodeWithTag("volver-lista").assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(volvio)
    }

    @Test
    fun `los botones de icono tienen descripcion para talkback`() {
        mostrarDetalle(requiereInformacion())
        compose.onNodeWithContentDescription("Volver").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Actualizar").assertHeightIsAtLeast(48.dp)
    }
}
