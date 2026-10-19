package co.solventa.feature.onboarding

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.core.network.Cliente
import co.solventa.core.network.EstadoIdentidad
import co.solventa.core.network.EstadoVerificacion
import co.solventa.core.network.MotivoRechazo
import co.solventa.core.network.VerificacionIdentidad
import co.solventa.feature.onboarding.biometria.DisponibilidadBiometrica
import co.solventa.feature.onboarding.biometria.OfertaBiometriaViewModel
import co.solventa.feature.onboarding.biometria.PantallaOfertaBiometria
import co.solventa.feature.onboarding.biometria.PropositoBiometrico
import co.solventa.feature.onboarding.biometria.ResultadoBiometrico
import co.solventa.feature.onboarding.identidad.IdentidadViewModel
import co.solventa.feature.onboarding.identidad.PantallaIdentidad
import co.solventa.feature.onboarding.ingreso.IngresoViewModel
import co.solventa.feature.onboarding.ingreso.PantallaIngreso
import co.solventa.feature.onboarding.otp.OtpViewModel
import co.solventa.feature.onboarding.otp.PantallaOtp
import co.solventa.feature.onboarding.sesion.GestorSesion
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es-w411dp-h914dp")
class PantallasOnboardingTest {
    @get:Rule(order = 0)
    val main = ReglaMain()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val api = BffFalso()
    private val gestor = GestorSesion()
    private val ahora = Instant.parse("2026-10-17T10:00:00Z")

    // --- OTP ---

    // El reloj sigue al reloj virtual de Compose: la cuenta regresiva avanza con mainClock.
    private val reloj = { ahora.plusMillis(compose.mainClock.currentTime) }

    private fun mostrarOtp(reenvioEn: Instant = ahora.plusSeconds(30), alVerificar: (Cliente) -> Unit = {}) {
        val viewModel = OtpViewModel(api, gestor, "REG-1", reenvioEn, reloj)
        compose.setContent { SolventaTheme { PantallaOtp(viewModel, reloj, alVerificar) } }
    }

    @Test
    fun `el OTP muestra la cuenta pendiente y los intentos que quedan tras un error`() {
        api.verificacionOtp = { _, _ -> throw fallaBff(422, "otp_invalido", intentosRestantes = 2) }
        compose.mainClock.autoAdvance = false
        mostrarOtp(reenvioEn = ahora.plusMillis(compose.mainClock.currentTime).plusSeconds(30))
        compose.onNodeWithText("Tu cuenta quedó creada y está pendiente de verificación.").assertIsDisplayed()
        compose.onNodeWithText("Reenviar código en 30 s").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(31_000)
        compose.onNodeWithText("Reenviar código").assertIsEnabled()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("campo-otp").performTextInput("000000")
        compose.onNodeWithText("Verificar").performClick()
        compose.onNodeWithText("Código incorrecto. Intentos restantes: 2.").assertIsDisplayed()
    }

    @Test
    fun `un OTP bloqueado ofrece pedir un codigo nuevo en lugar de verificar`() {
        api.verificacionOtp = { _, _ -> throw fallaBff(409, "otp_bloqueado") }
        api.reenvio = { otp() }
        mostrarOtp(reenvioEn = ahora.minusSeconds(1))
        compose.onNodeWithTag("campo-otp").performTextInput("000000")
        compose.onNodeWithText("Verificar").performClick()
        compose.onNodeWithText("Usaste los 3 intentos y este código se bloqueó.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Tu cuenta sigue activa", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Verificar").assertDoesNotExist()
        compose.onNodeWithText("Pedir un código nuevo").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Te enviamos un código nuevo. El anterior ya no sirve.").assertIsDisplayed()
        compose.onNodeWithText("Verificar").assertIsDisplayed()
    }

    @Test
    fun `un OTP correcto continua con el cliente verificado`() {
        var verificado: Cliente? = null
        api.verificacionOtp = { _, _ -> sesionPrueba() }
        mostrarOtp { verificado = it }
        compose.onNodeWithTag("campo-otp").performTextInput("123456")
        compose.onNodeWithText("Verificar").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(EstadoIdentidad.PENDIENTE_VERIFICACION, verificado?.estadoIdentidad)
    }

    // --- Resultado KYC ---

    private fun mostrarIdentidad(
        verificacion: VerificacionIdentidad,
        alAprobar: () -> Unit = {},
        alInicio: () -> Unit = {},
    ) {
        api.consulta = { verificacion }
        val viewModel = IdentidadViewModel(api, gestor)
        compose.setContent { SolventaTheme { PantallaIdentidad(viewModel, alAprobar, alInicio) } }
    }

    private fun kyc(estado: EstadoVerificacion, motivo: MotivoRechazo? = null, reintento: Boolean? = null) =
        VerificacionIdentidad("KYC-1", estado, motivo, reintento, "2026-10-17T10:00:00Z")

    @Test
    fun `una identidad aprobada continua al siguiente paso`() {
        var aprobada = false
        mostrarIdentidad(kyc(EstadoVerificacion.APROBADA), alAprobar = { aprobada = true })
        compose.onNodeWithText("¡Identidad verificada!").assertIsDisplayed()
        compose.onNodeWithText("Continuar").performClick()
        assertTrue(aprobada)
    }

    @Test
    fun `un rechazo explica el motivo en lenguaje claro y permite reintentar`() {
        mostrarIdentidad(kyc(EstadoVerificacion.RECHAZADA, MotivoRechazo.DATOS_NO_COINCIDEN, reintento = true))
        api.solicitud = { kyc(EstadoVerificacion.EN_REVISION) }
        compose.onNodeWithText("Los datos que ingresaste no coinciden con los de tu documento.").assertIsDisplayed()
        compose.onNodeWithText("datos_no_coinciden", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Intentar de nuevo").performClick()
        compose.onNodeWithTag("kyc-en-revision").assertIsDisplayed()
    }

    @Test
    fun `un rechazo sin reintento lleva a Ayuda y al inicio`() {
        var alInicio = false
        mostrarIdentidad(
            kyc(EstadoVerificacion.RECHAZADA, MotivoRechazo.DOCUMENTO_NO_VALIDO, reintento = false),
            alInicio = { alInicio = true },
        )
        compose.onNodeWithText("No pudimos validar tu documento. Puede estar vencido o el número tener un error.")
            .assertIsDisplayed()
        compose.onNodeWithText("Escríbenos desde Ayuda y una persona del equipo revisará tu caso.").assertIsDisplayed()
        compose.onNodeWithText("Intentar de nuevo").assertDoesNotExist()
        compose.onNodeWithText("Ir al inicio").performClick()
        assertTrue(alInicio)
    }

    @Test
    fun `una revision manual avisa que se notificara el resultado`() {
        mostrarIdentidad(kyc(EstadoVerificacion.EN_REVISION))
        compose.onNodeWithText("Tu verificación está en revisión").assertIsDisplayed()
        compose.onNodeWithText("Te avisaremos", substring = true).assertIsDisplayed()
    }

    @Test
    fun `una verificacion en proceso no deja una carga indefinida y se reconsulta sola`() {
        var consultas = 0
        api.consulta = {
            consultas += 1
            if (consultas == 1) kyc(EstadoVerificacion.EN_PROCESO) else kyc(EstadoVerificacion.APROBADA)
        }
        val viewModel = IdentidadViewModel(api, gestor)
        compose.mainClock.autoAdvance = false
        compose.setContent { SolventaTheme { PantallaIdentidad(viewModel, {}, {}) } }
        compose.onNodeWithTag("kyc-en-proceso").assertIsDisplayed()
        compose.onNodeWithText("Ir al inicio").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(4_000)
        compose.onNodeWithTag("kyc-aprobada").assertIsDisplayed()
    }

    @Test
    fun `un error al consultar la verificacion ofrece reintentar`() {
        api.consulta = { throw java.io.IOException("sin red") }
        val viewModel = IdentidadViewModel(api, gestor)
        compose.setContent { SolventaTheme { PantallaIdentidad(viewModel, {}, {}) } }
        compose.onNodeWithTag("kyc-error").assertIsDisplayed()
        api.consulta = { kyc(EstadoVerificacion.APROBADA) }
        compose.onNodeWithText("Reintentar").performClick()
        compose.onNodeWithTag("kyc-aprobada").assertIsDisplayed()
    }

    // --- Ingreso ---

    private fun mostrarIngreso(
        almacen: AlmacenFalso,
        biometria: BiometriaFalsa = BiometriaFalsa(),
        alIngresar: (Cliente) -> Unit = {},
    ) {
        val dependencias = DependenciasOnboarding(api, gestor, almacen, biometria) { ahora }
        val viewModel = IngresoViewModel(api, gestor, almacen, biometria.disponibilidad())
        compose.setContent { SolventaTheme { PantallaIngreso(viewModel, dependencias, alIngresar, {}) } }
    }

    @Test
    fun `sin biometria activa solo se ofrece la contrasena`() {
        mostrarIngreso(AlmacenFalso())
        compose.onNodeWithText("Iniciar sesión").assertIsDisplayed()
        compose.onNodeWithText("Ingresar con huella o rostro").assertDoesNotExist()
    }

    @Test
    fun `con biometria activa entra con huella y renueva la sesion`() {
        var cliente: Cliente? = null
        val biometria = BiometriaFalsa()
        api.renovacion = { sesionPrueba(EstadoIdentidad.VERIFICADA, refresco = "REF-2") }
        mostrarIngreso(AlmacenFalso("REF-1"), biometria) { cliente = it }
        compose.onNodeWithText("Ingresar con huella o rostro").assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        assertEquals(listOf(PropositoBiometrico.INGRESAR), biometria.propositos)
        assertEquals(EstadoIdentidad.VERIFICADA, cliente?.estadoIdentidad)
    }

    @Test
    fun `tres lecturas fallidas vuelven a la contrasena sin bloquear la cuenta`() {
        val biometria = BiometriaFalsa(resultado = { ResultadoBiometrico.Fallido })
        api.ingreso = { sesionPrueba(EstadoIdentidad.VERIFICADA) }
        var cliente: Cliente? = null
        mostrarIngreso(AlmacenFalso("REF-1"), biometria) { cliente = it }
        compose.onNodeWithText("Ingresar con huella o rostro").performClick()
        compose.onNodeWithText("No reconocimos tu huella o rostro. Ingresa con tu contraseña; tu cuenta sigue activa.")
            .assertIsDisplayed()
        compose.onNodeWithTag("ingreso-correo").performTextInput("ana@ejemplo.co")
        compose.onNodeWithTag("ingreso-contrasena").performTextInput("Solventa2026")
        compose.onNodeWithText("Iniciar sesión").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(EstadoIdentidad.VERIFICADA, cliente?.estadoIdentidad)
    }

    @Test
    fun `un dispositivo sin sensor no ofrece la biometria aunque haya token`() {
        mostrarIngreso(AlmacenFalso("REF-1"), BiometriaFalsa(DisponibilidadBiometrica.SIN_SENSOR))
        compose.onNodeWithText("Ingresar con huella o rostro").assertDoesNotExist()
    }

    // --- Oferta de biometría ---

    @Test
    fun `la oferta explica que implica y activa la biometria tras confirmarla`() {
        gestor.sesion = sesionPrueba(EstadoIdentidad.VERIFICADA, refresco = "REF-5")
        val almacen = AlmacenFalso()
        val biometria = BiometriaFalsa()
        var terminado = false
        val viewModel = OfertaBiometriaViewModel(gestor, almacen)
        compose.setContent { SolventaTheme { PantallaOfertaBiometria(viewModel, biometria) { terminado = true } } }
        compose.onNodeWithText("Solventa no los recibe ni los guarda", substring = true).assertIsDisplayed()
        compose.onNodeWithText("siempre podrás entrar con tu contraseña", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Activar").performClick()
        compose.onNodeWithText("Listo. La próxima vez podrás entrar con tu huella o rostro.").assertIsDisplayed()
        assertEquals("REF-5", almacen.token)
        assertEquals(listOf(PropositoBiometrico.ACTIVAR), biometria.propositos)
        compose.onNodeWithText("Ir al inicio").performClick()
        assertTrue(terminado)
    }

    @Test
    fun `rechazar la oferta continua sin guardar nada`() {
        gestor.sesion = sesionPrueba(EstadoIdentidad.VERIFICADA)
        val almacen = AlmacenFalso()
        var terminado = false
        val viewModel = OfertaBiometriaViewModel(gestor, almacen)
        compose.setContent {
            SolventaTheme { PantallaOfertaBiometria(viewModel, BiometriaFalsa()) { terminado = true } }
        }
        compose.onNodeWithText("Ahora no").performClick()
        assertTrue(terminado)
        assertEquals(null, almacen.token)
    }
}
