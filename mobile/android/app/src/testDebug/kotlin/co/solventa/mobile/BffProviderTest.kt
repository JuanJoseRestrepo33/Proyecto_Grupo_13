package co.solventa.mobile

import android.content.Context
import co.solventa.core.network.HealthResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BffProviderTest {
    @Test
    fun `el proveedor debug conecta assets y cliente sin red`() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        assertEquals(HealthResponse("ok", "mock-v1"), BffProvider.create(context).health())
    }
}
