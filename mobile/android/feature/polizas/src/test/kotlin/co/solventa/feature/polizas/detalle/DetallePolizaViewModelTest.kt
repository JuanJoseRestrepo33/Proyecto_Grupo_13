package co.solventa.feature.polizas.detalle

import androidx.lifecycle.SavedStateHandle
import co.solventa.feature.polizas.BffFalso
import co.solventa.feature.polizas.COMPLETA
import co.solventa.feature.polizas.DocumentosFalsos
import co.solventa.feature.polizas.NUMERO
import co.solventa.feature.polizas.ReglaMain
import co.solventa.feature.polizas.SIN_DATOS
import co.solventa.feature.polizas.comun.FalloConsulta
import co.solventa.feature.polizas.fallaBff
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DetallePolizaViewModelTest {
    @get:Rule
    val main = ReglaMain()

    private val api = BffFalso()
    private val documentos = DocumentosFalsos()

    private fun detalle(numero: String = NUMERO) =
        DetallePolizaViewModel(api, SavedStateHandle(mapOf("numero" to numero)), documentos, main.dispatcher)

    // ── Consulta ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `consulta la poliza del numero de la ruta con coberturas, deducible y valor asegurado`() {
        var pedido: String? = null
        api.detalle = { numero -> COMPLETA.also { pedido = numero } }
        val vm = detalle()

        assertEquals(NUMERO, pedido)
        assertEquals(COMPLETA, vm.estado.detalle)
        assertEquals(3, vm.estado.detalle?.coberturas?.size)
        assertFalse(vm.estado.cargando)
        assertTrue(vm.estado.ofreceDescarga)
    }

    @Test
    fun `una poliza que no existe o de otro cliente se muestra como no encontrada`() {
        api.detalle = { throw fallaBff(404, "poliza_no_encontrada") }
        val vm = detalle("POL-2026-002101")

        assertTrue(vm.estado.noEncontrada)
        assertNull(vm.estado.error)
    }

    @Test
    fun `un 401 al consultar va al flujo de sesion`() {
        api.detalle = { throw fallaBff(401, "sesion_no_vigente") }
        val vm = detalle()

        assertTrue(vm.estado.sesionVencida)
        vm.sesionAtendida()
        assertFalse(vm.estado.sesionVencida)
    }

    @Test
    fun `sin red o con 5xx se puede reintentar la consulta`() {
        api.detalle = { throw IOException("sin red") }
        val vm = detalle()
        assertEquals(FalloConsulta.SIN_RED, vm.estado.error)

        api.detalle = { throw fallaBff(503) }
        vm.consultar()
        assertEquals(FalloConsulta.GENERICO, vm.estado.error)

        api.detalle = { COMPLETA }
        vm.consultar()
        assertNull(vm.estado.error)
        assertEquals(COMPLETA, vm.estado.detalle)
        assertEquals(3, api.consultasDetalle)
    }

    @Test
    fun `sin documento no se ofrece la descarga y descargar no llama al BFF`() {
        api.detalle = { SIN_DATOS }
        val vm = detalle()

        assertFalse(vm.estado.ofreceDescarga)
        vm.descargar()
        assertTrue(api.descargas.isEmpty())
    }

    // ── Descarga ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `descargar guarda el PDF de esa poliza y lo deja listo para abrir con el visor`() {
        val vm = detalle()

        vm.descargar()

        assertEquals(listOf(NUMERO), api.descargas)
        assertArrayEquals("%PDF-1.4 prueba".toByteArray(), documentos.guardados[NUMERO])
        assertEquals("/cache/polizas/$NUMERO.pdf", vm.estado.pdf)
        assertFalse(vm.estado.descargando)
        vm.pdfAbierto(true)
        assertNull(vm.estado.pdf)
        assertNull(vm.estado.aviso)
    }

    @Test
    fun `mientras descarga el boton queda ocupado y un segundo toque no descarga otra vez`() {
        val vm = detalle()
        val llega = CompletableDeferred<Unit>()
        api.documento = {
            llega.await()
            "%PDF".toResponseBody("application/pdf".toMediaType())
        }

        vm.descargar()
        vm.descargar()

        assertTrue(vm.estado.descargando)
        assertEquals(1, api.descargas.size)
        llega.complete(Unit)
        assertFalse(vm.estado.descargando)
        assertEquals("/cache/polizas/$NUMERO.pdf", vm.estado.pdf)
    }

    @Test
    fun `404 documento_no_disponible avisa y oculta el boton`() {
        api.documento = { throw fallaBff(404, "documento_no_disponible") }
        val vm = detalle()

        vm.descargar()

        assertEquals(AvisoPoliza.DOCUMENTO_NO_DISPONIBLE, vm.estado.aviso)
        assertFalse(AvisoPoliza.DOCUMENTO_NO_DISPONIBLE.reintentable)
        assertFalse(vm.estado.ofreceDescarga)
        assertFalse(vm.estado.noEncontrada)
    }

    @Test
    fun `404 poliza_no_encontrada al descargar muestra la pantalla de no encontrada`() {
        api.documento = { throw fallaBff(404, "poliza_no_encontrada") }
        val vm = detalle()

        vm.descargar()

        assertTrue(vm.estado.noEncontrada)
        assertNull(vm.estado.aviso)
    }

    @Test
    fun `un 401 al descargar va al flujo de sesion`() {
        api.documento = { throw fallaBff(401, "sesion_no_vigente") }
        val vm = detalle()

        vm.descargar()

        assertTrue(vm.estado.sesionVencida)
        assertNull(vm.estado.aviso)
    }

    @Test
    fun `sin red avisa con Reintentar sin salir del detalle y el reintento descarga`() {
        api.documento = { throw IOException("sin red") }
        val vm = detalle()

        vm.descargar()

        assertEquals(AvisoPoliza.DESCARGA_SIN_RED, vm.estado.aviso)
        assertTrue(AvisoPoliza.DESCARGA_SIN_RED.reintentable)
        assertEquals(COMPLETA, vm.estado.detalle)
        assertTrue(vm.estado.ofreceDescarga)

        api.documento = { "%PDF".toResponseBody("application/pdf".toMediaType()) }
        vm.descargar()

        assertNull(vm.estado.aviso)
        assertEquals("/cache/polizas/$NUMERO.pdf", vm.estado.pdf)
    }

    @Test
    fun `un tiempo agotado o un corte a mitad de la descarga se tratan como sin red`() {
        api.documento = { throw SocketTimeoutException("lento") }
        val vm = detalle()
        vm.descargar()
        assertEquals(AvisoPoliza.DESCARGA_SIN_RED, vm.estado.aviso)

        api.documento = { "%PDF".toResponseBody("application/pdf".toMediaType()) }
        documentos.fallo = IOException("se cortó la conexión")
        vm.descargar()
        assertEquals(AvisoPoliza.DESCARGA_SIN_RED, vm.estado.aviso)
        assertNull(vm.estado.pdf)
    }

    @Test
    fun `un 5xx al descargar avisa con Reintentar`() {
        api.documento = { throw fallaBff(503) }
        val vm = detalle()

        vm.descargar()

        assertEquals(AvisoPoliza.DESCARGA_FALLIDA, vm.estado.aviso)
        assertTrue(AvisoPoliza.DESCARGA_FALLIDA.reintentable)
    }

    @Test
    fun `sin una app para abrir el PDF se avisa`() {
        val vm = detalle()
        vm.descargar()

        vm.pdfAbierto(false)

        assertEquals(AvisoPoliza.SIN_VISOR, vm.estado.aviso)
        assertNull(vm.estado.pdf)
        vm.avisoMostrado()
        assertNull(vm.estado.aviso)
    }
}
