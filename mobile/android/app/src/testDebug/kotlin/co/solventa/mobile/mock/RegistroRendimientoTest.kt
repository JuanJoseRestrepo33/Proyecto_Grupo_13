package co.solventa.mobile.mock

import co.solventa.core.network.NetworkClient
import co.solventa.core.network.SolicitudOtp
import co.solventa.core.network.SolicitudRegistro
import co.solventa.core.network.TipoDocumento
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.DebugMockInterceptor
import kotlin.system.measureNanoTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

// MOB-F01: el registro (crear la cuenta y verificar el correo) responde con p95 ≤ 3 s contra el mock.
class RegistroRendimientoTest {
    private val api = NetworkClient.create(BuildConfig.BASE_URL, listOf(DebugMockInterceptor(MockBffMovil())))

    @Test
    fun `el registro completo tiene p95 de 3 segundos o menos`() = runBlocking {
        val duraciones = (1..MUESTRAS).map { n ->
            measureNanoTime {
                val registro = api.crearRegistro(
                    SolicitudRegistro(
                        nombreCompleto = "Cliente $n",
                        tipoDocumento = TipoDocumento.CEDULA_CIUDADANIA,
                        numeroDocumento = (1_000_000_000L + n).toString(),
                        correo = "cliente$n@ejemplo.co",
                        celular = "3001234567",
                        contrasena = "Clave2026",
                        versionTerminos = "2026-09",
                    ),
                )
                api.verificarOtp(registro.id, SolicitudOtp(CODIGO_OTP))
            } / NANOS_POR_MS
        }.sorted()
        val p95 = duraciones[(duraciones.size * 95 / 100) - 1]
        assertTrue("p95 = $p95 ms", p95 <= LIMITE_MS)
    }

    private companion object {
        const val MUESTRAS = 100
        const val NANOS_POR_MS = 1_000_000L
        const val LIMITE_MS = 3_000L
    }
}
