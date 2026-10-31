package co.solventa.core.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

private const val CLIENTE =
    """{"id":"CLI-1","nombre_completo":"Ana","correo":"a@b.co","estado_identidad":"verificada"}"""

private fun sesionJson(acceso: String, refresco: String) =
    """{"token_acceso":"$acceso","token_refresco":"$refresco","expira_en":"2026-10-17T10:15:00Z","cliente":$CLIENTE}"""

/** Sesión en memoria que registra lo que el cliente le avisa. */
private class SesionFalsa(acceso: String? = "ACC-VIEJO", refresco: String? = "REF-VIEJO") : SesionBff {
    @Volatile
    override var tokenAcceso: String? = acceso

    @Volatile
    override var tokenRefresco: String? = refresco
    val renovadas = mutableListOf<Sesion>()
    val vencimientos = mutableListOf<Boolean>()

    override fun alRenovar(nueva: Sesion) {
        renovadas += nueva
        tokenAcceso = nueva.tokenAcceso
        tokenRefresco = nueva.tokenRefresco
    }

    override fun alVencer(tokenRechazado: Boolean) {
        vencimientos += tokenRechazado
        tokenAcceso = null
        tokenRefresco = null
    }
}

class RenovacionSesionTest {
    private val server = MockWebServer()

    @Before
    fun setUp() { server.start() }

    @After
    fun tearDown() { server.shutdown() }

    private fun api(sesion: SesionBff) = NetworkClient.create(server.url("/api/v1/movil/").toString(), sesion = sesion)

    private fun json(codigo: Int, cuerpo: String) =
        MockResponse().setResponseCode(codigo).setHeader("Content-Type", "application/json").setBody(cuerpo)

    private fun noAutenticado(codigo: String = "sesion_no_vigente") =
        json(401, """{"codigo":"$codigo","mensaje":"x"}""").setHeader("WWW-Authenticate", "Bearer")

    private fun siguiente(): RecordedRequest = server.takeRequest(1, TimeUnit.SECONDS) ?: error("Sin petición")

    @Test
    fun `un 401 en una ruta protegida renueva una vez y repite la llamada con el token nuevo`() = runBlocking {
        val sesion = SesionFalsa()
        server.enqueue(noAutenticado())
        server.enqueue(json(200, sesionJson("ACC-NUEVO", "REF-NUEVO")))
        server.enqueue(json(200, CLIENTE))

        assertEquals("CLI-1", api(sesion).obtenerSesion().id)

        assertEquals("Bearer ACC-VIEJO", siguiente().getHeader("Authorization"))
        val renovacion = siguiente()
        assertEquals("/api/v1/movil/sesiones/renovacion", renovacion.path)
        assertEquals("""{"token_refresco":"REF-VIEJO"}""", renovacion.body.readUtf8())
        assertEquals("Bearer ACC-NUEVO", siguiente().getHeader("Authorization"))
        assertEquals(listOf("REF-NUEVO"), sesion.renovadas.map { it.tokenRefresco })
        assertTrue(sesion.vencimientos.isEmpty())
    }

    @Test
    fun `un token de refresco rechazado vence la sesion y devuelve el 401 sin reintentar`() {
        val sesion = SesionFalsa()
        server.enqueue(noAutenticado())
        server.enqueue(noAutenticado("token_refresco_invalido"))

        val falla = assertThrows(HttpException::class.java) { runBlocking { api(sesion).obtenerSesion() } }

        assertEquals(401, falla.code())
        assertEquals(listOf(true), sesion.vencimientos)
        assertNull(sesion.tokenAcceso)
        assertEquals("ni bucle ni segunda renovación", 2, server.requestCount)
    }

    @Test
    fun `si la llamada repetida vuelve a dar 401 no se renueva de nuevo`() {
        val sesion = SesionFalsa()
        server.enqueue(noAutenticado())
        server.enqueue(json(200, sesionJson("ACC-NUEVO", "REF-NUEVO")))
        server.enqueue(noAutenticado())

        val falla = assertThrows(HttpException::class.java) { runBlocking { api(sesion).obtenerSesion() } }

        assertEquals(401, falla.code())
        assertEquals(3, server.requestCount)
        assertEquals(1, sesion.renovadas.size)
        assertEquals("la sesión vence sin culpar al token de refresco", listOf(false), sesion.vencimientos)
    }

    @Test
    fun `un error del BFF al renovar vence la sesion sin declarar rechazado el token`() {
        val sesion = SesionFalsa()
        server.enqueue(noAutenticado())
        server.enqueue(json(503, """{"codigo":"no_disponible","mensaje":"x"}"""))

        assertThrows(HttpException::class.java) { runBlocking { api(sesion).obtenerSesion() } }

        assertEquals(listOf(false), sesion.vencimientos)
    }

    @Test
    fun `una renovacion con cuerpo ilegible vence la sesion`() {
        val sesion = SesionFalsa()
        server.enqueue(noAutenticado())
        server.enqueue(json(200, """{"token_acceso":"ACC-NUEVO"}"""))

        assertThrows(HttpException::class.java) { runBlocking { api(sesion).obtenerSesion() } }

        assertEquals(listOf(false), sesion.vencimientos)
    }

    @Test
    fun `sin red al renovar la sesion vence pero conserva el token de la huella`() {
        val sesion = SesionFalsa()
        server.enqueue(noAutenticado())
        // Varias: OkHttp puede reintentar la conexión antes de rendirse.
        repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }

        assertThrows(HttpException::class.java) { runBlocking { api(sesion).obtenerSesion() } }

        assertEquals(listOf(false), sesion.vencimientos)
        assertNull(sesion.tokenAcceso)
    }

    @Test
    fun `si la renovacion falla las llamadas que esperaban no vuelven a renovar`() = runBlocking {
        val sesion = SesionFalsa()
        val renovaciones = AtomicInteger()
        val llamadas = 3
        val todasVencidas = CountDownLatch(llamadas)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.endsWith("/sesiones/renovacion")) {
                    renovaciones.incrementAndGet()
                    todasVencidas.await(2, TimeUnit.SECONDS)
                    json(503, """{"codigo":"no_disponible","mensaje":"x"}""")
                } else {
                    noAutenticado().also { todasVencidas.countDown() }
                }
        }
        val cliente = api(sesion)

        val resultados = List(llamadas) { async(Dispatchers.IO) { llamarBff { cliente.obtenerSesion() } } }.awaitAll()

        assertTrue(resultados.all { it is Resultado.Falla && it.http == 401 })
        assertEquals(1, renovaciones.get())
        assertEquals(listOf(false), sesion.vencimientos)
    }

    @Test
    fun `un 401 de una ruta publica es de negocio y no renueva`() {
        val sesion = SesionFalsa()
        server.enqueue(noAutenticado("credenciales_invalidas"))

        val falla = assertThrows(HttpException::class.java) {
            runBlocking { api(sesion).iniciarSesion(Credenciales("ana@ejemplo.co", "Errada1")) }
        }

        assertEquals("credenciales_invalidas", ErrorBff.desde(falla)?.codigo)
        assertEquals(1, server.requestCount)
        assertTrue(sesion.renovadas.isEmpty() && sesion.vencimientos.isEmpty())
    }

    @Test
    fun `sin sesion un 401 no intenta renovar`() {
        val sesion = SesionFalsa(acceso = null, refresco = null)
        server.enqueue(noAutenticado())

        assertThrows(HttpException::class.java) { runBlocking { api(sesion).obtenerSesion() } }

        assertEquals(1, server.requestCount)
        assertTrue(sesion.vencimientos.isEmpty())
    }

    @Test
    fun `varias llamadas vencidas a la vez comparten una sola renovacion`() = runBlocking {
        val sesion = SesionFalsa()
        val renovaciones = AtomicInteger()
        val llamadas = 3
        val todasVencidas = CountDownLatch(llamadas)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/sesiones/renovacion") -> {
                    // La renovación espera a que las tres llamadas hayan recibido su 401.
                    todasVencidas.await(2, TimeUnit.SECONDS)
                    json(200, sesionJson("ACC-${renovaciones.incrementAndGet()}", "REF-NUEVO"))
                }
                request.getHeader("Authorization") == "Bearer ACC-VIEJO" -> noAutenticado().also {
                    todasVencidas.countDown()
                }
                else -> json(200, CLIENTE)
            }
        }
        val cliente = api(sesion)

        val resultados = List(llamadas) { async(Dispatchers.IO) { cliente.obtenerSesion().id } }.awaitAll()

        assertEquals(List(llamadas) { "CLI-1" }, resultados)
        assertEquals(1, renovaciones.get())
        assertEquals("ACC-1", sesion.tokenAcceso)
    }

    @Test
    fun `las rutas publicas se reconocen por su ruta`() {
        fun publica(ruta: String) =
            RenovadorSesion.esPublica(okhttp3.Request.Builder().url("https://bff.prueba/api/v1/movil/$ruta").build())
        assertTrue(publica("sesiones"))
        assertTrue(publica("sesiones/renovacion"))
        assertTrue(publica("registros"))
        assertTrue(publica("registros/REG-1/otp/verificacion"))
        assertEquals(false, publica("sesion"))
        assertEquals(false, publica("consentimientos"))
    }
}
