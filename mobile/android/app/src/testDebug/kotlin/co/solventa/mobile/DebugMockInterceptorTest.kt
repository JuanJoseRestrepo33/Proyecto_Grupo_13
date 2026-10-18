package co.solventa.mobile

import co.solventa.mobile.mock.MockBffMovil
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugMockInterceptorTest {
    private val client = OkHttpClient.Builder().addInterceptor(DebugMockInterceptor(MockBffMovil())).build()

    @Test
    fun `rutas fuera del prefijo del BFF fallan localmente sin acceso de red`() {
        val request = Request.Builder().url("https://otro-host.invalid/sesion").build()
        client.newCall(request).execute().use { response ->
            assertEquals(404, response.code)
            assertEquals("application", response.body?.contentType()?.type)
            assertTrue(response.body?.string().orEmpty().contains("ruta_no_simulada"))
        }
    }

    @Test
    fun `el prefijo del BFF se descuenta antes de enrutar`() {
        val request = Request.Builder().url(BuildConfig.BASE_URL + "sesion").build()
        client.newCall(request).execute().use { response ->
            assertEquals(401, response.code)
            assertTrue(response.body?.string().orEmpty().contains("no_autenticado"))
        }
    }
}
