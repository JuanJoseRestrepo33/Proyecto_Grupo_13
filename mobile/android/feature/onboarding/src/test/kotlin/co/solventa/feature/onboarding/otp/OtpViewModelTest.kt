package co.solventa.feature.onboarding.otp

import co.solventa.core.network.EstadoIdentidad
import co.solventa.feature.onboarding.BffFalso
import co.solventa.feature.onboarding.R
import co.solventa.feature.onboarding.ReglaMain
import co.solventa.feature.onboarding.fallaBff
import co.solventa.feature.onboarding.otp
import co.solventa.feature.onboarding.sesionPrueba
import co.solventa.feature.onboarding.sesion.GestorSesion
import java.io.IOException
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OtpViewModelTest {
    @get:Rule
    val main = ReglaMain()

    private var ahora = Instant.parse("2026-10-17T10:00:00Z")
    private val api = BffFalso()
    private val gestor = GestorSesion()
    private val viewModel = OtpViewModel(api, gestor, "REG-1", ahora.plusSeconds(30)) { ahora }

    private fun rechazarOtpCon(error: Exception) {
        api.verificacionOtp = { _, _ -> throw error }
    }

    private fun intentar(codigo: String) {
        viewModel.cambiarCodigo(codigo)
        viewModel.verificar()
    }

    @Test
    fun `el codigo solo admite seis digitos`() {
        viewModel.cambiarCodigo("12a3-45678")
        assertEquals("123456", viewModel.estado.codigo)
        assertTrue(viewModel.estado.puedeVerificar)
        viewModel.cambiarCodigo("123")
        assertFalse(viewModel.estado.puedeVerificar)
        viewModel.verificar()
        assertTrue("con un código incompleto no se llama al BFF", api.llamadas.isEmpty())
    }

    @Test
    fun `un codigo correcto abre la sesion con la identidad pendiente`() {
        api.verificacionOtp = { _, _ -> sesionPrueba() }
        intentar("123456")
        assertEquals(EstadoIdentidad.PENDIENTE_VERIFICACION, viewModel.estado.verificado?.estadoIdentidad)
        assertEquals("ACC-1", gestor.tokenAcceso)
        viewModel.verificacionAtendida()
        assertNull(viewModel.estado.verificado)
    }

    @Test
    fun `tres codigos errados bloquean el codigo pero no la cuenta`() {
        var restantes = 3
        api.verificacionOtp = { _, _ ->
            restantes -= 1
            throw if (restantes == 0) fallaBff(409, "otp_bloqueado") else fallaBff(422, "otp_invalido", restantes)
        }
        intentar("000001")
        assertEquals(2, viewModel.estado.intentosRestantes)
        assertEquals(R.string.otp_incorrecto, viewModel.estado.error)
        assertEquals("el campo se limpia para el siguiente intento", "", viewModel.estado.codigo)
        intentar("000002")
        intentar("000003")

        assertTrue(viewModel.estado.bloqueado)
        assertEquals(0, viewModel.estado.intentosRestantes)
        assertEquals(R.string.otp_bloqueado, viewModel.estado.error)
        intentar("123456")
        assertEquals("un código bloqueado no se vuelve a enviar", 3, api.llamadas.size)
    }

    @Test
    fun `un codigo vencido pide uno nuevo`() {
        api.verificacionOtp = { _, _ -> throw fallaBff(409, "otp_vencido") }
        intentar("123456")
        assertTrue(viewModel.estado.vencido)
        assertEquals(R.string.otp_vencido, viewModel.estado.error)
    }

    @Test
    fun `otros rechazos y la falta de conexion se explican sin bloquear`() {
        rechazarOtpCon(fallaBff(409, "registro_ya_verificado"))
        intentar("123456")
        assertEquals(R.string.otp_ya_verificado, viewModel.estado.error)
        rechazarOtpCon(fallaBff(404, "registro_no_encontrado"))
        intentar("123456")
        assertEquals(R.string.error_generico, viewModel.estado.error)
        rechazarOtpCon(IOException("sin red"))
        intentar("123456")
        assertEquals(R.string.error_sin_conexion, viewModel.estado.error)
        assertFalse(viewModel.estado.bloqueado)
    }

    @Test
    fun `el reenvio espera el tiempo indicado y luego desbloquea el codigo`() {
        api.verificacionOtp = { _, _ -> throw fallaBff(409, "otp_bloqueado") }
        api.reenvio = { otp(reenvio = "2026-10-17T10:01:00Z") }
        intentar("000000")
        assertEquals(30, viewModel.estado.segundosParaReenvio(ahora))

        viewModel.reenviar()
        assertFalse("antes de tiempo no se llama al BFF", "reenvio" in api.llamadas)

        ahora = ahora.plusSeconds(30)
        viewModel.reenviar()
        assertFalse(viewModel.estado.bloqueado)
        assertEquals(3, viewModel.estado.intentosRestantes)
        assertEquals(R.string.otp_reenviado, viewModel.estado.aviso)
        assertEquals(Instant.parse("2026-10-17T10:01:00Z"), viewModel.estado.reenvioDisponibleEn)
    }

    @Test
    fun `un reenvio prematuro respeta Retry-After`() {
        ahora = ahora.plusSeconds(30)
        api.reenvio = { throw fallaBff(429, "otp_reenvio_prematuro", encabezados = mapOf("Retry-After" to "12")) }
        viewModel.reenviar()
        assertEquals(R.string.otp_espera_reenvio, viewModel.estado.error)
        assertEquals(12, viewModel.estado.segundosParaReenvio(ahora))
    }

    @Test
    fun `un reenvio fallido por otra causa se explica`() {
        ahora = ahora.plusSeconds(30)
        api.reenvio = { throw fallaBff(409, "registro_ya_verificado") }
        viewModel.reenviar()
        assertEquals(R.string.error_generico, viewModel.estado.error)
        api.reenvio = { throw IOException("sin red") }
        viewModel.reenviar()
        assertEquals(R.string.error_sin_conexion, viewModel.estado.error)
    }
}
