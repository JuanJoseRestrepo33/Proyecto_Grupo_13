package co.solventa.core.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * El cliente real contra el BFF simulado con Mockoon (`mobile/android/mockoon`). Solo corre con
 * `MOCKOON_URL` (p. ej. http://localhost:3001/api/v1/movil/) y un token de acceso de 2 s:
 *
 *     MOCKOON_TTL_ACCESO=2 mobile/android/mockoon/mockoon.sh &
 *     MOCKOON_URL=http://localhost:3001/api/v1/movil/ ./gradlew :core:network:test --tests '*ContraMockoon*'
 */
class ContraMockoonTest {
    private val url: String? = System.getenv("MOCKOON_URL")

    private class SesionEnMemoria : SesionBff {
        @Volatile
        var actual: Sesion? = null
        val vencimientos = mutableListOf<Boolean>()

        override val tokenAcceso get() = actual?.tokenAcceso
        override val tokenRefresco get() = actual?.tokenRefresco

        override fun alRenovar(nueva: Sesion) {
            actual = nueva
        }

        override fun alVencer(tokenRechazado: Boolean) {
            actual = null
            vencimientos += tokenRechazado
        }
    }

    @Before
    fun requiereMockoon() = assumeTrue("MOCKOON_URL no está definida", url != null)

    @Test
    fun `un token de acceso vencido se renueva solo y la llamada protegida responde`() = runBlocking {
        val sesion = SesionEnMemoria()
        val api = NetworkClient.create(requireNotNull(url), sesion = sesion)
        sesion.actual = api.iniciarSesion(Credenciales("ana@ejemplo.co", "Solventa2026"))
        val inicial = requireNotNull(sesion.actual)

        Thread.sleep(VENCIMIENTO_MS)
        val cliente = api.obtenerSesion()

        assertEquals("cli-ana", cliente.id)
        assertNotEquals("se renovó", inicial.tokenAcceso, sesion.tokenAcceso)
        assertNotEquals("y rotó el refresco", inicial.tokenRefresco, sesion.tokenRefresco)
        val reutilizado = llamarBff { api.renovarSesion(SolicitudRenovacion(inicial.tokenRefresco)) }
        assertEquals(401 to "token_refresco_invalido", (reutilizado as Resultado.Falla).let { it.http to it.codigo })
    }

    @Test
    fun `con el refresco ya usado la sesion vence y borra la huella`() = runBlocking {
        val sesion = SesionEnMemoria()
        val api = NetworkClient.create(requireNotNull(url), sesion = sesion)
        val abierta = api.iniciarSesion(Credenciales("ana@ejemplo.co", "Solventa2026"))
        api.renovarSesion(SolicitudRenovacion(abierta.tokenRefresco))
        sesion.actual = abierta

        Thread.sleep(VENCIMIENTO_MS)
        val resultado = llamarBff { api.obtenerSesion() }

        assertEquals(401, (resultado as Resultado.Falla).http)
        assertEquals(listOf(true), sesion.vencimientos)
        assertNull(sesion.tokenAcceso)
    }

    private companion object {
        const val VENCIMIENTO_MS = 2_200L
    }
}
