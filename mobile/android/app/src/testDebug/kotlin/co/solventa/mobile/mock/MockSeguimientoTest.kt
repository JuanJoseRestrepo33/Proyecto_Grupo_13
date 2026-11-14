package co.solventa.mobile.mock

import co.solventa.core.network.BffApi
import co.solventa.core.network.Credenciales
import co.solventa.core.network.ErrorBff
import co.solventa.core.network.EstadoSiniestro
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.SolicitudSiniestro
import co.solventa.core.network.TipoEventoSiniestro
import co.solventa.core.network.parteArchivo
import co.solventa.core.network.parteTexto
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.DebugMockInterceptor
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

// Seguimiento (WEB-F06) en el mock, a través del cliente real: BffApi → OkHttp → DebugMockInterceptor.
class MockSeguimientoTest {
    private var token: String? = null
    private val api: BffApi = NetworkClient.create(
        BuildConfig.BASE_URL,
        listOf(DebugMockInterceptor(MockBffMovil { Instant.parse("2026-10-26T15:00:00Z") })),
        tokenAcceso = { token },
    )

    private fun ingresar(correo: String = CORREO_DEMO) = runBlocking {
        token = api.iniciarSesion(Credenciales(correo, CONTRASENA_DEMO)).tokenAcceso
    }

    private fun falla(llamada: suspend () -> Any): Pair<Int, String?> {
        val error = assertThrows(HttpException::class.java) { runBlocking { llamada() } }
        return error.code() to ErrorBff.desde(error)?.codigo
    }

    private fun archivo() = File.createTempFile("factura", ".pdf").apply {
        writeBytes("%PDF-1.4".toByteArray())
        deleteOnExit()
    }

    private suspend fun responder(idSiniestro: String, idRequerimiento: String?, nombre: String = "factura.pdf") =
        api.adjuntarEvidencia(
            idSiniestro,
            parteArchivo(archivo(), nombre, "application/pdf"),
            tomadaEn = null,
            latitud = null,
            longitud = null,
            lugar = null,
            idRequerimiento = parteTexto(idRequerimiento),
        )

    @Test
    fun `la cuenta demo tiene un caso en cada estado, del mas reciente al mas antiguo`() = runBlocking {
        ingresar()
        val lista = api.listarSiniestros()
        assertEquals(EstadoSiniestro.entries.toSet(), lista.map { it.estado }.toSet())
        assertEquals(lista.sortedByDescending { Instant.parse(it.reportadoEn) }, lista)
        assertEquals(setOf("web", "movil"), lista.map { it.canal }.toSet())
    }

    @Test
    fun `la cuenta sin polizas no tiene siniestros`() = runBlocking {
        ingresar(CORREO_SIN_POLIZAS)
        assertTrue(api.listarSiniestros().isEmpty())
    }

    @Test
    fun `el detalle trae historial desde el reporte y los tres estados de requerimiento`() = runBlocking {
        ingresar()
        val pendiente = api.obtenerSiniestro(SINIESTRO_REQUIERE_INFORMACION)
        assertNull(pendiente.historial.first().estadoAnterior)
        assertEquals(EstadoSiniestro.REQUIERE_INFORMACION, pendiente.historial.last().estado)
        assertTrue(pendiente.requerimientos.single().pendiente)

        val atendido = api.obtenerSiniestro(SINIESTRO_APROBADO).requerimientos.single()
        assertFalse(atendido.pendiente)
        assertEquals("2026-09-30T11:45:00Z", atendido.atendidoEn)

        val cerrado = api.obtenerSiniestro(SINIESTRO_RECHAZADO).requerimientos.single()
        assertFalse(cerrado.pendiente)
        assertNull(cerrado.atendidoEn)
    }

    @Test
    fun `un caso sin monto ni cambios de estado`() = runBlocking {
        ingresar()
        val caso = api.obtenerSiniestro(SINIESTRO_REPORTADO)
        assertNull(caso.montoEstimado)
        assertNull(caso.estadoActualizadoEn)
        assertEquals(1, caso.historial.size)
    }

    @Test
    fun `un id inexistente o de otro cliente responde 404 siniestro_no_encontrado`() {
        ingresar()
        assertEquals(404 to "siniestro_no_encontrado", falla { api.obtenerSiniestro(SINIESTRO_INEXISTENTE) })
        ingresar(CORREO_SIN_POLIZAS)
        assertEquals(404 to "siniestro_no_encontrado", falla { api.obtenerSiniestro(SINIESTRO_PAGADO) })
    }

    @Test
    fun `un caso reportado en la sesion tiene el evento del reporte y ningun requerimiento`() = runBlocking {
        ingresar()
        val poliza = api.listarPolizas().first { it.numero.endsWith("101") }.numero
        val solicitud = SolicitudSiniestro(poliza, TipoEventoSiniestro.HURTO, "2026-10-20", "Me robaron el celular")
        val reportado = api.reportarSiniestro(UUID.randomUUID().toString(), solicitud)

        val detalle = api.obtenerSiniestro(reportado.id)

        assertEquals(reportado.referencia, detalle.referencia)
        assertEquals(EstadoSiniestro.REPORTADO, detalle.historial.single().estado)
        assertTrue(detalle.requerimientos.isEmpty())
        assertEquals(reportado.id, api.listarSiniestros().first().id)
    }

    @Test
    fun `responder deja el requerimiento atendido y otra respuesta recibe 409`() = runBlocking {
        ingresar()
        val evidencia = responder(SINIESTRO_REQUIERE_INFORMACION, REQUERIMIENTO_PENDIENTE)

        val requerimiento = api.obtenerSiniestro(SINIESTRO_REQUIERE_INFORMACION).requerimientos.single()
        assertFalse(requerimiento.pendiente)
        assertEquals("2026-10-26T15:00:00Z", requerimiento.atendidoEn)
        assertEquals(evidencia.id, requerimiento.idEvidencia)
        assertEquals(
            409 to "requerimiento_ya_atendido",
            falla { responder(SINIESTRO_REQUIERE_INFORMACION, REQUERIMIENTO_PENDIENTE, "otra.pdf") },
        )
    }

    @Test
    fun `un requerimiento de otro caso responde 404 requerimiento_no_encontrado`() {
        ingresar()
        assertEquals(
            404 to "requerimiento_no_encontrado",
            falla { responder(SINIESTRO_EN_EVALUACION, REQUERIMIENTO_PENDIENTE) },
        )
    }

    @Test
    fun `un caso rechazado o pagado no recibe evidencia`() {
        ingresar()
        assertEquals(409 to "siniestro_cerrado", falla { responder(SINIESTRO_PAGADO, null) })
        assertEquals(409 to "siniestro_cerrado", falla { responder(SINIESTRO_RECHAZADO, REQUERIMIENTO_CERRADO) })
    }

    @Test
    fun `la evidencia sin requerimiento sigue como hoy y no atiende nada`() = runBlocking {
        ingresar()
        responder(SINIESTRO_REQUIERE_INFORMACION, null)
        assertTrue(api.obtenerSiniestro(SINIESTRO_REQUIERE_INFORMACION).requerimientos.single().pendiente)
        assertEquals(1, api.listarEvidencias(SINIESTRO_REQUIERE_INFORMACION).size)
    }

    @Test
    fun `el seguimiento exige sesion`() {
        token = null
        assertEquals(401, falla { api.obtenerSiniestro(SINIESTRO_REPORTADO) }.first)
    }
}
