package co.solventa.feature.onboarding.comun

import co.solventa.core.network.EstadoIdentidad
import co.solventa.feature.onboarding.fallaBff
import co.solventa.feature.onboarding.sesion.GestorSesion
import co.solventa.feature.onboarding.sesionPrueba
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

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

    @Test
    fun `la sesion actualiza la identidad del cliente en memoria`() {
        val gestor = GestorSesion()
        gestor.actualizarIdentidad(EstadoIdentidad.VERIFICADA)
        assertNull("sin sesión no hay nada que actualizar", gestor.sesion)
        assertFalse(gestor.identidadVerificada)
        gestor.sesion = sesionPrueba()
        gestor.actualizarIdentidad(EstadoIdentidad.VERIFICADA)
        assertTrue(gestor.identidadVerificada)
        assertEquals("ACC-1", gestor.tokenAcceso)
    }
}
