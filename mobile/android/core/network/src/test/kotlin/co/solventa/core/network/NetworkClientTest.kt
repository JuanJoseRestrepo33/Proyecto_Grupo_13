package co.solventa.core.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

class NetworkClientTest {
    private val server = MockWebServer()

    private val solicitud = SolicitudRegistro(
        nombreCompleto = "Ana María Rojas",
        tipoDocumento = TipoDocumento.CEDULA_CIUDADANIA,
        numeroDocumento = "1020304050",
        correo = "ana@ejemplo.co",
        celular = "3001234567",
        contrasena = "Secreta123",
        versionTerminos = "2026-09",
    )

    @Before
    fun setUp() { server.start() }

    @After
    fun tearDown() { server.shutdown() }

    private fun api(token: String? = null) =
        NetworkClient.create(server.url("/api/v1/movil/").toString(), tokenAcceso = { token })

    private fun json(codigo: Int, cuerpo: String) =
        MockResponse().setResponseCode(codigo).setHeader("Content-Type", "application/json").setBody(cuerpo)

    @Test
    fun `crear registro usa la ruta del contrato y snake_case`() = runBlocking {
        server.enqueue(
            json(
                201,
                """{"id":"REG-1","estado_identidad":"pendiente_verificacion","campo_futuro":1,
                "otp":{"expira_en":"2026-10-17T10:10:00Z","intentos_restantes":3,
                "reenvio_disponible_en":"2026-10-17T10:00:30Z"}}""",
            ),
        )
        val registro = api().crearRegistro(solicitud)
        assertEquals(EstadoIdentidad.PENDIENTE_VERIFICACION, registro.estadoIdentidad)
        assertEquals(3, registro.otp.intentosRestantes)
        val request = server.takeRequest(1, TimeUnit.SECONDS) ?: error("Sin petición")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/movil/registros", request.path)
        val cuerpo = request.body.readUtf8()
        assertTrue(cuerpo.contains(""""nombre_completo":"Ana María Rojas""""))
        assertTrue(cuerpo.contains(""""tipo_documento":"cedula_ciudadania""""))
        assertTrue(cuerpo.contains(""""version_terminos":"2026-09""""))
    }

    @Test
    fun `el token de acceso viaja como Bearer solo si hay sesion`() = runBlocking {
        server.enqueue(
            json(200, """{"id":"CLI-1","nombre_completo":"Ana","correo":"a@b.co","estado_identidad":"verificada"}"""),
        )
        server.enqueue(json(200, """{"id":"KYC-1","estado":"en_proceso","actualizada_en":"2026-10-17T10:00:00Z"}"""))
        assertEquals(EstadoIdentidad.VERIFICADA, api(token = "abc").obtenerSesion().estadoIdentidad)
        assertEquals("Bearer abc", server.takeRequest().getHeader("Authorization"))
        assertEquals(EstadoVerificacion.EN_PROCESO, api().solicitarVerificacionIdentidad().estado)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `un error del contrato conserva codigo y intentos restantes`() {
        server.enqueue(json(422, """{"codigo":"otp_invalido","mensaje":"x","intentos_restantes":2}"""))
        val falla = assertThrows(HttpException::class.java) {
            runBlocking { api().verificarOtp("REG-1", SolicitudOtp("000000")) }
        }
        assertEquals(422, falla.code())
        assertEquals(ErrorBff("otp_invalido", "x", intentosRestantes = 2), ErrorBff.desde(falla))
    }

    @Test
    fun `un error sin cuerpo del contrato no fabrica un codigo`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("<html>Bad gateway</html>"))
        val falla = assertThrows(HttpException::class.java) {
            runBlocking { api().iniciarSesion(Credenciales("a@b.co", "x")) }
        }
        assertEquals(503, falla.code())
        assertNull(ErrorBff.desde(falla))
    }

    @Test
    fun `una respuesta invalida falla en lugar de fabricar estado`() {
        server.enqueue(json(200, """{"id":"KYC-1"}"""))
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            runBlocking { api().obtenerVerificacionIdentidad() }
        }
    }

    @Test
    fun `listar polizas lee resumen, vigencia y estado`() = runBlocking {
        server.enqueue(
            json(
                200,
                """[{"numero":"POL-2026-000101","producto":"auto","inicio":"2026-09-26",
                "fin":"2027-09-26","estado":"vigente","coberturas":[]}]""",
            ),
        )
        val polizas = api(token = "abc").listarPolizas()
        val esperada = PolizaResumen("POL-2026-000101", "auto", "2026-09-26", "2027-09-26", EstadoPoliza.VIGENTE)
        assertEquals(listOf(esperada), polizas)
        assertEquals("/api/v1/movil/polizas", server.takeRequest().path)
    }

    @Test
    fun `reportar un siniestro envia la clave de idempotencia y el monto como Dinero`() = runBlocking {
        server.enqueue(
            json(
                201,
                """{"id":"SIN-1","referencia":"SIN-2026-000123","numero_poliza":"POL-2026-000101",
                "tipo_evento":"hurto","ocurrido_el":"2026-10-20","descripcion":"Me robaron el celular",
                "monto_estimado":{"valor":1500000,"moneda":"COP"},"estado":"reportado",
                "siguiente_paso":"adjuntar_evidencia","canal":"movil","reportado_en":"2026-10-26T15:00:00Z"}""",
            ),
        )
        val solicitud = SolicitudSiniestro(
            numeroPoliza = "POL-2026-000101",
            tipoEvento = TipoEventoSiniestro.HURTO,
            ocurridoEl = "2026-10-20",
            descripcion = "Me robaron el celular",
            montoEstimado = Dinero.pesos(1_500_000),
        )
        val siniestro = api(token = "abc").reportarSiniestro("6f1c2b0e-8a4d-4f7e-9b1a-2c3d4e5f6a7b", solicitud)
        assertEquals("SIN-2026-000123", siniestro.referencia)
        assertEquals(SiguientePaso.ADJUNTAR_EVIDENCIA, siniestro.siguientePaso)
        assertEquals(Dinero(1_500_000, "COP"), siniestro.montoEstimado)
        val request = server.takeRequest()
        assertEquals("/api/v1/movil/siniestros", request.path)
        assertEquals("6f1c2b0e-8a4d-4f7e-9b1a-2c3d4e5f6a7b", request.getHeader("Idempotency-Key"))
        val cuerpo = request.body.readUtf8()
        assertTrue(cuerpo.contains(""""tipo_evento":"hurto""""))
        assertTrue(cuerpo.contains(""""ocurrido_el":"2026-10-20""""))
        assertTrue(cuerpo.contains(""""monto_estimado":{"valor":1500000,"moneda":"COP"}"""))
    }

    @Test
    fun `un evento fuera de vigencia trae las fechas de la poliza`() {
        server.enqueue(
            json(
                409,
                """{"codigo":"evento_fuera_de_vigencia","mensaje":"x","vigente_desde":"2025-09-26",
                "vigente_hasta":"2026-09-26"}""",
            ),
        )
        val falla = assertThrows(HttpException::class.java) {
            runBlocking {
                api().reportarSiniestro(
                    "6f1c2b0e-8a4d-4f7e-9b1a-2c3d4e5f6a7b",
                    SolicitudSiniestro("POL-1", TipoEventoSiniestro.OTRO, "2026-10-20", "Descripción larga"),
                )
            }
        }
        val error = ErrorBff.desde(falla)
        assertEquals("2025-09-26", error?.vigenteDesde)
        assertEquals("2026-09-26", error?.vigenteHasta)
    }

    @Test
    fun `los secretos no aparecen al imprimir solicitudes ni sesiones`() {
        val sesion = Sesion("acceso-secreto", "refresco-secreto", "2026-10-17T10:15:00Z",
            Cliente("CLI-1", "Ana", "a@b.co", EstadoIdentidad.VERIFICADA))
        val impresos = listOf(
            solicitud.toString(),
            Credenciales("a@b.co", "Secreta123").toString(),
            SolicitudRenovacion("refresco-secreto").toString(),
            sesion.toString(),
        )
        impresos.forEach { texto ->
            assertFalse(texto, texto.contains("Secreta123") || texto.contains("secreto"))
        }
    }

    @Test
    fun `una respuesta exitosa con formato inesperado llega como falla y no como excepcion`() = runBlocking {
        server.enqueue(json(200, """{"token_acceso":"ACC","estado":"desconocido"}"""))
        val resultado = llamarBff { api().iniciarSesion(Credenciales("ana@ejemplo.co", "Solventa2026")) }
        assertEquals(Resultado.Falla(http = HTTP_RESPUESTA_ILEGIBLE, codigo = null), resultado)
    }
}
