package co.solventa.core.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

class NetworkClientTest {
    private val server = MockWebServer()

    @Before
    fun setUp() { server.start() }

    @After
    fun tearDown() { server.shutdown() }

    @Test
    fun `health respeta prefijo v1 y deserializa campos conocidos`() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"status":"ok","version":"v1","future_field":true}"""),
        )
        val response = NetworkClient.create(server.url("/v1/").toString()).health()
        assertEquals(HealthResponse("ok", "v1"), response)
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("/v1/health", request?.path)
        assertEquals("GET", request?.method)
    }

    @Test
    fun `un error del BFF conserva su codigo HTTP`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        val failure = assertThrows(HttpException::class.java) {
            runBlocking { NetworkClient.create(server.url("/v1/").toString()).health() }
        }
        assertEquals(503, failure.code())
    }

    @Test
    fun `una respuesta invalida falla en lugar de fabricar estado`() {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            runBlocking { NetworkClient.create(server.url("/v1/").toString()).health() }
        }
    }
}
