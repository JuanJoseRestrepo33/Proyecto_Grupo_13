package co.solventa.mobile

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import co.solventa.core.network.NetworkClient

class DebugMockInterceptorTest {
    @Test
    fun `debug consume el fixture sin depender de un BFF disponible`() = runBlocking {
        val interceptor = DebugMockInterceptor {
            File("src/debug/assets/mocks/health.json").readText()
        }
        val response = NetworkClient.create(BuildConfig.BASE_URL, listOf(interceptor)).health()
        assertEquals("ok", response.status)
        assertEquals("mock-v1", response.version)
    }

    @Test
    fun `rutas no simuladas fallan localmente sin acceso de red`() {
        val client = OkHttpClient.Builder().addInterceptor(DebugMockInterceptor { error("No es health") }).build()
        val request = Request.Builder().url(BuildConfig.BASE_URL + "unknown").build()
        client.newCall(request).execute().use { response ->
            assertEquals(404, response.code)
            assertEquals("""{"error":"mock_not_found"}""", response.body?.string())
        }
    }
}
