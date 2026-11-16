package co.solventa.feature.polizas.lista

import co.solventa.core.network.EstadoPoliza
import co.solventa.feature.polizas.BffFalso
import co.solventa.feature.polizas.POR_INICIAR
import co.solventa.feature.polizas.ReglaMain
import co.solventa.feature.polizas.VENCIDA
import co.solventa.feature.polizas.VIGENTE
import co.solventa.feature.polizas.comun.FalloConsulta
import co.solventa.feature.polizas.comun.Pestana
import co.solventa.feature.polizas.fallaBff
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MisPolizasViewModelTest {
    @get:Rule
    val main = ReglaMain()

    private val api = BffFalso()

    @Test
    fun `la primera carga trae las polizas en el orden del BFF y abre en Activas con las vigentes`() {
        val vm = MisPolizasViewModel(api)

        assertEquals(listOf(VIGENTE, VENCIDA, POR_INICIAR), vm.estado.polizas)
        assertFalse(vm.estado.cargando)
        assertEquals(Pestana.ACTIVAS, vm.estado.pestana)
        assertEquals(listOf(VIGENTE), vm.estado.visibles)
        assertEquals(1, api.consultasPolizas)
    }

    @Test
    fun `cada pestana muestra solo las que le corresponden y la por iniciar solo en Todas`() {
        val vm = MisPolizasViewModel(api)

        vm.elegir(Pestana.VENCIDAS)
        assertEquals(listOf(VENCIDA), vm.estado.visibles)
        vm.elegir(Pestana.TODAS)
        assertEquals(listOf(VIGENTE, VENCIDA, POR_INICIAR), vm.estado.visibles)
        vm.elegir(Pestana.ACTIVAS)
        assertTrue(vm.estado.visibles.none { it.estado == EstadoPoliza.POR_INICIAR })
        assertEquals("cambiar de pestaña no consulta", 1, api.consultasPolizas)
    }

    @Test
    fun `una pestana sin polizas queda vacia aunque el cliente tenga otras`() {
        api.polizas = { listOf(VENCIDA) }
        val vm = MisPolizasViewModel(api)

        assertTrue(vm.estado.visibles.isEmpty())
        assertEquals(listOf(VENCIDA), vm.estado.polizas)
    }

    @Test
    fun `un cliente sin polizas recibe la lista vacia`() {
        api.polizas = { emptyList() }
        val vm = MisPolizasViewModel(api)

        assertEquals(emptyList<Any>(), vm.estado.polizas)
        assertNull(vm.estado.error)
    }

    @Test
    fun `sin red se dice distinto y reintentar carga la lista`() {
        api.polizas = { throw IOException("sin red") }
        val vm = MisPolizasViewModel(api)
        assertEquals(FalloConsulta.SIN_RED, vm.estado.error)
        assertNull(vm.estado.polizas)

        api.polizas = { listOf(VIGENTE) }
        vm.actualizar()

        assertNull(vm.estado.error)
        assertEquals(listOf(VIGENTE), vm.estado.polizas)
    }

    @Test
    fun `un 5xx es un error generico que se puede reintentar`() {
        api.polizas = { throw fallaBff(503) }
        val vm = MisPolizasViewModel(api)

        assertEquals(FalloConsulta.GENERICO, vm.estado.error)
        assertFalse(vm.estado.sesionVencida)
    }

    @Test
    fun `un 401 que no se pudo renovar va al flujo de sesion y no se reintenta`() {
        api.polizas = { throw fallaBff(401, "sesion_no_vigente") }
        val vm = MisPolizasViewModel(api)

        assertTrue(vm.estado.sesionVencida)
        assertNull("no es un error que se reintente", vm.estado.error)
        vm.sesionAtendida()
        assertFalse(vm.estado.sesionVencida)
    }

    @Test
    fun `actualizar muestra la lista anterior mientras llega la nueva y no duplica la consulta`() {
        val vm = MisPolizasViewModel(api)
        val respuesta = CompletableDeferred<Unit>()
        api.polizas = {
            respuesta.await()
            listOf(VIGENTE)
        }

        vm.actualizar()
        vm.actualizar()

        assertTrue(vm.estado.actualizando)
        assertEquals(listOf(VIGENTE, VENCIDA, POR_INICIAR), vm.estado.polizas)
        assertEquals(2, api.consultasPolizas)
        respuesta.complete(Unit)
        assertFalse(vm.estado.actualizando)
        assertEquals(listOf(VIGENTE), vm.estado.polizas)
    }

    @Test
    fun `entrar de nuevo a la pestana consulta y la poliza recien emitida aparece primero en Activas`() {
        val vm = MisPolizasViewModel(api, entradaInicial = 1)
        val emitida = VIGENTE.copy(numero = "POL-2026-001105", inicio = "2026-11-15")
        api.polizas = { listOf(emitida, VIGENTE, VENCIDA) }

        vm.alEntrar(2)

        assertEquals(2, api.consultasPolizas)
        assertEquals(emitida, vm.estado.visibles.first())
    }

    @Test
    fun `recomponer con la misma entrada no consulta, como al rotar o volver del detalle`() {
        val vm = MisPolizasViewModel(api, entradaInicial = 3)

        vm.alEntrar(3)
        vm.alEntrar(3)

        assertEquals(1, api.consultasPolizas)
    }

    @Test
    fun `si el refresco al entrar falla se conserva la lista anterior con el aviso`() {
        val vm = MisPolizasViewModel(api, entradaInicial = 1)
        api.polizas = { throw IOException("sin red") }

        vm.alEntrar(2)

        assertEquals(listOf(VIGENTE, VENCIDA, POR_INICIAR), vm.estado.polizas)
        assertEquals(FalloConsulta.SIN_RED, vm.estado.error)
        assertFalse(vm.estado.cargando)
    }
}
