package co.solventa.feature.notificaciones

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import co.solventa.core.designsystem.SolventaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es-w411dp-h914dp")
class AvisoNotificacionesTest {
    @get:Rule
    val compose = createComposeRule()

    private val acciones = mutableListOf<String>()

    private fun dibujar(estado: EstadoPermiso, conSiniestros: Boolean = true) = compose.setContent {
        SolventaTheme {
            AvisoNotificaciones(
                estado = estado,
                onActivar = { acciones += "activar" },
                onAhoraNo = { acciones += "ahora no" },
                onVerSiniestros = if (conSiniestros) ({ acciones += "siniestros" }) else null,
            )
        }
    }

    @Test
    fun `sin pedir explica para que sirven antes de pedir el permiso`() {
        dibujar(EstadoPermiso.SIN_PEDIR)

        compose.onNodeWithText("Entérate cuando avance tu siniestro").assertIsDisplayed()
        compose.onNodeWithText("Te enviaremos una notificación", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("activar-notificaciones").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("notificaciones-ahora-no").assertHeightIsAtLeast(48.dp).performClick()

        assertEquals(listOf("activar", "ahora no"), acciones)
    }

    @Test
    fun `negado explica y ofrece el seguimiento como alternativa`() {
        dibujar(EstadoPermiso.NEGADO)

        compose.onNodeWithText("Las notificaciones están desactivadas").assertIsDisplayed()
        compose.onNodeWithText("«Mis siniestros»", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("aviso-ver-siniestros").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Activar en Ajustes").performClick()

        assertEquals(listOf("siniestros", "activar"), acciones)
    }

    @Test
    fun `sin seguimiento no ofrece ir a el`() {
        dibujar(EstadoPermiso.NEGADO, conSiniestros = false)

        compose.onNodeWithTag("aviso-notificaciones").assertIsDisplayed()
        compose.onNodeWithTag("aviso-ver-siniestros").assertDoesNotExist()
    }

    @Test
    fun `concedido no muestra aviso`() {
        dibujar(EstadoPermiso.CONCEDIDO)

        compose.onNodeWithTag("aviso-notificaciones").assertDoesNotExist()
    }

    @Test
    fun `el estado sale del permiso, la version y si ya se pidio`() {
        assertEquals(EstadoPermiso.CONCEDIDO, estadoPermiso(permitidas = true, pedible = true, pedido = true))
        assertEquals(EstadoPermiso.SIN_PEDIR, estadoPermiso(permitidas = false, pedible = true, pedido = false))
        assertEquals(EstadoPermiso.NEGADO, estadoPermiso(permitidas = false, pedible = true, pedido = true))
        // Antes de Android 13 no se pide: si están apagadas, solo queda Ajustes.
        assertEquals(EstadoPermiso.NEGADO, estadoPermiso(permitidas = false, pedible = false, pedido = false))
    }

    @Test
    fun `con estado real, ahora no pasa al aviso con alternativa y queda recordado`() {
        val almacen = AlmacenEnMemoria()
        compose.setContent {
            SolventaTheme { AvisoPermisoNotificaciones(almacen, onVerSiniestros = { acciones += "siniestros" }) }
        }

        compose.onNodeWithTag("notificaciones-ahora-no").performClick()

        compose.onNodeWithTag("aviso-ver-siniestros").assertIsDisplayed()
        assertTrue(almacen.pedido)
    }

    @Test
    fun `con el permiso concedido no hay aviso`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.setContent { SolventaTheme { AvisoPermisoNotificaciones(AlmacenEnMemoria(), onVerSiniestros = null) } }

        compose.onNodeWithTag("aviso-notificaciones").assertDoesNotExist()
    }
}
