package co.solventa.mobile.mock

import co.solventa.core.network.BffApi
import co.solventa.core.network.Credenciales
import co.solventa.core.network.ErrorBff
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.SolicitudDispositivo
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.DebugMockInterceptor
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

// Push (MOB-F06) en el mock, a través del cliente real: BffApi → OkHttp → DebugMockInterceptor.
class MockNotificacionesTest {
    private var ahora = Instant.parse("2026-11-15T10:00:00Z")
    private var token: String? = null
    private val api: BffApi = NetworkClient.create(
        BuildConfig.BASE_URL,
        listOf(DebugMockInterceptor(MockBffMovil { ahora })),
        tokenAcceso = { token },
    )

    private fun ingresar(correo: String = CORREO_DEMO) = runBlocking {
        token = api.iniciarSesion(Credenciales(correo, CONTRASENA_DEMO)).tokenAcceso
    }

    private fun falla(llamada: suspend () -> Any?): Pair<Int, String?> {
        val error = assertThrows(HttpException::class.java) { runBlocking { llamada() } }
        return error.code() to ErrorBff.desde(error)?.codigo
    }

    @Test
    fun `registrar es idempotente por token y otro token es otro dispositivo`() = runBlocking {
        ingresar()
        val primero = api.registrarDispositivo(SolicitudDispositivo("instalacion-1"))
        val otraVez = api.registrarDispositivo(SolicitudDispositivo("instalacion-1"))
        val otro = api.registrarDispositivo(SolicitudDispositivo("instalacion-2"))

        assertEquals(primero.id, otraVez.id)
        assertNotEquals(primero.id, otro.id)
        assertEquals("android", primero.plataforma)
    }

    @Test
    fun `un token vacio o con espacios responde 422 y sin sesion 401`() {
        assertEquals(401 to "sesion_no_vigente", falla { api.registrarDispositivo(SolicitudDispositivo("x")) })
        assertEquals(401 to "sesion_no_vigente", falla { api.listarNotificaciones() })
        ingresar()
        assertEquals(422 to "token_push_invalido", falla { api.registrarDispositivo(SolicitudDispositivo("a b")) })
        assertEquals(422 to "token_push_invalido", falla { api.registrarDispositivo(SolicitudDispositivo("")) })
    }

    @Test
    fun `la notificacion de demostracion llega despues del registro y abre el siniestro en evaluacion`() =
        runBlocking {
            ingresar()
            assertTrue("sin dispositivo no hay push", api.listarNotificaciones().isEmpty())
            api.registrarDispositivo(SolicitudDispositivo("instalacion-1"))
            assertTrue("aún no cambia", api.listarNotificaciones().isEmpty())

            ahora = ahora.plus(ESPERA_PUSH_DEMO)
            val notificacion = api.listarNotificaciones().single()

            assertEquals(NOTIFICACION_DEMO, notificacion.id)
            assertEquals(SINIESTRO_EN_EVALUACION, notificacion.idSiniestro)
            assertFalse(notificacion.leida)
            api.marcarNotificacionLeida(NOTIFICACION_DEMO)
            assertTrue(api.listarNotificaciones().single().leida)
        }

    @Test
    fun `marcar una notificacion inexistente responde 404`() {
        ingresar()
        assertEquals(404 to "notificacion_no_encontrada", falla { api.marcarNotificacionLeida("NOT-x") })
    }

    @Test
    fun `otra cuenta no recibe la notificacion de demostracion`() = runBlocking {
        ingresar(CORREO_SIN_POLIZAS)
        api.registrarDispositivo(SolicitudDispositivo("instalacion-1"))
        ahora = ahora.plus(ESPERA_PUSH_DEMO)

        assertTrue(api.listarNotificaciones().isEmpty())
    }

    @Test
    fun `dar de baja responde 204 y otra vez 404`() = runBlocking {
        ingresar()
        val dispositivo = api.registrarDispositivo(SolicitudDispositivo("instalacion-1"))

        api.darDeBajaDispositivo(dispositivo.id)

        assertEquals(404 to "dispositivo_no_encontrado", falla { api.darDeBajaDispositivo(dispositivo.id) })
    }

    @Test
    fun `la baja con el token explicito funciona aunque la sesion ya no este`() = runBlocking {
        ingresar()
        val acceso = token
        val dispositivo = api.registrarDispositivo(SolicitudDispositivo("instalacion-1"))
        token = null

        api.darDeBajaDispositivo(dispositivo.id, "Bearer $acceso")

        token = acceso
        assertEquals(404 to "dispositivo_no_encontrado", falla { api.darDeBajaDispositivo(dispositivo.id) })
    }
}
