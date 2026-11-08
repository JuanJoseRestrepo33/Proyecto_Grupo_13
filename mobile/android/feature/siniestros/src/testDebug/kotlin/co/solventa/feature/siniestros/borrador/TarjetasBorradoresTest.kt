package co.solventa.feature.siniestros.borrador

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.core.network.ErrorBff
import co.solventa.core.network.SolicitudSiniestro
import co.solventa.core.network.TipoEventoSiniestro
import co.solventa.feature.siniestros.CLIENTE
import co.solventa.feature.siniestros.Entorno
import co.solventa.feature.siniestros.POLIZA_VIGENTE
import co.solventa.feature.siniestros.ReglaMain
import co.solventa.feature.siniestros.evidencia.FormatoEvidencia
import co.solventa.feature.siniestros.fallaBff
import java.io.File
import java.io.IOException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es-w411dp-h914dp")
class TarjetasBorradoresTest {
    @get:Rule(order = 0)
    val main = ReglaMain()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule(order = 2)
    val carpeta = TemporaryFolder()

    private val entorno by lazy { Entorno(carpeta.root) }
    private val revisados = mutableListOf<String>()
    private var ingreso = 0

    @After
    fun cerrar() = entorno.alcance.cancel()

    private val solicitud = SolicitudSiniestro(
        POLIZA_VIGENTE.numero,
        TipoEventoSiniestro.ACCIDENTE_VEHICULO,
        "2026-10-23",
        "Me chocaron en la calle 72",
    )

    private fun mostrar() = compose.setContent {
        SolventaTheme {
            TarjetasBorradores(
                entorno.enviador,
                AccionesTarjeta(onRevisar = { revisados += it }, onIngresar = { ingreso += 1 }),
            )
        }
    }

    private fun enviar(vararg archivos: AdjuntoNuevo) = runBlocking {
        entorno.enviador.enviarDesdeAsistente(solicitud, "clave-1", archivos.toList())
    }

    private fun foto(): AdjuntoNuevo {
        val archivo = File(carpeta.root, "foto.jpg").apply { writeText("foto") }
        return AdjuntoNuevo(AdjuntoBorrador("ADJ-1", "evidencia-1.jpg", FormatoEvidencia.JPEG, 4), archivo)
    }

    private val regionViva = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

    @Test
    fun `un reporte pendiente se ve con su estado y se descarta tras confirmar`() {
        entorno.monitor.conectado.value = false
        enviar()
        mostrar()
        compose.onNodeWithText("Reporte pendiente de envío").assertIsDisplayed()
        compose.onNodeWithText("Accidente de vehículo").assertIsDisplayed()
        compose.onNodeWithText("Póliza …101").assertIsDisplayed()
        compose.onNodeWithText("Esperando conexión").assertIsDisplayed()
        compose.onNodeWithText("Guardado el", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("borrador-${entorno.borradores().single().id}").assertIsDisplayed()
        compose.onNodeWithText("Descartar").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("¿Descartar el reporte?").assertIsDisplayed()
        compose.onNodeWithTag("confirmar-descarte").performClick()
        compose.waitForIdle()
        assertTrue(entorno.borradores().isEmpty())
        compose.onNodeWithText("Reporte pendiente de envío").assertDoesNotExist()
    }

    @Test
    fun `con un intento ya iniciado el descarte aclara que no cancela el siniestro`() {
        entorno.api.reporte = { _, _ -> throw IOException("sin respuesta") }
        enviar()
        mostrar()
        compose.onNodeWithText("no se creará un reporte duplicado", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Descartar").performClick()
        compose.onNodeWithText("¿Eliminar del teléfono?").assertIsDisplayed()
        compose.onNodeWithText("no cancela un siniestro registrado", substring = true).assertIsDisplayed()
    }

    @Test
    fun `al enviarse muestra la referencia hasta que el cliente toca entendido`() {
        enviar()
        mostrar()
        compose.onNodeWithText("Reporte enviado").assertIsDisplayed()
        compose.onNodeWithTag("referencia-enviada").assertIsDisplayed()
        compose.onNodeWithText("SIN-2026-000123").assertIsDisplayed()
        compose.onNodeWithText("Lo verás también en la web.").assertIsDisplayed()
        compose.onNodeWithText("Entendido").assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("SIN-2026-000123").assertDoesNotExist()
    }

    @Test
    fun `un rechazo sin intento incierto ofrece revisar o descartar`() {
        entorno.api.reporte = { _, _ ->
            throw fallaBff(
                409,
                ErrorBff("evento_fuera_de_vigencia", "x", vigenteDesde = "2026-01-01", vigenteHasta = "2026-06-30"),
            )
        }
        enviar()
        mostrar()
        compose.onNodeWithText("Tu reporte necesita revisión").assertIsDisplayed()
        compose.onNodeWithText("cubre del 1 de enero de 2026 al 30 de junio de 2026", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Revisar").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(entorno.borradores().single().id), revisados)
    }

    @Test
    fun `por conciliar no ofrece revisar ni una clave nueva`() {
        val enUso = ErrorBff("clave_idempotencia_en_uso", "x", referencia = "SIN-2026-000555")
        entorno.api.reporte = { _, _ -> throw fallaBff(409, enUso) }
        entorno.api.siniestros = { throw IOException("sin red") }
        enviar()
        mostrar()
        compose.onNodeWithText("Estamos confirmando tu reporte").assertIsDisplayed()
        compose.onNodeWithText("Ya existe SIN-2026-000555.").assertIsDisplayed()
        compose.onNodeWithText("Revisar").assertDoesNotExist()
        compose.onNodeWithText("Reintentar").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `si falla la evidencia dice que archivo y permite reintentar o quitarlo`() {
        entorno.api.carga = { throw fallaBff(503, "almacenamiento_no_disponible") }
        enviar(foto())
        mostrar()
        compose.onNodeWithText("Evidencia: 0 de 1 archivo adjunto").assertIsDisplayed()
        compose.onNodeWithText("Foto 1: El almacenamiento no está disponible", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Quitar Foto 1").assertHeightIsAtLeast(48.dp)
        entorno.api.carga = { entorno.api.evidenciaDe(it) }
        compose.onNodeWithText("Reintentar").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("referencia-enviada").assertIsDisplayed()
    }

    @Test
    fun `sin sesion pide ingresar para enviar`() {
        entorno.api.reporte = { _, _ -> throw fallaBff(401, "sesion_no_vigente") }
        enviar()
        mostrar()
        compose.onNodeWithText("Ingresa para enviar tu reporte pendiente").assertIsDisplayed()
        compose.onNodeWithText("Ingresar").performClick()
        assertEquals(1, ingreso)
    }

    @Test
    fun `un borrador ilegible explica que paso y solo se elimina si el cliente lo decide`() {
        entorno.monitor.conectado.value = false
        enviar()
        val id = entorno.borradores().single().id
        val manifiesto = File(entorno.raiz, "$CLIENTE/$id/borrador.bin")
        val bytes = manifiesto.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        manifiesto.writeBytes(bytes)
        entorno.monitor.conectado.value = true
        mostrar()
        compose.onNodeWithText("No podemos leer un reporte guardado").assertIsDisplayed()
        compose.onNodeWithText("Reportar de nuevo").assertHeightIsAtLeast(48.dp)
        assertTrue(manifiesto.exists())
        compose.onNodeWithText("Eliminar del teléfono").performClick()
        compose.waitForIdle()
        assertTrue(!manifiesto.exists())
    }

    @Test
    fun `los cambios de estado se anuncian como region viva`() {
        entorno.monitor.conectado.value = false
        enviar()
        mostrar()
        val id = entorno.borradores().single().id
        compose.onNode(hasTestTag("borrador-$id")).assertIsDisplayed()
        compose.onNode(regionViva, useUnmergedTree = true).assertExists()
    }
}
