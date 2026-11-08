package co.solventa.feature.siniestros.evidencia

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.core.network.TipoEventoSiniestro
import co.solventa.feature.siniestros.AlmacenFalso
import co.solventa.feature.siniestros.BffFalso
import co.solventa.feature.siniestros.Entorno
import co.solventa.feature.siniestros.Claves
import co.solventa.feature.siniestros.HOY
import co.solventa.feature.siniestros.POLIZA_VIGENTE
import co.solventa.feature.siniestros.ReglaMain
import co.solventa.feature.siniestros.fallaBff
import co.solventa.feature.siniestros.reporte.Campo
import co.solventa.feature.siniestros.reporte.PantallaReporte
import co.solventa.feature.siniestros.reporte.ReporteViewModel
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es-w411dp-h914dp")
class PasoEvidenciaTest {
    @get:Rule(order = 0)
    val main = ReglaMain()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule(order = 2)
    val carpeta = TemporaryFolder()

    private val api = BffFalso()
    private val entorno by lazy { Entorno(File(carpeta.root, "durable"), api) }
    private val almacen by lazy { AlmacenFalso(carpeta.root) }
    private var camaraAbierta = 0
    private var permisosPedidos = 0
    private var galeria = 0
    private var ajustes = 0
    private lateinit var viewModel: ReporteViewModel

    private val gestor get() = viewModel.evidencia

    /** Llega al paso de evidencia con el asistente real. */
    private fun mostrar(
        camara: EstadoPermiso = EstadoPermiso.SIN_PEDIR,
        ubicacion: EstadoPermiso = EstadoPermiso.SIN_PEDIR,
    ) {
        viewModel = ReporteViewModel(
            api,
            SavedStateHandle(),
            { HOY },
            Claves()::nueva,
            FuentesEvidencia(almacen, { Coordenadas(4.711, -74.0721) }),
            enviador = entorno.enviador,
        )
        viewModel.actualizar(Campo.POLIZA) { it.copy(numeroPoliza = POLIZA_VIGENTE.numero) }
        viewModel.continuar()
        viewModel.actualizar(Campo.TIPO) { it.copy(tipoEvento = TipoEventoSiniestro.HURTO) }
        viewModel.actualizar(Campo.FECHA) { it.copy(fecha = HOY.minusDays(3)) }
        viewModel.actualizar(Campo.DESCRIPCION) { it.copy(descripcion = "Me robaron el celular en el bus") }
        viewModel.continuar()
        gestor.permisos(camara, ubicacion)
        val acciones = AccionesEvidencia(
            abrirCamara = { camaraAbierta += 1 },
            pedirPermisos = { permisosPedidos += 1 },
            abrirGaleria = { galeria += 1 },
            abrirAjustes = { ajustes += 1 },
        )
        compose.setContent { SolventaTheme { PantallaReporte(viewModel, {}, {}, acciones) } }
    }

    private fun tocar(etiqueta: String) = compose.onNodeWithTag(etiqueta).performScrollTo().performClick()

    @Test
    fun `es un paso opcional entre informacion y revision`() {
        mostrar()
        compose.onNodeWithText("Paso 3 de 4 · Evidencia").assertIsDisplayed()
        tocar("continuar")
        compose.onNodeWithText("Paso 4 de 4 · Revisión").assertIsDisplayed()
        compose.onNodeWithText("Sin evidencia adjunta").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `explica para que sirven los permisos antes de pedirlos`() {
        mostrar()
        tocar("tomar-foto")
        compose.onNodeWithTag("explicacion-permisos").assertIsDisplayed()
        assertEquals(0, permisosPedidos)
        compose.onNodeWithText("Entendido").performScrollTo().performClick()
        assertEquals(1, permisosPedidos)
    }

    @Test
    fun `con los permisos resueltos abre la camara`() {
        mostrar(EstadoPermiso.CONCEDIDO, EstadoPermiso.NEGADO)
        tocar("tomar-foto")
        assertEquals(1, camaraAbierta)
        assertEquals(0, permisosPedidos)
    }

    @Test
    fun `sin camara explica y ofrece la galeria`() {
        mostrar(camara = EstadoPermiso.NEGADO)
        compose.onNodeWithTag("aviso-camara").performScrollTo().assertIsDisplayed()
        tocar("galeria")
        assertEquals(1, galeria)
    }

    @Test
    fun `con la denegacion permanente indica los ajustes y mantiene la alternativa`() {
        mostrar(EstadoPermiso.PERMANENTE, EstadoPermiso.PERMANENTE)
        compose.onNodeWithTag("tomar-foto").assertDoesNotExist()
        compose.onNodeWithTag("aviso-camara").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("aviso-ubicacion").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Abrir ajustes de la app").performScrollTo().performClick()
        assertEquals(1, ajustes)
        tocar("galeria")
        assertEquals(1, galeria)
    }

    @Test
    fun `la lista muestra cada archivo y lo que se quita desaparece`() {
        mostrar(EstadoPermiso.CONCEDIDO, EstadoPermiso.CONCEDIDO)
        compose.runOnIdle { gestor.fotoTomada("a.jpg") }
        compose.onNodeWithText("Foto 1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("10 B").assertIsDisplayed()
        compose.onNodeWithText("4.71100, -74.07210").assertIsDisplayed()
        compose.onNodeWithContentDescription("Vista previa de Foto 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Quitar Foto 1").performClick()
        compose.onNodeWithText("Foto 1").assertDoesNotExist()
    }

    @Test
    fun `sin coordenadas ofrece escribir el lugar`() {
        mostrar()
        compose.runOnIdle { gestor.elegido("a.jpg") }
        compose.onNodeWithTag("lugar-0").performScrollTo().performTextInput("Calle 100")
        compose.runOnIdle { assertEquals("Calle 100", gestor.estado.adjuntos.single().lugar) }
    }

    @Test
    fun `explica el rechazo y los archivos que no caben se pueden quitar`() {
        almacen.resultado = { Preparado.Rechazado(MotivoRechazo.NO_CABE, FormatoEvidencia.MP4, TAMANO_MAXIMO * 2) }
        mostrar()
        compose.runOnIdle { gestor.elegido("largo.mp4") }
        compose.onNodeWithText("No logramos dejar el archivo en 10 MB, el límite por archivo.").performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Quitar Video 1").performClick()
        compose.onNodeWithText("Video 1").assertDoesNotExist()
    }

    @Test
    fun `mientras comprime muestra el avance y no deja continuar`() {
        mostrar()
        val bloqueo = CompletableDeferred<Unit>()
        val demorado = object : AlmacenEvidencias by almacen {
            override suspend fun preparar(uri: String, onProgreso: (Float) -> Unit): Preparado {
                onProgreso(0.4f)
                bloqueo.await()
                return almacen.preparar(uri, onProgreso)
            }
        }
        val alcance = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        val otro = GestorEvidencia(api, FuentesEvidencia(demorado), alcance, SavedStateHandle())
        otro.elegido("a.mp4")
        assertTrue(otro.estado.preparando)
        assertEquals(0.4f, otro.estado.progreso)
        bloqueo.complete(Unit)
        compose.runOnIdle { assertTrue(!otro.estado.preparando) }
        compose.onNodeWithTag("continuar").performScrollTo()
    }

    @Test
    fun `la revision muestra lo adjunto y la confirmacion dice que fallo y deja reintentar`() {
        api.carga = { throw IOException("sin red") }
        mostrar(EstadoPermiso.CONCEDIDO, EstadoPermiso.CONCEDIDO)
        compose.runOnIdle { gestor.fotoTomada("a.jpg") }
        tocar("continuar")
        compose.onNodeWithText("Foto 1 · 10 B").performScrollTo().assertIsDisplayed()
        tocar("enviar")

        compose.onNodeWithText("SIN-2026-000123").assertIsDisplayed()
        compose.onNodeWithTag("carga-0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Foto 1: no se adjuntó", substring = true).assertIsDisplayed()

        api.carga = { api.evidenciaDe(it) }
        tocar("reintentar-evidencia")
        compose.onNodeWithText("Foto 1: adjuntado").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Evidencia del siniestro").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reintentar-evidencia").assertDoesNotExist()
    }

    @Test
    fun `un rechazo definitivo se explica sin ofrecer reintentar`() {
        api.carga = { throw fallaBff(409, "siniestro_cerrado") }
        mostrar(EstadoPermiso.CONCEDIDO, EstadoPermiso.CONCEDIDO)
        compose.runOnIdle { gestor.fotoTomada("a.jpg") }
        tocar("continuar")
        tocar("enviar")
        compose.onNodeWithText("Este caso ya está cerrado", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reintentar-evidencia").assertDoesNotExist()
    }

    @Test
    fun `desde la confirmacion se agrega mas con el mismo paso`() {
        mostrar(EstadoPermiso.CONCEDIDO, EstadoPermiso.CONCEDIDO)
        tocar("continuar")
        tocar("enviar")
        tocar("agregar-evidencia")
        compose.onNodeWithTag("enviar-evidencia").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { gestor.fotoTomada("b.jpg") }
        tocar("enviar-evidencia")
        compose.onNodeWithText("Foto 1: adjuntado").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("b.jpg"), api.cargas.map { it.nombre })
    }

    @Test
    fun `lo que se agrega despues conserva el numero que tendra en la confirmacion`() {
        mostrar(EstadoPermiso.CONCEDIDO, EstadoPermiso.CONCEDIDO)
        compose.runOnIdle { gestor.fotoTomada("a.jpg") }
        tocar("continuar")
        tocar("enviar")
        tocar("agregar-evidencia")
        compose.runOnIdle { gestor.fotoTomada("b.jpg") }
        compose.onNodeWithText("Foto 2").performScrollTo().assertIsDisplayed()
        tocar("enviar-evidencia")
        compose.onNodeWithText("Foto 2: adjuntado").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `la evidencia del reporte sigue subiendo aunque el cliente salga`() {
        val bloqueo = CompletableDeferred<Unit>()
        api.carga = { datos ->
            bloqueo.await()
            api.evidenciaDe(datos)
        }
        mostrar(EstadoPermiso.CONCEDIDO, EstadoPermiso.CONCEDIDO)
        compose.runOnIdle { gestor.fotoTomada("a.jpg") }
        tocar("continuar")
        tocar("enviar")
        compose.onNodeWithText("Enviando la evidencia…").performScrollTo().assertIsDisplayed()
        // La sube el enviador desde el borrador cifrado: salir no la corta ni la borra.
        compose.onNodeWithText("Volver al inicio").performScrollTo().assertIsEnabled()
        compose.runOnIdle { bloqueo.complete(Unit) }
        compose.onNodeWithText("Foto 1: adjuntado").performScrollTo().assertIsDisplayed()
        assertEquals(1, api.cargas.size)
    }

    @Test
    fun `si un archivo desaparecio de la cache se avisa antes de enviar y no se pierde en silencio`() {
        mostrar(EstadoPermiso.CONCEDIDO, EstadoPermiso.CONCEDIDO)
        compose.runOnIdle { gestor.fotoTomada("a.jpg") }
        val ruta = gestor.estado.adjuntos.single().ruta
        tocar("continuar")
        File(ruta).delete()
        tocar("enviar")
        compose.onNodeWithText("Un archivo de evidencia ya no estaba en el teléfono", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Paso 3 de 4 · Evidencia").assertIsDisplayed()
        assertTrue(api.reportes.isEmpty())
    }

    @Test
    fun `al guardar el borrador no queda la copia en claro en la cache`() {
        entorno.monitor.conectado.value = false
        mostrar(EstadoPermiso.CONCEDIDO, EstadoPermiso.CONCEDIDO)
        compose.runOnIdle { gestor.fotoTomada("a.jpg") }
        val ruta = gestor.estado.adjuntos.single().ruta
        tocar("continuar")
        tocar("enviar")
        compose.onNodeWithTag("guardado-sin_conexion").assertIsDisplayed()
        compose.onNodeWithText("Guardamos tu reporte y 1 archivo de evidencia", substring = true).assertIsDisplayed()
        assertFalse(File(ruta).exists())
    }

    @Test
    fun `abandonar el asistente sin enviar borra lo adjunto`() {
        mostrar(EstadoPermiso.CONCEDIDO, EstadoPermiso.CONCEDIDO)
        compose.runOnIdle { gestor.fotoTomada("a.jpg") }
        val ruta = gestor.estado.adjuntos.single().ruta
        repeat(3) { compose.onNodeWithContentDescription("Volver").performClick() }
        compose.runOnIdle { assertEquals(listOf(ruta), almacen.borrados) }
        assertTrue(gestor.estado.adjuntos.isEmpty())
    }

    @Test
    fun `los controles miden al menos 48 dp`() {
        mostrar(EstadoPermiso.PERMANENTE, EstadoPermiso.NEGADO)
        listOf("galeria", "documento").forEach { compose.onNodeWithTag(it).assertHeightIsAtLeast(48.dp) }
        compose.runOnIdle { gestor.elegido("a.jpg") }
        compose.onNodeWithTag("quitar-0").performScrollTo().assertHeightIsAtLeast(48.dp)
    }
}
