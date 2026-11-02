package co.solventa.mobile.mock

import co.solventa.core.network.BffApi
import co.solventa.core.network.Credenciales
import co.solventa.core.network.Dinero
import co.solventa.core.network.ErrorBff
import co.solventa.core.network.EstadoPoliza
import co.solventa.core.network.EstadoSiniestro
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.PolizaResumen
import co.solventa.core.network.SiguientePaso
import co.solventa.core.network.SolicitudSiniestro
import co.solventa.core.network.TipoEventoSiniestro
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.DebugMockInterceptor
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

// Escenarios de siniestros del mock, a través del cliente real: BffApi → OkHttp → DebugMockInterceptor.
class MockSiniestrosTest {
    private var token: String? = null
    private val api: BffApi = NetworkClient.create(
        BuildConfig.BASE_URL,
        listOf(DebugMockInterceptor(MockBffMovil { Instant.parse("2026-10-26T15:00:00Z") })),
        tokenAcceso = { token },
    )

    private fun ingresar(correo: String = CORREO_DEMO) = runBlocking {
        token = api.iniciarSesion(Credenciales(correo, CONTRASENA_DEMO)).tokenAcceso
    }

    private fun polizas(): List<PolizaResumen> = runBlocking { api.listarPolizas() }

    private fun solicitud(poliza: String, fecha: String = "2026-10-20", descripcion: String = "Me robaron el celular") =
        SolicitudSiniestro(poliza, TipoEventoSiniestro.HURTO, fecha, descripcion, Dinero.pesos(800_000))

    private fun falla(llamada: suspend () -> Any): Pair<Int, ErrorBff?> {
        val error = assertThrows(HttpException::class.java) { runBlocking { llamada() } }
        return error.code() to ErrorBff.desde(error)
    }

    private fun Pair<Int, ErrorBff?>.clave() = first to second?.codigo

    @Test
    fun `el cliente demo tiene una poliza vigente y una vencida con fechas relativas a hoy`() {
        ingresar()
        val esperadas = listOf(
            PolizaResumen("POL-2026-001101", "auto", "2026-09-26", "2027-09-26", EstadoPoliza.VIGENTE),
            PolizaResumen("POL-2026-001102", "hogar", "2025-09-26", "2026-09-26", EstadoPoliza.VENCIDA),
        )
        assertEquals(esperadas, polizas())
    }

    @Test
    fun `la cuenta sin polizas no tiene ninguna`() {
        ingresar(CORREO_SIN_POLIZAS)
        assertTrue(polizas().isEmpty())
    }

    @Test
    fun `reportar sobre la poliza vigente devuelve referencia, estado y siguiente paso`() = runBlocking {
        ingresar()
        val siniestro = api.reportarSiniestro(CLAVE, solicitud("POL-2026-001101"))
        assertEquals("SIN-2026-000001", siniestro.referencia)
        assertEquals(EstadoSiniestro.REPORTADO, siniestro.estado)
        assertEquals(SiguientePaso.ADJUNTAR_EVIDENCIA, siniestro.siguientePaso)
        assertEquals("movil", siniestro.canal)
        assertEquals(Dinero(800_000, "COP"), siniestro.montoEstimado)
    }

    @Test
    fun `la misma clave con los mismos datos devuelve el mismo siniestro y con otros datos se rechaza`() = runBlocking {
        ingresar()
        val primero = api.reportarSiniestro(CLAVE, solicitud("POL-2026-001101"))
        val reintento = api.reportarSiniestro(CLAVE, solicitud("POL-2026-001101"))
        assertEquals(primero, reintento)
        assertEquals(
            409 to "clave_idempotencia_en_uso",
            falla { api.reportarSiniestro(CLAVE, solicitud("POL-2026-001101", descripcion = "Otra descripción")) }
                .clave(),
        )
        val otro = api.reportarSiniestro(OTRA_CLAVE, solicitud("POL-2026-001101"))
        assertEquals("SIN-2026-000002", otro.referencia)
    }

    @Test
    fun `una poliza vencida acepta el evento dentro de su vigencia y rechaza uno posterior con sus fechas`() {
        ingresar()
        val dentro = runBlocking { api.reportarSiniestro(CLAVE, solicitud("POL-2026-001102", fecha = "2026-09-01")) }
        assertEquals("POL-2026-001102", dentro.numeroPoliza)
        val (codigo, error) = falla { api.reportarSiniestro(OTRA_CLAVE, solicitud("POL-2026-001102")) }
        assertEquals(409 to "evento_fuera_de_vigencia", codigo to error?.codigo)
        assertEquals("2025-09-26" to "2026-09-26", error?.vigenteDesde to error?.vigenteHasta)
    }

    @Test
    fun `fecha futura, poliza ajena, datos invalidos y clave faltante responden como el BFF`() {
        ingresar()
        val (http, futura) = falla { api.reportarSiniestro(CLAVE, solicitud("POL-2026-001101", fecha = "2026-10-27")) }
        assertEquals(422 to "fecha_futura", http to futura?.codigo)
        assertEquals("ocurrido_el", futura?.campos?.single()?.campo)
        assertEquals(404 to "poliza_no_encontrada", falla { api.reportarSiniestro(CLAVE, solicitud("POL-X")) }.clave())
        val (_, corta) = falla { api.reportarSiniestro(CLAVE, solicitud("POL-2026-001101", descripcion = "Corta")) }
        assertEquals("descripcion", corta?.campos?.single()?.campo)
        val (_, fecha) = falla { api.reportarSiniestro(CLAVE, solicitud("POL-2026-001101", fecha = "2026-02-30")) }
        assertEquals("ocurrido_el", fecha?.campos?.single()?.campo)
        assertEquals(
            422 to "clave_idempotencia_invalida",
            falla { api.reportarSiniestro("no-es-uuid", solicitud("POL-2026-001101")) }.clave(),
        )
    }

    @Test
    fun `con la marca de respuesta perdida el primer envio agota el tiempo y el reintento confirma el mismo`() {
        ingresar()
        val perdida = solicitud("POL-2026-001101", descripcion = "Choque leve $MARCA_RESPUESTA_PERDIDA")
        assertThrows(IOException::class.java) { runBlocking { api.reportarSiniestro(CLAVE, perdida) } }
        val reintento = runBlocking { api.reportarSiniestro(CLAVE, perdida) }
        assertEquals("SIN-2026-000001", reintento.referencia)
        val otro = runBlocking { api.reportarSiniestro(OTRA_CLAVE, solicitud("POL-2026-001101")) }
        assertEquals("SIN-2026-000002", otro.referencia)
    }

    @Test
    fun `polizas y siniestros exigen sesion`() {
        assertEquals(401 to "sesion_no_vigente", falla { api.listarPolizas() }.clave())
        val reporte = falla { api.reportarSiniestro(CLAVE, solicitud("POL-2026-001101")) }
        assertEquals(401 to "sesion_no_vigente", reporte.clave())
    }

    private companion object {
        const val CLAVE = "6f1c2b0e-8a4d-4f7e-9b1a-2c3d4e5f6a7b"
        const val OTRA_CLAVE = "0b9e7c41-3a52-4d8e-a1f0-5c6d7e8f9a0b"
    }
}
