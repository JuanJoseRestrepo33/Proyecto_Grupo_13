package co.solventa.feature.polizas.sinsesion

import co.solventa.core.network.EstadoPoliza
import co.solventa.feature.polizas.BilleteraDePrueba
import co.solventa.feature.polizas.COMPLETA
import co.solventa.feature.polizas.NUMERO
import co.solventa.feature.polizas.ReglaMain
import co.solventa.feature.polizas.SINCRONIZADA
import co.solventa.feature.polizas.VENCIDA
import co.solventa.feature.polizas.credencial.SinCredencial
import co.solventa.feature.polizas.lista.ModoPolizas
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

class BilleteraSinSesionViewModelTest {
    @get:Rule
    val main = ReglaMain()

    @get:Rule
    val carpeta = TemporaryFolder()

    private val b by lazy { BilleteraDePrueba(carpeta.root, CoroutineScope(main.dispatcher)) }

    /** Como tras un cierre del proceso sin señal: sin sesión, sin red y con la billetera guardada. */
    private fun desbloqueada(): BilleteraSinSesionViewModel {
        runBlocking { b.guardar() }
        b.cuenta.clienteId = null
        b.monitor.conectado.value = false
        assertTrue(b.sincronizador.abrirSinSesion())
        return BilleteraSinSesionViewModel(b.sincronizador, b.reloj)
    }

    @Test
    fun `la lista muestra la billetera guardada como sin conexion y no llama al BFF`() {
        val vm = desbloqueada()

        val lista = vm.lista()
        assertEquals(ModoPolizas.SIN_CONEXION, lista.modo)
        assertEquals(3, lista.mostradas?.size)
        assertEquals(SINCRONIZADA, lista.billetera?.momento)
        assertEquals(0, b.api.consultasPolizas)
        assertEquals(0, b.api.consultasDetalle)
    }

    @Test
    fun `el detalle guardado se abre con el PDF inactivo y el de una vencida no esta guardado`() {
        val vm = desbloqueada()

        val detalle = vm.detalle(NUMERO)
        assertEquals(COMPLETA, detalle.detalle)
        assertTrue(detalle.sinConexion)
        assertTrue(detalle.descargaRequiereConexion)
        assertTrue(vm.detalle(VENCIDA.numero).detalleNoGuardado)
    }

    @Test
    fun `la credencial lleva el titular y la fecha de sincronizacion`() {
        val vm = desbloqueada()

        val credencial = vm.credencial(NUMERO)
        assertEquals("Ana María Rojas", credencial.titular)
        assertEquals(SINCRONIZADA, credencial.sincronizadaEn)
        assertNull(credencial.motivo)
        assertEquals(SinCredencial.NO_GUARDADA, vm.credencial(VENCIDA.numero).motivo)
    }

    @Test
    fun `una poliza vencida por fecha no ofrece credencial`() {
        val vm = desbloqueada()
        b.reloj.instante = Instant.parse("2027-10-20T12:00:00Z")
        val despues = BilleteraSinSesionViewModel(b.sincronizador, b.reloj)

        assertEquals(EstadoPoliza.VENCIDA, despues.credencial(NUMERO).detalle?.estado)
        assertEquals(SinCredencial.NO_VIGENTE, despues.credencial(NUMERO).motivo)
        assertEquals(EstadoPoliza.VIGENTE, vm.credencial(NUMERO).detalle?.estado)
    }

    @Test
    fun `cuando vuelve la conexion lo indica sin sincronizar`() {
        val vm = desbloqueada()
        assertFalse(vm.estado.conectado)

        b.monitor.conectado.value = true

        assertTrue(vm.estado.conectado)
        assertEquals("sin sesión no hay a quién sincronizar", 0, b.api.consultasPolizas)
    }

    @Test
    fun `sin el desbloqueo no muestra nada`() {
        runBlocking { b.guardar() }
        b.cuenta.clienteId = null

        val vm = BilleteraSinSesionViewModel(b.sincronizador, b.reloj)

        assertNull(vm.estado.billetera)
        assertEquals(ModoPolizas.SIN_BILLETERA, vm.lista().modo)
    }
}
