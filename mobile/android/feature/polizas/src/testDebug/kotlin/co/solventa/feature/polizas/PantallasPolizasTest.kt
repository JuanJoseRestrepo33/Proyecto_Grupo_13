package co.solventa.feature.polizas

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.core.network.EstadoPoliza
import co.solventa.feature.polizas.comun.FalloConsulta
import co.solventa.feature.polizas.comun.Pestana
import co.solventa.feature.polizas.detalle.AvisoPoliza
import co.solventa.feature.polizas.detalle.EstadoDetallePoliza
import co.solventa.feature.polizas.detalle.PantallaDetallePoliza
import co.solventa.feature.polizas.lista.EstadoMisPolizas
import co.solventa.feature.polizas.lista.PantallaMisPolizas
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es-w411dp-h914dp")
class PantallasPolizasTest {
    @get:Rule
    val compose = createComposeRule()

    private var abierta: String? = null
    private var pestana: Pestana? = null
    private var actualizar = 0
    private var cotizar = 0
    private var volvio = false
    private var descargar = 0
    private var reintentarConsulta = 0
    private var avisoMostrado = 0

    private fun lista(estado: EstadoMisPolizas) {
        compose.setContent {
            SolventaTheme {
                PantallaMisPolizas(
                    estado = estado,
                    onActualizar = { actualizar += 1 },
                    onElegir = { pestana = it },
                    onAbrir = { abierta = it },
                    onCotizar = { cotizar += 1 },
                )
            }
        }
    }

    private fun detalle(estado: EstadoDetallePoliza) {
        compose.setContent {
            SolventaTheme {
                PantallaDetallePoliza(
                    estado = estado,
                    onVolver = { volvio = true },
                    onReintentar = { reintentarConsulta += 1 },
                    onDescargar = { descargar += 1 },
                    onAvisoMostrado = { avisoMostrado += 1 },
                )
            }
        }
    }

    private fun conTalkBack(texto: String) = hasContentDescription(texto, substring = true)

    // ── Mis pólizas ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `lista con datos muestra producto, numero, vigencia, prima y estado y la tarjeta abre el detalle`() {
        lista(EstadoMisPolizas(polizas = listOf(VIGENTE, VENCIDA, POR_INICIAR), cargando = false))

        compose.onNodeWithTag("pestana-activas").assertIsSelected().assertHeightIsAtLeast(48.dp)
        val tarjeta = compose.onNodeWithTag("poliza-$NUMERO").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        // TalkBack lee la tarjeta completa, con el estado en texto (no solo el color).
        compose.onNode(
            hasClickAction() and conTalkBack("Seguro de Auto — Plan Full. Estado: Vigente. Póliza $NUMERO."),
        ).assertIsDisplayed()
        compose.onNode(conTalkBack("Vigencia del 15 oct. 2026 al 14 oct. 2027. Prima de $185.000 al mes"))
            .assertExists()
        // Activas no incluye la vencida ni la por iniciar.
        compose.onNodeWithTag("poliza-POL-2026-001102").assertDoesNotExist()
        compose.onNodeWithTag("poliza-POL-2026-001103").assertDoesNotExist()

        tarjeta.performClick()
        assertEquals(NUMERO, abierta)
    }

    @Test
    fun `la tarjeta muestra los textos visibles y la insignia con su texto`() {
        lista(EstadoMisPolizas(polizas = listOf(VIGENTE), cargando = false))
        // Sin el árbol fusionado: los textos que ve el cliente.
        compose.onNodeWithText("Seguro de Auto — Plan Full", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(NUMERO, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Vigente", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("15 oct. 2026 – 14 oct. 2027", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("$185.000 / mes", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `en Todas aparecen los tres estados y a una poliza sin datos le dice nombre generico y prima no disponible`() {
        lista(
            EstadoMisPolizas(
                polizas = listOf(VIGENTE, VENCIDA, POR_INICIAR),
                cargando = false,
                pestana = Pestana.TODAS,
            ),
        )

        compose.onNode(conTalkBack("Seguro de hogar. Estado: Vencida.")).assertExists()
        compose.onNode(conTalkBack("Vigencia del 1 ene. 2025 al 31 dic. 2025. Prima: No disponible.")).assertExists()
        compose.onNode(conTalkBack("Seguro de viaje. Estado: Por iniciar.")).assertExists()
        compose.onAllNodesWithText("Prima: No disponible", useUnmergedTree = true).assertCountEquals(2)
        compose.onNode(conTalkBack("null")).assertDoesNotExist()
    }

    @Test
    fun `cambiar de pestana avisa la elegida`() {
        lista(EstadoMisPolizas(polizas = listOf(VIGENTE, VENCIDA), cargando = false))
        compose.onNodeWithTag("pestana-vencidas").performClick()
        assertEquals(Pestana.VENCIDAS, pestana)
    }

    @Test
    fun `una pestana sin polizas lo dice`() {
        lista(EstadoMisPolizas(polizas = listOf(VENCIDA), cargando = false))

        compose.onNodeWithTag("pestana-vacia").assertIsDisplayed()
        compose.onNodeWithText("No tienes pólizas en esta pestaña.").assertIsDisplayed()
        compose.onNodeWithTag("sin-polizas").assertDoesNotExist()
    }

    @Test
    fun `sin polizas ofrece cotizar un seguro`() {
        lista(EstadoMisPolizas(polizas = emptyList(), cargando = false))

        compose.onNodeWithText("Aún no tienes pólizas").assertIsDisplayed()
        compose.onNodeWithTag("pestana-activas").assertDoesNotExist()
        compose.onNodeWithTag("cotizar").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, cotizar)
    }

    @Test
    fun `un error de carga ofrece reintentar`() {
        lista(EstadoMisPolizas(polizas = null, cargando = false, error = FalloConsulta.GENERICO))

        compose.onNodeWithText("No pudimos cargar tus pólizas. Intenta de nuevo.").assertIsDisplayed()
        compose.onNodeWithTag("reintentar").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, actualizar)
    }

    @Test
    fun `si el refresco falla se conserva la lista con el aviso`() {
        lista(EstadoMisPolizas(polizas = listOf(VIGENTE), cargando = false, error = FalloConsulta.SIN_RED))

        compose.onNodeWithText("No tienes conexión", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("poliza-$NUMERO").assertExists()
    }

    @Test
    fun `cargando muestra el indicador y actualizar tiene descripcion y 48 dp`() {
        lista(EstadoMisPolizas())
        compose.onNodeWithTag("cargando").assertIsDisplayed()
        compose.onNodeWithContentDescription("Actualizar").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, actualizar)
    }

    @Test
    fun `mientras actualiza con una lista el boton queda deshabilitado y la lista sigue visible`() {
        lista(EstadoMisPolizas(polizas = listOf(VIGENTE), cargando = true))
        compose.onNodeWithTag("actualizar").assertIsNotEnabled()
        compose.onNodeWithTag("poliza-$NUMERO").assertIsDisplayed()
    }

    // ── Detalle ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `detalle completo con informacion general, financiera, coberturas y descarga`() {
        detalle(EstadoDetallePoliza(detalle = COMPLETA, cargando = false))

        compose.onNodeWithText("Seguro de Auto — Plan Full").assertIsDisplayed()
        compose.onNode(conTalkBack("Estado: Vigente")).assertExists()
        compose.onNode(hasTestTag("fila-vigencia") and conTalkBack("Vigencia: 15 oct. 2026 – 14 oct. 2027"))
            .assertExists()
        compose.onNode(hasTestTag("fila-prima") and conTalkBack("Prima: $185.000 / mes")).assertExists()
        compose.onNode(hasTestTag("fila-emitida") and conTalkBack("Emitida:")).assertExists()
        compose.onNode(hasTestTag("fila-valor-asegurado") and conTalkBack("$60.000.000")).assertExists()
        compose.onNode(hasTestTag("fila-deducible") and conTalkBack("5% del valor asegurado")).assertExists()
        compose.onNode(conTalkBack("Responsabilidad civil: $1.000.000.000")).assertExists()
        compose.onNode(conTalkBack("Asistencia en viaje: Incluida")).assertExists()
        compose.onNodeWithTag("sin-documento").assertDoesNotExist()

        compose.onNodeWithTag("descargar-pdf").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, descargar)
    }

    @Test
    fun `detalle con datos faltantes y sin documento dice no disponible y no ofrece la descarga`() {
        detalle(EstadoDetallePoliza(detalle = SIN_DATOS, cargando = false))

        compose.onNodeWithText("Seguro de hogar").assertIsDisplayed()
        compose.onNode(conTalkBack("Estado: Vencida")).assertExists()
        compose.onNode(hasTestTag("fila-prima") and conTalkBack("Prima: No disponible")).assertExists()
        compose.onNode(hasTestTag("fila-valor-asegurado") and conTalkBack("Valor asegurado: No disponible"))
            .assertExists()
        compose.onNode(hasTestTag("fila-deducible") and conTalkBack("Deducible: No disponible")).assertExists()
        compose.onNodeWithTag("fila-emitida").assertDoesNotExist()
        compose.onNodeWithText("Esta póliza no tiene coberturas registradas.").assertExists()
        compose.onNodeWithTag("descargar-pdf").assertDoesNotExist()
        compose.onNodeWithTag("sin-documento").performScrollTo().assertIsDisplayed()
        compose.onNode(conTalkBack("El documento de esta póliza no está disponible.")).assertExists()
    }

    @Test
    fun `la poliza por iniciar muestra su insignia con texto`() {
        detalle(EstadoDetallePoliza(detalle = SIN_DATOS.copy(estado = EstadoPoliza.POR_INICIAR), cargando = false))
        compose.onNode(conTalkBack("Estado: Por iniciar")).assertExists()
        compose.onNodeWithTag("insignia-por_iniciar", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `mientras descarga el boton muestra el indicador y no se puede tocar`() {
        detalle(EstadoDetallePoliza(detalle = COMPLETA, cargando = false, descargando = true))
        compose.onNodeWithTag("descargar-pdf").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Descargando…", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `no encontrada permite volver a la lista`() {
        detalle(EstadoDetallePoliza(cargando = false, noEncontrada = true))

        compose.onNodeWithText("No encontramos esta póliza.").assertIsDisplayed()
        compose.onNodeWithTag("volver-lista").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(true, volvio)
    }

    @Test
    fun `un error al cargar el detalle ofrece reintentar y la flecha vuelve a la lista`() {
        detalle(EstadoDetallePoliza(cargando = false, error = FalloConsulta.SIN_RED))

        compose.onNodeWithText("No tienes conexión", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("reintentar").performClick()
        assertEquals(1, reintentarConsulta)
        compose.onNodeWithContentDescription("Volver").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(true, volvio)
    }

    @Test
    fun `sin red al descargar el aviso ofrece reintentar la descarga`() {
        detalle(EstadoDetallePoliza(detalle = COMPLETA, cargando = false, aviso = AvisoPoliza.DESCARGA_SIN_RED))

        compose.onNodeWithText("No pudimos descargar el PDF: no tienes conexión.").assertIsDisplayed()
        compose.onNodeWithText("Reintentar").performClick()
        compose.waitForIdle()
        assertEquals(1, descargar)
    }

    @Test
    fun `sin visor de PDF se avisa`() {
        detalle(EstadoDetallePoliza(detalle = COMPLETA, cargando = false, aviso = AvisoPoliza.SIN_VISOR))
        compose.onNodeWithText("No hay una app para abrir el PDF.").assertIsDisplayed()
        compose.onAllNodesWithTag("reintentar").fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }
}
