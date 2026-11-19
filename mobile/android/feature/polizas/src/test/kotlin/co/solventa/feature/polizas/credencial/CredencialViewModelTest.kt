package co.solventa.feature.polizas.credencial

import androidx.lifecycle.SavedStateHandle
import co.solventa.core.network.EstadoPoliza
import co.solventa.feature.polizas.BilleteraDePrueba
import co.solventa.feature.polizas.COMPLETA
import co.solventa.feature.polizas.NUMERO
import co.solventa.feature.polizas.POR_INICIAR
import co.solventa.feature.polizas.ReglaMain
import co.solventa.feature.polizas.SINCRONIZADA
import co.solventa.feature.polizas.VENCIDA
import co.solventa.feature.polizas.VIGENTE
import co.solventa.feature.polizas.fallaBff
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CredencialViewModelTest {
    @get:Rule
    val main = ReglaMain()

    @get:Rule
    val carpeta = TemporaryFolder()

    private val b by lazy { BilleteraDePrueba(carpeta.root, CoroutineScope(main.dispatcher)) }

    private fun credencial(numero: String = NUMERO) =
        CredencialViewModel(SavedStateHandle(mapOf("numero" to numero)), b.sincronizador, b.reloj)

    @Test
    fun `sin conexion muestra la credencial guardada con titular, fecha y la franja`() {
        runBlocking { b.guardar() }
        b.monitor.conectado.value = false

        val vm = credencial()

        assertEquals(COMPLETA, vm.estado.detalle)
        assertEquals("Ana María Rojas", vm.estado.titular)
        assertEquals(SINCRONIZADA, vm.estado.sincronizadaEn)
        assertTrue(vm.estado.sinConexion)
        assertNull(vm.estado.motivo)
        assertEquals("siempre lee la billetera, sin llamar al BFF", 0, b.api.consultasDetalle)
    }

    @Test
    fun `con conexion tambien lee la billetera y no lleva la franja`() {
        runBlocking { b.guardar() }

        val vm = credencial()

        assertEquals(COMPLETA, vm.estado.detalle)
        assertFalse(vm.estado.sinConexion)
        assertEquals(0, b.api.consultasPolizas)
    }

    @Test
    fun `si la poliza aun no esta guardada, con conexion sincroniza primero`() {
        val vm = credencial()

        assertEquals(1, b.api.consultasPolizas)
        assertEquals(COMPLETA, vm.estado.detalle)
        assertNull(vm.estado.motivo)
    }

    @Test
    fun `si la poliza aun no esta guardada y no hay red, explica que necesita conexion`() {
        b.monitor.conectado.value = false

        val vm = credencial()

        assertEquals(SinCredencial.NECESITA_CONEXION, vm.estado.motivo)
        assertFalse(vm.estado.cargando)
        assertEquals(0, b.api.consultasPolizas)
    }

    @Test
    fun `una poliza vencida por fecha despues de sincronizar no muestra credencial`() {
        runBlocking { b.guardar() }
        b.reloj.instante = Instant.parse("2027-10-15T15:00:00Z")

        val vm = credencial()

        assertEquals(EstadoPoliza.VENCIDA, vm.estado.detalle?.estado)
        assertEquals(SinCredencial.NO_VIGENTE, vm.estado.motivo)
    }

    @Test
    fun `una por iniciar que ya empezo se muestra vigente`() {
        runBlocking { b.guardar() }
        b.reloj.instante = Instant.parse("2026-11-26T15:00:00Z")

        val vm = credencial(POR_INICIAR.numero)

        assertEquals(EstadoPoliza.VIGENTE, vm.estado.detalle?.estado)
        assertNull(vm.estado.motivo)
    }

    @Test
    fun `una poliza que no queda guardada tras sincronizar no tiene credencial`() {
        val vm = credencial(VENCIDA.numero)

        assertEquals(SinCredencial.NO_GUARDADA, vm.estado.motivo)
    }

    @Test
    fun `al volver la conexion se sincroniza y la fecha de la credencial se actualiza`() {
        runBlocking { b.guardar() }
        b.monitor.conectado.value = false
        val vm = credencial()

        val luego = Instant.parse("2026-11-18T16:00:00Z")
        b.reloj.instante = luego
        b.monitor.conectado.value = true
        b.api.polizas = { listOf(VIGENTE) }
        vm.alReconectar()

        assertEquals(luego, vm.estado.sincronizadaEn)
        assertFalse(vm.estado.sinConexion)
        assertFalse(vm.estado.sincronizando)
    }

    @Test
    fun `si la sincronizacion al reconectar falla se queda la credencial que habia`() {
        runBlocking { b.guardar() }
        val vm = credencial()
        b.api.polizas = { throw fallaBff(503) }

        vm.alReconectar()

        assertEquals(COMPLETA, vm.estado.detalle)
        assertEquals(SINCRONIZADA, vm.estado.sincronizadaEn)
        assertNull(vm.estado.motivo)
    }
}
