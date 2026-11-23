package co.solventa.mobile.mock

import co.solventa.core.network.BffApi
import co.solventa.core.network.Credenciales
import co.solventa.core.network.Dinero
import co.solventa.core.network.ErrorBff
import co.solventa.core.network.EstadoPoliza
import co.solventa.core.network.NetworkClient
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.DebugMockInterceptor
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

// Escenarios de pólizas del mock (WEB-F04), a través del cliente real: BffApi → OkHttp → DebugMockInterceptor.
class MockPolizasTest {
    private var token: String? = null
    private val api: BffApi = NetworkClient.create(
        BuildConfig.BASE_URL,
        listOf(DebugMockInterceptor(MockBffMovil { Instant.parse("2026-11-15T15:00:00Z") })),
        tokenAcceso = { token },
    )

    private fun ingresar(correo: String = CORREO_DEMO) = runBlocking {
        token = api.iniciarSesion(Credenciales(correo, CONTRASENA_DEMO)).tokenAcceso
    }

    private fun falla(llamada: suspend () -> Any): Pair<Int, String?> {
        val error = assertThrows(HttpException::class.java) { runBlocking { llamada() } }
        return error.code() to ErrorBff.desde(error)?.codigo
    }

    @Test
    fun `la cuenta demo tiene una vigente completa, una por iniciar, una sin datos y una vencida`() = runBlocking {
        ingresar()
        val polizas = api.listarPolizas()

        assertEquals(
            listOf("POL-2026-001101", "POL-2026-001103", "POL-2026-001104", "POL-2026-001102"),
            polizas.map { it.numero },
        )
        assertEquals(
            listOf(EstadoPoliza.VIGENTE, EstadoPoliza.POR_INICIAR, EstadoPoliza.VIGENTE, EstadoPoliza.VENCIDA),
            polizas.map { it.estado },
        )
        val completa = polizas.first()
        assertEquals("Seguro de Auto — Plan Full", completa.nombreProducto)
        assertEquals(Dinero.pesos(185_000), completa.primaMensual)
        assertEquals("/api/v1/movil/polizas/POL-2026-001101/documento", completa.documentoUrl)
        val sinDatos = polizas.first { it.numero == "POL-2026-001104" }
        assertNull(sinDatos.nombreProducto)
        assertNull(sinDatos.primaMensual)
        assertNull(sinDatos.documentoUrl)
    }

    @Test
    fun `el detalle de la completa trae coberturas, deducible, valor asegurado y emision`() = runBlocking {
        ingresar()
        val poliza = api.obtenerPoliza("POL-2026-001101")

        assertEquals(3, poliza.coberturas.size)
        assertEquals("5% del valor asegurado", poliza.deducible)
        assertEquals("$60.000.000", poliza.valorAsegurado)
        assertEquals("2026-10-15T17:00:00Z", poliza.emitidaEn)
    }

    @Test
    fun `la poliza sin datos no trae condiciones, coberturas ni documento`() {
        ingresar()
        val poliza = runBlocking { api.obtenerPoliza("POL-2026-001104") }

        assertTrue(poliza.coberturas.isEmpty())
        assertNull(poliza.deducible)
        assertNull(poliza.valorAsegurado)
        assertNull(poliza.emitidaEn)
        assertEquals(404 to "documento_no_disponible", falla { api.descargarDocumentoPoliza("POL-2026-001104") })
    }

    @Test
    fun `el documento de la completa es un PDF`() = runBlocking {
        ingresar()
        val pdf = api.descargarDocumentoPoliza("POL-2026-001101").use { it.bytes() }

        assertTrue(String(pdf).startsWith("%PDF-1.4"))
        assertTrue(String(pdf).trimEnd().endsWith("%%EOF"))
        assertTrue("POL-2026-001101" in String(pdf))
    }

    @Test
    fun `un numero inexistente o de otro cliente responde 404 poliza_no_encontrada`() {
        ingresar()
        assertEquals(404 to "poliza_no_encontrada", falla { api.obtenerPoliza(POLIZA_INEXISTENTE) })
        assertEquals(404 to "poliza_no_encontrada", falla { api.obtenerPoliza("POL-2026-002101") })
        assertEquals(404 to "poliza_no_encontrada", falla { api.descargarDocumentoPoliza(POLIZA_INEXISTENTE) })
    }

    @Test
    fun `la cuenta sin polizas no tiene ninguna y sin sesion responde 401`() {
        assertEquals(401 to "sesion_no_vigente", falla { api.obtenerPoliza("POL-2026-001101") })
        ingresar(CORREO_SIN_POLIZAS)
        assertTrue(runBlocking { api.listarPolizas() }.isEmpty())
        assertNotNull(falla { api.obtenerPoliza("POL-2026-002101") })
    }
}
