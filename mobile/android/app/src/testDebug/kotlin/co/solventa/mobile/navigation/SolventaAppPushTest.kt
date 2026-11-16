package co.solventa.mobile.navigation

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.core.network.Credenciales
import co.solventa.core.network.NetworkClient
import co.solventa.feature.notificaciones.AlmacenPush
import co.solventa.feature.notificaciones.AperturaPush
import co.solventa.feature.notificaciones.AperturasPush
import co.solventa.feature.notificaciones.DependenciasNotificaciones
import co.solventa.feature.notificaciones.ReceptorPush
import co.solventa.feature.onboarding.sesion.GestorSesion
import co.solventa.feature.siniestros.DependenciasSiniestros
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.DebugMockInterceptor
import co.solventa.mobile.mock.CONTRASENA_DEMO
import co.solventa.mobile.mock.CORREO_DEMO
import co.solventa.mobile.mock.MockBffMovil
import co.solventa.mobile.mock.SINIESTRO_PAGADO
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MOB-F06: la notificación tocada abre el detalle y, sin permiso, Inicio explica la alternativa (P20). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es")
class SolventaAppPushTest {
    @get:Rule
    val compose = createComposeRule()

    private class AlmacenEnMemoria : AlmacenPush {
        var pedido = false
        override fun tokenInstalacion() = "instalacion-prueba"
        override fun vistas(cliente: String): Set<String>? = null
        override fun guardarVistas(cliente: String, ids: Set<String>) = Unit
        override fun permisoPedido() = pedido
        override fun marcarPermisoPedido() {
            pedido = true
        }
    }

    private val aperturas = AperturasPush()

    private fun abrir(conSiniestros: Boolean = true): NavHostController {
        var token: String? = null
        val api = NetworkClient.create(
            BuildConfig.BASE_URL,
            listOf(DebugMockInterceptor(MockBffMovil { Instant.parse("2026-10-26T15:00:00Z") })),
            tokenAcceso = { token },
        )
        token = runBlocking { api.iniciarSesion(Credenciales(CORREO_DEMO, CONTRASENA_DEMO)).tokenAcceso }
        val almacen = AlmacenEnMemoria()
        val notificaciones = DependenciasNotificaciones(
            receptor = ReceptorPush(
                api = api,
                almacen = almacen,
                notificador = { true },
                sesion = GestorSesion(),
                alcance = CoroutineScope(Dispatchers.Main),
            ),
            almacen = almacen,
            aperturas = aperturas,
        )
        val siniestros = DependenciasSiniestros(api, enviadorDePrueba(api) { "CLI-2026-000001" })
            .takeIf { conSiniestros }
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            SolventaTheme {
                SolventaApp(
                    dependenciasSiniestros = siniestros,
                    dependenciasNotificaciones = notificaciones,
                    navController = nav,
                )
            }
        }
        return nav
    }

    private fun esperar(tag: String) = compose.waitUntil(timeoutMillis = 5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `una notificacion tocada abre el detalle de su siniestro y se atiende`() {
        aperturas.recibir(AperturaPush("NOT-1", SINIESTRO_PAGADO))
        val nav = abrir()

        esperar("encabezado")
        assertEquals(RUTA_DETALLE_SINIESTRO, nav.currentDestination?.route)
        compose.onNodeWithText("Referencia SIN-2026-000906", substring = true).assertExists()
        assertNull(aperturas.pendiente)
    }

    @Test
    fun `una notificacion tocada con la app abierta tambien abre el detalle`() {
        val nav = abrir()
        compose.waitForIdle()

        compose.runOnUiThread { aperturas.recibir(AperturaPush("NOT-1", SINIESTRO_PAGADO)) }

        esperar("encabezado")
        assertEquals(RUTA_DETALLE_SINIESTRO, nav.currentDestination?.route)
    }

    @Test
    fun `una notificacion sin siniestro deja la app en inicio`() {
        aperturas.recibir(AperturaPush("NOT-1", null))
        val nav = abrir()

        compose.waitForIdle()
        assertEquals(TopLevelDestination.HOME.route, nav.currentDestination?.route)
        assertNull(aperturas.pendiente)
    }

    @Test
    fun `sin permiso inicio explica y ofrece ver mis siniestros`() {
        abrir()
        compose.onNodeWithTag("aviso-notificaciones").assertIsDisplayed()

        compose.onNodeWithTag("notificaciones-ahora-no").performScrollTo().performClick()
        compose.onNodeWithTag("aviso-ver-siniestros").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()

        esperar("pantalla-mis-siniestros")
    }

    @Test
    fun `sin siniestros el aviso no ofrece el seguimiento`() {
        abrir(conSiniestros = false)

        compose.onNodeWithTag("notificaciones-ahora-no").performScrollTo().performClick()

        compose.onNodeWithTag("aviso-notificaciones").assertIsDisplayed()
        compose.onNodeWithTag("aviso-ver-siniestros").assertDoesNotExist()
    }
}
