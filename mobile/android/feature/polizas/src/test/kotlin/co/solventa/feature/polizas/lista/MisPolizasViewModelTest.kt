package co.solventa.feature.polizas.lista

import co.solventa.core.network.EstadoPoliza
import co.solventa.feature.polizas.BilleteraDePrueba
import co.solventa.feature.polizas.COMPLETA
import co.solventa.feature.polizas.SINCRONIZADA
import co.solventa.feature.polizas.billeteraDePrueba
import co.solventa.feature.polizas.POR_INICIAR
import co.solventa.feature.polizas.ReglaMain
import co.solventa.feature.polizas.VENCIDA
import co.solventa.feature.polizas.VIGENTE
import co.solventa.feature.polizas.comun.FalloConsulta
import co.solventa.feature.polizas.comun.Pestana
import co.solventa.feature.polizas.fallaBff
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MisPolizasViewModelTest {
    @get:Rule
    val main = ReglaMain()

    @get:Rule
    val carpeta = TemporaryFolder()

    private val billetera by lazy { BilleteraDePrueba(carpeta.root, CoroutineScope(main.dispatcher)) }
    private val api get() = billetera.api

    private fun vm(entradaInicial: Int = 0) =
        MisPolizasViewModel(billetera.api, billetera.sincronizador, entradaInicial, billetera.reloj)

    private fun guardada() = runBlocking { billetera.guardar() }

    @Test
    fun `la primera carga trae las polizas en el orden del BFF y abre en Activas con las vigentes`() {
        val vm = vm()

        assertEquals(listOf(VIGENTE, VENCIDA, POR_INICIAR), vm.estado.polizas)
        assertFalse(vm.estado.cargando)
        assertEquals(Pestana.ACTIVAS, vm.estado.pestana)
        assertEquals(listOf(VIGENTE), vm.estado.visibles)
        assertEquals(1, api.consultasPolizas)
    }

    @Test
    fun `cada pestana muestra solo las que le corresponden y la por iniciar solo en Todas`() {
        val vm = vm()

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
        val vm = vm()

        assertTrue(vm.estado.visibles.isEmpty())
        assertEquals(listOf(VENCIDA), vm.estado.polizas)
    }

    @Test
    fun `un cliente sin polizas recibe la lista vacia`() {
        api.polizas = { emptyList() }
        val vm = vm()

        assertEquals(emptyList<Any>(), vm.estado.polizas)
        assertNull(vm.estado.error)
    }

    @Test
    fun `sin red y sin billetera pide conexion una vez y reintentar carga la lista`() {
        api.polizas = { throw IOException("sin red") }
        val vm = vm()
        assertEquals(ModoPolizas.SIN_BILLETERA, vm.estado.modo)
        assertNull(vm.estado.mostradas)
        assertNull(vm.estado.error)

        api.polizas = { listOf(VIGENTE) }
        vm.actualizar()

        assertEquals(ModoPolizas.EN_LINEA, vm.estado.modo)
        assertEquals(listOf(VIGENTE), vm.estado.polizas)
    }

    @Test
    fun `un 5xx es un error generico que se puede reintentar`() {
        api.polizas = { throw fallaBff(503) }
        val vm = vm()

        assertEquals(FalloConsulta.GENERICO, vm.estado.error)
        assertFalse(vm.estado.sesionVencida)
    }

    @Test
    fun `un 401 que no se pudo renovar va al flujo de sesion y no se reintenta`() {
        api.polizas = { throw fallaBff(401, "sesion_no_vigente") }
        val vm = vm()

        assertTrue(vm.estado.sesionVencida)
        assertNull("no es un error que se reintente", vm.estado.error)
        vm.sesionAtendida()
        assertFalse(vm.estado.sesionVencida)
    }

    @Test
    fun `actualizar muestra la lista anterior mientras llega la nueva y no duplica la consulta`() {
        val vm = vm()
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
        val vm = vm(entradaInicial = 1)
        val emitida = VIGENTE.copy(numero = "POL-2026-001105", inicio = "2026-11-15")
        api.polizas = { listOf(emitida, VIGENTE, VENCIDA) }

        vm.alEntrar(2)

        assertEquals(2, api.consultasPolizas)
        assertEquals(emitida, vm.estado.visibles.first())
    }

    @Test
    fun `recomponer con la misma entrada no consulta, como al rotar o volver del detalle`() {
        val vm = vm(entradaInicial = 3)

        vm.alEntrar(3)
        vm.alEntrar(3)

        assertEquals(1, api.consultasPolizas)
    }

    @Test
    fun `si el refresco al entrar falla sin billetera se conserva la lista anterior con el aviso`() {
        // La sincronización de la primera carga falla: no queda billetera.
        api.detalle = { throw fallaBff(503) }
        val vm = vm(entradaInicial = 1)
        api.polizas = { throw IOException("sin red") }

        vm.alEntrar(2)

        assertEquals(ModoPolizas.EN_LINEA, vm.estado.modo)
        assertEquals(listOf(VIGENTE, VENCIDA, POR_INICIAR), vm.estado.polizas)
        assertEquals(FalloConsulta.SIN_RED, vm.estado.error)
        assertFalse(vm.estado.cargando)
    }

    // ── Billetera sin conexión (MOB-F03, §4) ─────────────────────────────────────────────────

    @Test
    fun `cargar bien la lista sincroniza la billetera y la linea dice desde cuando esta disponible`() {
        val vm = vm()

        assertEquals(ModoPolizas.EN_LINEA, vm.estado.modo)
        assertTrue(vm.estado.billeteraLeida)
        assertEquals(SINCRONIZADA, vm.estado.billetera?.momento)
        assertEquals("pidió el detalle de la vigente y de la por iniciar", 2, api.consultasDetalle)
    }

    @Test
    fun `sin red y con billetera se ve la billetera con la franja sin conexion`() {
        guardada()
        billetera.monitor.conectado.value = false
        api.polizas = { throw IOException("sin red") }

        val vm = vm()

        assertEquals(ModoPolizas.SIN_CONEXION, vm.estado.modo)
        assertTrue(vm.estado.sinConexion)
        assertEquals(listOf(VIGENTE.numero, VENCIDA.numero, POR_INICIAR.numero), vm.estado.mostradas?.map { it.numero })
        assertEquals(SINCRONIZADA, vm.estado.billetera?.momento)
        assertNull(vm.estado.error)
        assertTrue(vm.estado.ofreceCredencial(vm.estado.visibles.single()))
    }

    @Test
    fun `un tiempo agotado con red muestra la billetera con no pudimos actualizar`() {
        guardada()
        api.polizas = { throw SocketTimeoutException("tiempo agotado") }

        val vm = vm()

        assertEquals(ModoPolizas.SIN_ACTUALIZAR, vm.estado.modo)
        assertNotNull(vm.estado.mostradas)
    }

    @Test
    fun `un 5xx sigue lo de 190 y no muestra la billetera`() {
        guardada()
        api.polizas = { throw fallaBff(503) }

        val vm = vm()

        assertEquals(ModoPolizas.EN_LINEA, vm.estado.modo)
        assertFalse(vm.estado.sinConexion)
        assertEquals(FalloConsulta.GENERICO, vm.estado.error)
        assertNull(vm.estado.mostradas)
    }

    @Test
    fun `un 401 que no se pudo renovar sigue el flujo de sesion aunque haya billetera`() {
        guardada()
        api.polizas = { throw fallaBff(401, "sesion_no_vigente") }

        val vm = vm()

        assertTrue(vm.estado.sesionVencida)
        assertFalse(vm.estado.sinConexion)
    }

    @Test
    fun `una billetera sin polizas se ve vacia con su fecha`() {
        runBlocking { billetera.guardar(billeteraDePrueba(polizas = emptyList(), detalles = emptyList())) }
        billetera.monitor.conectado.value = false
        api.polizas = { throw IOException("sin red") }

        val vm = vm()

        assertEquals(ModoPolizas.SIN_CONEXION, vm.estado.modo)
        assertEquals(emptyList<Any>(), vm.estado.mostradas)
        assertEquals(SINCRONIZADA, vm.estado.billetera?.momento)
    }

    @Test
    fun `sin conexion una poliza guardada como vigente cuya fecha paso se ve vencida y sin credencial`() {
        guardada()
        billetera.monitor.conectado.value = false
        api.polizas = { throw IOException("sin red") }
        billetera.reloj.instante = Instant.parse("2027-10-20T12:00:00Z")

        val vm = vm()

        val vigente = vm.estado.mostradas!!.first { it.numero == VIGENTE.numero }
        assertEquals(EstadoPoliza.VENCIDA, vigente.estado)
        assertFalse(vm.estado.ofreceCredencial(vigente))
        assertTrue("ya no está en Activas", vm.estado.visibles.isEmpty())
    }

    @Test
    fun `al volver la conexion se sincroniza sola y la fecha se actualiza`() {
        guardada()
        billetera.monitor.conectado.value = false
        api.polizas = { throw IOException("sin red") }
        val vm = vm()
        assertEquals(ModoPolizas.SIN_CONEXION, vm.estado.modo)

        val luego = Instant.parse("2026-11-18T15:00:00Z")
        billetera.reloj.instante = luego
        billetera.monitor.conectado.value = true
        api.polizas = { listOf(VIGENTE) }
        vm.alReconectar()

        assertEquals(ModoPolizas.EN_LINEA, vm.estado.modo)
        assertEquals(luego, vm.estado.billetera?.momento)
        assertEquals(listOf(COMPLETA.resumen), vm.estado.polizas)
        assertFalse(vm.estado.sincronizando)
    }

    @Test
    fun `si la sincronizacion al reconectar falla se conserva la billetera con no pudimos actualizar`() {
        guardada()
        billetera.monitor.conectado.value = false
        api.polizas = { throw IOException("sin red") }
        val vm = vm()

        billetera.monitor.conectado.value = true
        api.polizas = { listOf(VIGENTE) }
        api.detalle = { throw fallaBff(503) }
        vm.alReconectar()

        assertEquals(ModoPolizas.SIN_ACTUALIZAR, vm.estado.modo)
        assertEquals(SINCRONIZADA, vm.estado.billetera?.momento)
        assertEquals(3, vm.estado.mostradas?.size)
    }

    @Test
    fun `mientras sincroniza al reconectar sigue mostrando lo guardado con el indicador`() {
        guardada()
        billetera.monitor.conectado.value = false
        api.polizas = { throw IOException("sin red") }
        val vm = vm()
        val respuesta = CompletableDeferred<Unit>()
        api.polizas = {
            respuesta.await()
            listOf(VIGENTE)
        }

        vm.alReconectar()

        assertTrue(vm.estado.sincronizando)
        assertEquals(3, vm.estado.mostradas?.size)
        respuesta.complete(Unit)
        assertFalse(vm.estado.sincronizando)
    }
}
