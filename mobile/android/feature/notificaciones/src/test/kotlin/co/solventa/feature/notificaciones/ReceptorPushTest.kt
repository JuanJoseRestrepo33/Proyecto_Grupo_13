package co.solventa.feature.notificaciones

import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceptorPushTest {
    private val api = BffFalso()
    private val almacen = AlmacenEnMemoria()
    private val notificador = NotificadorFalso()
    private val sesion = SesionFalsa(CLIENTE, "acceso-1")
    private val alcance = TestScope(StandardTestDispatcher())
    private val receptor = ReceptorPush(
        api = api,
        almacen = almacen,
        notificador = notificador,
        sesion = sesion,
        alcance = alcance,
        intervalo = INTERVALO,
    )

    /** La vuelta no termina sola: se detiene antes de que runTest espere a sus hijos. */
    private fun prueba(cuerpo: suspend TestScope.() -> Unit) = alcance.runTest {
        try {
            cuerpo()
        } finally {
            receptor.detener()
        }
    }

    @Test
    fun `al activar registra el dispositivo con el token de la instalacion y una sola vez`() = prueba {
        receptor.activar(CLIENTE)
        runCurrent()
        advanceTimeBy(INTERVALO.toMillis() * 3)
        runCurrent()

        assertEquals(listOf("registrar:instalacion-1"), api.llamadas.filter { it.startsWith("registrar") })
    }

    @Test
    fun `sin red el registro se reintenta en la vuelta siguiente`() = prueba {
        var intentos = 0
        val original = api.registro
        api.registro = { intentos++; if (intentos == 1) sinRed() else original(it) }

        receptor.activar(CLIENTE)
        runCurrent()
        advanceTimeBy(INTERVALO.toMillis() + 1)
        runCurrent()
        advanceTimeBy(INTERVALO.toMillis() + 1)
        runCurrent()

        assertEquals(2, api.llamadas.count { it.startsWith("registrar") })
    }

    @Test
    fun `la primera lectura de la bandeja no muestra nada y la siguiente muestra solo lo nuevo`() = prueba {
        api.bandeja = { listOf(notificacion("viejo")) }
        receptor.activar(CLIENTE)
        runCurrent()
        assertEquals(emptyList<String>(), notificador.mostradas)

        api.bandeja = { listOf(notificacion("nuevo"), notificacion("viejo")) }
        advanceTimeBy(INTERVALO.toMillis() + 1)
        runCurrent()
        assertEquals(listOf("nuevo"), notificador.mostradas)

        advanceTimeBy(INTERVALO.toMillis() + 1)
        runCurrent()
        assertEquals("no se repite", listOf("nuevo"), notificador.mostradas)
    }

    @Test
    fun `las nuevas se muestran de la mas antigua a la mas reciente y las leidas no`() = prueba {
        almacen.vistas[CLIENTE] = emptySet()
        api.bandeja = { listOf(notificacion("3"), notificacion("2", leida = true), notificacion("1")) }

        receptor.activar(CLIENTE)
        runCurrent()

        assertEquals(listOf("1", "3"), notificador.mostradas)
        assertEquals(setOf("1", "2", "3"), almacen.vistas[CLIENTE])
    }

    @Test
    fun `sin permiso no se muestra pero queda atendida y no aparece de golpe al darlo`() = prueba {
        almacen.vistas[CLIENTE] = emptySet()
        notificador.permitido = false
        api.bandeja = { listOf(notificacion("1")) }
        receptor.activar(CLIENTE)
        runCurrent()

        notificador.permitido = true
        advanceTimeBy(INTERVALO.toMillis() + 1)
        runCurrent()

        assertEquals(listOf("1"), notificador.intentos)
        assertEquals(emptyList<String>(), notificador.mostradas)
    }

    @Test
    fun `activar otra vez para el mismo cliente lee la bandeja enseguida`() = prueba {
        receptor.activar(CLIENTE)
        runCurrent()
        receptor.activar(CLIENTE)
        runCurrent()

        assertEquals(2, api.llamadas.count { it == "bandeja" })
    }

    @Test
    fun `al cambiar la cuenta la vuelta se detiene y no muestra lo de la anterior`() = prueba {
        almacen.vistas[CLIENTE] = emptySet()
        receptor.activar(CLIENTE)
        runCurrent()
        sesion.clienteId = "otro"
        api.bandeja = { listOf(notificacion("1")) }
        advanceTimeBy(INTERVALO.toMillis() * 2)
        runCurrent()

        assertEquals(emptyList<String>(), notificador.intentos)
    }

    @Test
    fun `la bandeja que llega despues del cambio de cuenta se descarta`() = prueba {
        almacen.vistas[CLIENTE] = emptySet()
        api.bandeja = {
            sesion.clienteId = "otro"
            listOf(notificacion("1"))
        }
        receptor.activar(CLIENTE)
        runCurrent()

        assertTrue(notificador.intentos.isEmpty())
        assertEquals(emptySet<String>(), almacen.vistas[CLIENTE])
    }

    @Test
    fun `al cerrar sesion da de baja el dispositivo con el token que tenia`() = prueba {
        receptor.activar(CLIENTE)
        runCurrent()

        receptor.darDeBaja()
        sesion.tokenAcceso = null
        runCurrent()

        assertTrue(api.llamadas.contains("baja:DIS-1:Bearer acceso-1"))
        api.llamadas.clear()
        advanceTimeBy(INTERVALO.toMillis() * 2)
        runCurrent()
        assertEquals("la vuelta se detuvo", emptyList<String>(), api.llamadas)
    }

    @Test
    fun `sin dispositivo registrado no hay baja`() = prueba {
        api.registro = { sinRed() }
        receptor.activar(CLIENTE)
        runCurrent()

        receptor.darDeBaja()
        runCurrent()

        assertTrue(api.llamadas.none { it.startsWith("baja") })
    }

    @Test
    fun `marcar leida llama al BFF`() = prueba {
        receptor.marcarLeida("N-1")
        runCurrent()

        assertEquals(listOf("leida:N-1"), api.llamadas)
    }

    @Test
    fun `un fallo de la bandeja no rompe la vuelta`() = prueba {
        almacen.vistas[CLIENTE] = emptySet()
        api.bandeja = { sinRed() }
        receptor.activar(CLIENTE)
        runCurrent()
        api.bandeja = { listOf(notificacion("1")) }
        advanceTimeBy(INTERVALO.toMillis() + 1)
        runCurrent()

        assertEquals(listOf("1"), notificador.mostradas)
    }

    private companion object {
        const val CLIENTE = "cli-1"
        val INTERVALO: Duration = Duration.ofSeconds(30)
    }
}
