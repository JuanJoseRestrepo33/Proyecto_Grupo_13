package co.solventa.core.network

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

private fun fallaBff(
    http: Int,
    codigo: String,
    intentosRestantes: Int? = null,
    encabezados: Map<String, String> = emptyMap(),
): HttpException {
    val error = ErrorBff(codigo, "prueba", emptyList(), intentosRestantes)
    val cuerpo = NetworkClient.json.encodeToString(ErrorBff.serializer(), error)
    val crudo = okhttp3.Response.Builder()
        .code(http)
        .message("Prueba")
        .protocol(Protocol.HTTP_1_1)
        .request(Request.Builder().url("https://bff.prueba/api/v1/movil/").build())
        .apply { encabezados.forEach { (nombre, valor) -> header(nombre, valor) } }
        .build()
    return HttpException(Response.error<Any>(cuerpo.toResponseBody("application/json".toMediaType()), crudo))
}

class ResultadoTest {
    @Test
    fun `una respuesta exitosa llega como Exito`() = runBlocking {
        assertEquals(Resultado.Exito(7), llamarBff { 7 })
    }

    @Test
    fun `un error del contrato conserva codigo, intentos y Retry-After`() = runBlocking {
        val retryAfter = mapOf("Retry-After" to "9")
        val error = fallaBff(429, "otp_reenvio_prematuro", intentosRestantes = 1, encabezados = retryAfter)
        val falla = llamarBff<Int> { throw error } as Resultado.Falla
        assertEquals(429, falla.http)
        assertEquals("otp_reenvio_prematuro", falla.codigo)
        assertEquals(1, falla.error?.intentosRestantes)
        assertEquals(9L, falla.reintentarEnSegundos)
    }

    @Test
    fun `un error sin cuerpo del contrato no inventa un codigo`() = runBlocking {
        val crudo = Response.error<Any>(502, "<html>proxy</html>".toResponseBody("text/html".toMediaType()))
        val falla = llamarBff<Int> { throw HttpException(crudo) } as Resultado.Falla
        assertEquals(502, falla.http)
        assertNull(falla.codigo)
        assertNull(falla.reintentarEnSegundos)
    }

    @Test
    fun `un fallo de red se reporta como sin conexion`() = runBlocking {
        assertEquals(Resultado.SinConexion, llamarBff<Int> { throw IOException("sin red") })
    }
}
