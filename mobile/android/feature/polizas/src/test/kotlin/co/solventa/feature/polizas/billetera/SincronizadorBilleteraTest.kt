package co.solventa.feature.polizas.billetera

import co.solventa.core.network.EstadoPoliza
import co.solventa.feature.polizas.BilleteraDePrueba
import co.solventa.feature.polizas.CLIENTE
import co.solventa.feature.polizas.COMPLETA
import co.solventa.feature.polizas.OTRO_CLIENTE
import co.solventa.feature.polizas.POR_INICIAR
import co.solventa.feature.polizas.ReglaMain
import co.solventa.feature.polizas.SINCRONIZADA
import co.solventa.feature.polizas.VENCIDA
import co.solventa.feature.polizas.VIGENTE
import co.solventa.feature.polizas.billeteraDePrueba
import co.solventa.feature.polizas.fallaBff
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SincronizadorBilleteraTest {
    @get:Rule
    val main = ReglaMain()

    @get:Rule
    val carpeta = TemporaryFolder()

    private val b by lazy { BilleteraDePrueba(carpeta.root, CoroutineScope(main.dispatcher)) }
    private val sincronizador get() = b.sincronizador

    private fun enDisco(): BilleteraGuardada? = runBlocking { b.almacen.leer { true } }

    // ── Todo o nada ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `una sincronizacion completa guarda la lista y el detalle de las vigentes y por iniciar`() =
        runTest(main.dispatcher) {
        val resultado = sincronizador.sincronizar()

        val guardada = (resultado as ResultadoSincronizacion.Completa).billetera
        assertEquals(CLIENTE, guardada.clienteId)
        assertEquals("Ana María Rojas", guardada.nombreCompleto)
        assertEquals(listOf(VIGENTE.numero, VENCIDA.numero, POR_INICIAR.numero), guardada.polizas.map { it.numero })
        assertEquals(
            "las vencidas guardan solo el resumen",
            listOf(VIGENTE.numero, POR_INICIAR.numero),
            guardada.detalles.map { it.numero },
        )
        assertEquals(SINCRONIZADA, guardada.momento)
        assertEquals(guardada, enDisco())
        assertEquals(guardada, sincronizador.estado.value.guardada)
    }

    @Test
    fun `con la lista que ya trajo la pantalla no la vuelve a pedir`() = runTest(main.dispatcher) {
        sincronizador.sincronizar(listOf(VIGENTE))

        assertEquals(0, b.api.consultasPolizas)
        assertEquals(1, b.api.consultasDetalle)
    }

    @Test
    fun `si falla un detalle la billetera anterior queda entera y su fecha no cambia`() = runTest(main.dispatcher) {
        b.guardar()
        b.reloj.instante = Instant.parse("2026-11-19T10:00:00Z")
        b.api.polizas = { listOf(VIGENTE, POR_INICIAR) }
        b.api.detalle = { numero -> if (numero == POR_INICIAR.numero) throw fallaBff(503) else COMPLETA }

        val resultado = sincronizador.sincronizar()

        assertEquals(ResultadoSincronizacion.Fallida(), resultado)
        assertEquals(billeteraDePrueba(), enDisco())
        assertEquals(SINCRONIZADA, enDisco()?.momento)
    }

    @Test
    fun `sin red al pedir un detalle tampoco reemplaza la billetera`() = runTest(main.dispatcher) {
        b.guardar()
        b.api.detalle = { throw IOException("sin red") }

        assertEquals(ResultadoSincronizacion.Fallida(), sincronizador.sincronizar())
        assertEquals(billeteraDePrueba(), enDisco())
    }

    @Test
    fun `un 404 de una poliza que desaparecio entre las dos llamadas falla la sincronizacion entera`() =
        runTest(main.dispatcher) {
        b.guardar()
        b.api.detalle = { throw fallaBff(404, "poliza_no_encontrada") }

        assertEquals(ResultadoSincronizacion.Fallida(), sincronizador.sincronizar())
        assertEquals(billeteraDePrueba(), enDisco())
    }

    @Test
    fun `un 401 que no se pudo renovar se informa para ir al flujo de sesion`() = runTest(main.dispatcher) {
        b.api.polizas = { throw fallaBff(401, "sesion_no_vigente") }

        assertEquals(ResultadoSincronizacion.Fallida(sesionVencida = true), sincronizador.sincronizar())
    }

    @Test
    fun `si el detalle y el resumen difieren, manda el detalle que se pidio despues`() = runTest(main.dispatcher) {
        val viejo = VIGENTE.copy(estado = EstadoPoliza.POR_INICIAR, fin = "2027-01-01")
        b.api.polizas = { listOf(viejo) }

        val guardada = (sincronizador.sincronizar() as ResultadoSincronizacion.Completa).billetera

        assertEquals(COMPLETA.resumen, guardada.polizas.single())
    }

    @Test
    fun `un cliente sin polizas tambien tiene billetera`() = runTest(main.dispatcher) {
        b.api.polizas = { emptyList() }

        val resultado = sincronizador.sincronizar()

        assertTrue(resultado is ResultadoSincronizacion.Completa)
        assertEquals(emptyList<Any>(), enDisco()?.polizas)
    }

    // ── Una a la vez ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `si ya hay una sincronizacion en curso otro disparo espera esa y no lanza otra`() = runTest(main.dispatcher) {
        val respuesta = CompletableDeferred<Unit>()
        b.api.polizas = {
            respuesta.await()
            listOf(VIGENTE)
        }

        val primera = async { sincronizador.sincronizar() }
        val segunda = async { sincronizador.sincronizar() }
        assertTrue(sincronizador.estado.value.sincronizando)
        respuesta.complete(Unit)

        assertSame(primera.await(), segunda.await())
        assertEquals(1, b.api.consultasPolizas)
        assertEquals(1, b.api.consultasDetalle)
        assertFalse(sincronizador.estado.value.sincronizando)
    }

    // ── Cuentas y carreras (§3) ──────────────────────────────────────────────────────────────

    @Test
    fun `si otra cuenta inicia sesion durante una sincronizacion no queda la billetera de la anterior`() =
        runTest(main.dispatcher) {
        val detalle = CompletableDeferred<Unit>()
        b.api.detalle = {
            detalle.await()
            COMPLETA
        }
        val sincronizacion = async { sincronizador.sincronizar(listOf(VIGENTE)) }

        // GestorSesion: antes, cambia la sesión, después.
        sincronizador.cambioDeCuenta()
        b.cuenta.clienteId = OTRO_CLIENTE
        sincronizador.cuentaCambiada(OTRO_CLIENTE)
        detalle.complete(Unit)

        assertEquals(ResultadoSincronizacion.Omitida, sincronizacion.await())
        assertNull(enDisco())
        assertNull(sincronizador.estado.value.guardada)
    }

    @Test
    fun `una escritura que se colo antes del cambio de cuenta la borra el observador despues`() =
        runTest(main.dispatcher) {
        // La sincronización de CLIENTE ya comprobó la cuenta y escribió; luego entra OTRO_CLIENTE.
        sincronizador.sincronizar()
        sincronizador.cambioDeCuenta()
        b.cuenta.clienteId = OTRO_CLIENTE
        sincronizador.cuentaCambiada(OTRO_CLIENTE)

        assertNull(enDisco())
        assertNull(sincronizador.cargar())
    }

    @Test
    fun `si cierra sesion durante una sincronizacion no queda nada en disco`() = runTest(main.dispatcher) {
        b.guardar()
        val detalle = CompletableDeferred<Unit>()
        b.api.detalle = {
            detalle.await()
            COMPLETA
        }
        val sincronizacion = async { sincronizador.sincronizar(listOf(VIGENTE)) }

        sincronizador.cerrarSesion()
        sincronizador.cambioDeCuenta()
        b.cuenta.clienteId = null
        sincronizador.cuentaCambiada(null)
        detalle.complete(Unit)

        assertEquals(ResultadoSincronizacion.Omitida, sincronizacion.await())
        assertNull(enDisco())
    }

    @Test
    fun `tras reiniciar el proceso la billetera de otro duenio no se muestra y se borra`() = runTest(main.dispatcher) {
        b.guardar(billeteraDePrueba(cliente = OTRO_CLIENTE))

        assertNull(sincronizador.cargar())
        assertNull(enDisco())
    }

    @Test
    fun `el vencimiento de la sesion no borra la billetera y el cierre si`() = runTest(main.dispatcher) {
        b.guardar()

        // Vencimiento: ObservadorCuenta recibe null, igual que en el cierre.
        sincronizador.cambioDeCuenta()
        b.cuenta.clienteId = null
        sincronizador.cuentaCambiada(null)
        assertEquals(billeteraDePrueba(), enDisco())

        b.cuenta.clienteId = CLIENTE
        sincronizador.cerrarSesion()
        assertNull(enDisco())
        assertNull(sincronizador.estado.value.guardada)
    }

    @Test
    fun `sin sesion no sincroniza`() = runTest(main.dispatcher) {
        b.cuenta.clienteId = null

        assertEquals(ResultadoSincronizacion.Omitida, sincronizador.sincronizar())
        assertEquals(0, b.api.consultasPolizas)
    }

    // ── Reconexión ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `al pasar de sin red a con red y con sesion avisa una vez`() = runTest(main.dispatcher) {
        b.monitor.conectado.value = false
        var reconexiones = 0
        val vigilancia = launch { sincronizador.vigilarReconexion { reconexiones += 1 } }

        b.monitor.conectado.value = true
        b.monitor.conectado.value = false
        b.cuenta.clienteId = null
        b.monitor.conectado.value = true

        assertEquals("sin sesión no hay a quién sincronizar", 1, reconexiones)
        vigilancia.cancel()
    }

    @Test
    fun `con red desde el principio no avisa`() = runTest(main.dispatcher) {
        var reconexiones = 0
        val vigilancia = launch { sincronizador.vigilarReconexion { reconexiones += 1 } }

        b.monitor.conectado.value = true

        assertEquals(0, reconexiones)
        vigilancia.cancel()
    }
}
