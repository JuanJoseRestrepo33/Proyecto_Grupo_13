package co.solventa.mobile.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.core.network.NetworkClient
import co.solventa.feature.consentimiento.DependenciasConsentimiento
import co.solventa.feature.siniestros.DependenciasSiniestros
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.DebugMockInterceptor
import co.solventa.mobile.mock.MockBffMovil
import java.time.Instant
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import co.solventa.core.network.Credenciales
import co.solventa.mobile.mock.CONTRASENA_DEMO
import co.solventa.mobile.mock.CORREO_DEMO
import co.solventa.mobile.mock.SINIESTRO_PAGADO
import co.solventa.mobile.mock.SINIESTRO_REQUIERE_INFORMACION
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es")
class SolventaAppTest {
    @get:Rule
    val compose = createComposeRule()

    private val labels = listOf("Inicio", "Pólizas", "Avisos", "Perfil")

    @Test
    fun `los cuatro destinos cambian pantalla y seleccion y permiten volver a inicio`() {
        compose.setContent { SolventaTheme { SolventaApp() } }
        compose.onAllNodes(isSelectable()).assertCountEquals(4)
        assertDestination(0)
        listOf(1, 2, 3, 0).forEach { index ->
            compose.onNode(tab(labels[index])).assertIsDisplayed().performClick()
            assertDestination(index)
        }
    }

    @Test
    fun `reseleccionar un destino conserva su pantalla y seleccion unica`() {
        compose.setContent { SolventaTheme { SolventaApp() } }
        compose.onNode(tab("Pólizas")).performClick()
        repeat(2) { compose.onNode(tab("Pólizas")).performClick() }
        assertDestination(1)
    }

    @Test
    fun `sin identidad verificada inicio avisa que algunas funciones esperan la verificacion`() {
        compose.setContent { SolventaTheme { SolventaApp(identidadVerificada = false) } }
        compose.onNodeWithTag("aviso-identidad").assertIsDisplayed()
        compose.onNode(tab("Perfil")).performClick()
        compose.onNodeWithTag("aviso-identidad").assertDoesNotExist()
    }

    @Test
    fun `con identidad verificada no hay aviso`() {
        compose.setContent { SolventaTheme { SolventaApp(identidadVerificada = true) } }
        compose.onNodeWithTag("aviso-identidad").assertDoesNotExist()
    }

    @Test
    fun `perfil abre consentimiento y volver regresa a perfil con perfil seleccionado`() {
        val api = NetworkClient.create(
            BuildConfig.BASE_URL,
            listOf(DebugMockInterceptor(MockBffMovil { Instant.parse("2026-10-17T10:00:00Z") })),
            tokenAcceso = { null },
        )
        val dependencias = DependenciasConsentimiento(api)
        compose.setContent { SolventaTheme { SolventaApp(dependenciasConsentimiento = dependencias) } }
        compose.onNode(tab("Perfil")).performClick()
        compose.onNodeWithText("Esta sección estará disponible próximamente.").assertDoesNotExist()
        compose.onNodeWithTag("ir-consentimiento").assertHeightIsAtLeast(48.dp).performClick()

        compose.onNodeWithTag("pantalla-consentimiento").assertIsDisplayed()
        compose.onNode(tab("Perfil")).assertIsSelected()

        compose.onNodeWithContentDescription("Volver a Perfil").performClick()
        compose.onNodeWithTag("screen-profile").assertIsDisplayed()
        compose.onNodeWithTag("pantalla-consentimiento").assertDoesNotExist()
    }

    @Test
    fun `si la huella se desactivo al rotar el token inicio lo avisa una sola vez`() {
        var atendido = 0
        var aviso by mutableStateOf(true)
        compose.setContent {
            SolventaTheme {
                SolventaApp(avisoBiometriaDesactivada = aviso, onAvisoAtendido = { atendido++; aviso = false })
            }
        }
        compose.onNodeWithText("No pudimos guardar de forma segura tu ingreso con huella", substring = true)
            .assertIsDisplayed()
        assertEquals("sigue visible hasta que el cliente lo cierre", 0, atendido)
        compose.onNodeWithText("Entendido").assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        assertEquals(1, atendido)
        compose.onNodeWithText("No pudimos guardar", substring = true).assertDoesNotExist()
    }

    @Test
    fun `sin dependencias de consentimiento perfil no ofrece el acceso`() {
        compose.setContent { SolventaTheme { SolventaApp() } }
        compose.onNode(tab("Perfil")).performClick()
        compose.onNodeWithTag("ir-consentimiento").assertDoesNotExist()
    }

    @Test
    fun `inicio abre el reporte de siniestro con inicio seleccionado y un 401 va al flujo de sesion`() {
        val api = NetworkClient.create(
            BuildConfig.BASE_URL,
            listOf(DebugMockInterceptor(MockBffMovil { Instant.parse("2026-10-26T15:00:00Z") })),
            tokenAcceso = { null },
        )
        var sesionVencida = false
        val dependencias = DependenciasSiniestros(api, enviadorDePrueba(api) { null })
        compose.setContent {
            SolventaTheme {
                SolventaApp(dependenciasSiniestros = dependencias, onSesionVencida = { sesionVencida = true })
            }
        }
        compose.onNodeWithText("Esta sección estará disponible próximamente.").assertDoesNotExist()
        compose.onNodeWithTag("ir-reportar-siniestro").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Reportar siniestro").assertIsDisplayed()
        compose.onNode(tab("Inicio")).assertIsSelected()
        // Sin sesión, la consulta de pólizas responde 401.
        compose.waitUntil(timeoutMillis = 5_000) { sesionVencida }
    }

    // ── Seguimiento (WEB-F06) ────────────────────────────────────────────────────────────────

    /** La app con el mock y la sesión de la cuenta demo, que tiene un caso en cada estado. */
    private fun conSeguimiento(): NavHostController {
        var token: String? = null
        val api = NetworkClient.create(
            BuildConfig.BASE_URL,
            listOf(DebugMockInterceptor(MockBffMovil { Instant.parse("2026-10-26T15:00:00Z") })),
            tokenAcceso = { token },
        )
        token = runBlocking { api.iniciarSesion(Credenciales(CORREO_DEMO, CONTRASENA_DEMO)).tokenAcceso }
        val dependencias = DependenciasSiniestros(api, enviadorDePrueba(api) { "CLI-2026-000001" })
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            SolventaTheme { SolventaApp(dependenciasSiniestros = dependencias, navController = nav) }
        }
        return nav
    }

    private fun esperar(tag: String) = compose.waitUntil(timeoutMillis = 5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `inicio abre mis siniestros y la tarjeta abre el detalle de ese id con inicio seleccionado`() {
        conSeguimiento()
        compose.onNodeWithTag("ir-mis-siniestros").assertHeightIsAtLeast(48.dp).performClick()
        esperar("siniestro-$SINIESTRO_REQUIERE_INFORMACION")
        compose.onNode(tab("Inicio")).assertIsSelected()

        compose.onNodeWithTag("siniestro-$SINIESTRO_REQUIERE_INFORMACION").performScrollTo().performClick()

        esperar("encabezado")
        compose.onNodeWithText("Referencia SIN-2026-000903", substring = true).assertExists()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithTag("pantalla-mis-siniestros").assertIsDisplayed()
    }

    @Test
    fun `reportar desde mis siniestros abre el asistente y nunca el detalle`() {
        val nav = conSeguimiento()
        compose.onNodeWithTag("ir-mis-siniestros").performClick()
        esperar("seguimiento-reportar")

        compose.onNodeWithTag("seguimiento-reportar").performClick()

        compose.waitForIdle()
        assertEquals(RUTA_REPORTE_SINIESTRO, nav.currentDestination?.route)
        compose.onNodeWithTag("pantalla-detalle-siniestro").assertDoesNotExist()
    }

    @Test
    fun `la ruta del detalle abre el caso de ese id aunque se llegue directo`() {
        val nav = conSeguimiento()
        compose.runOnUiThread { nav.navigate(rutaDetalleSiniestro(SINIESTRO_PAGADO)) }

        esperar("encabezado")
        assertEquals(RUTA_DETALLE_SINIESTRO, nav.currentDestination?.route)
        compose.onNodeWithText("Referencia SIN-2026-000906", substring = true).assertExists()
        // Sin la lista en la pila, volver la abre en lugar del detalle.
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.waitForIdle()
        assertEquals(RUTA_MIS_SINIESTROS, nav.currentDestination?.route)
    }

    @Test
    fun `sin dependencias de siniestros inicio no ofrece el reporte`() {
        compose.setContent { SolventaTheme { SolventaApp() } }
        compose.onNodeWithTag("ir-reportar-siniestro").assertDoesNotExist()
        compose.onNodeWithTag("ir-mis-siniestros").assertDoesNotExist()
    }

    private fun tab(label: String): SemanticsMatcher = isSelectable() and hasText(label)

    private fun assertDestination(selectedIndex: Int) {
        compose.waitForIdle()
        val tag = "screen-${TopLevelDestination.entries[selectedIndex].route}"
        compose.onNodeWithTag(tag).assertIsDisplayed()
        compose.onNode(hasText(labels[selectedIndex]) and hasAnyAncestor(hasTestTag(tag))).assertIsDisplayed()
        compose.onNode(
            hasText("Esta sección estará disponible próximamente.") and hasAnyAncestor(hasTestTag(tag)),
        ).assertIsDisplayed()
        labels.forEachIndexed { index, label ->
            val node = compose.onNode(tab(label)).assertIsDisplayed()
            if (index == selectedIndex) node.assertIsSelected() else node.assertIsNotSelected()
        }
    }
}
