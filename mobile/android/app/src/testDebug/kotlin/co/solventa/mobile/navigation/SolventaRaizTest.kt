package co.solventa.mobile.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.core.network.EstadoIdentidad
import co.solventa.core.network.NetworkClient
import co.solventa.feature.onboarding.DependenciasOnboarding
import co.solventa.feature.onboarding.biometria.AlmacenToken
import co.solventa.feature.onboarding.biometria.Biometria
import co.solventa.feature.onboarding.biometria.DisponibilidadBiometrica
import co.solventa.feature.onboarding.biometria.PropositoBiometrico
import co.solventa.feature.onboarding.biometria.ResultadoBiometrico
import co.solventa.feature.onboarding.sesion.GestorSesion
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.DebugMockInterceptor
import co.solventa.mobile.mock.CODIGO_OTP
import co.solventa.mobile.mock.CONTRASENA_DEMO
import co.solventa.mobile.mock.CORREO_DEMO
import co.solventa.mobile.mock.MockBffMovil
import javax.crypto.Cipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Recorre MOB-F01 de punta a punta contra el mock del BFF, como en el build debug. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es-w411dp-h914dp")
class SolventaRaizTest {
    @get:Rule
    val compose = createComposeRule()

    private val sesion = GestorSesion()
    private val almacen = AlmacenEnMemoria()
    private val biometria = BiometriaSimulada()
    private val dependencias = DependenciasOnboarding(
        api = NetworkClient.create(
            BuildConfig.BASE_URL,
            listOf(DebugMockInterceptor(MockBffMovil())),
            tokenAcceso = { sesion.tokenAcceso },
        ),
        sesion = sesion,
        almacen = almacen,
        biometria = biometria,
    )

    /** Cambiarlo simula cerrar y volver a abrir la app: se recrea toda la navegación. */
    private var apertura by mutableIntStateOf(0)

    private fun mostrar() = compose.setContent { SolventaTheme { key(apertura) { SolventaRaiz(dependencias) } } }

    private fun escribir(etiqueta: String, texto: String) = compose.onNodeWithTag(etiqueta).performTextInput(texto)

    private fun registrar(documento: String, correo: String) {
        compose.onNodeWithText("Crear cuenta").performClick()
        escribir("campo-nombre", "Juan Pérez")
        escribir("campo-correo", correo)
        escribir("campo-celular", "3109876543")
        compose.onNodeWithText("Continuar").performClick()
        escribir("campo-documento", documento)
        compose.onNodeWithText("Continuar").performClick()
        escribir("campo-contrasena", "Clave2026")
        escribir("campo-confirmacion", "Clave2026")
        compose.onNodeWithTag("terminos").performClick()
        compose.onNodeWithText("Crear cuenta").performClick()
        compose.esperar(hasText("Verifica tu correo"))
        escribir("campo-otp", CODIGO_OTP)
        compose.onNodeWithText("Verificar").performClick()
    }

    @Test
    fun `registro, OTP, KYC aprobada y activacion de la biometria llegan al inicio`() {
        mostrar()
        registrar(documento = "1098765430", correo = "juan@ejemplo.co")
        compose.esperar(hasTestTag("kyc-aprobada"))
        compose.onNodeWithText("Continuar").performClick()

        compose.esperar(hasTestTag("oferta"))
        compose.onNodeWithText("Activar").performClick()
        assertEquals(listOf(PropositoBiometrico.ACTIVAR), biometria.propositos)
        compose.onNodeWithText("Ir al inicio").performClick()

        compose.onNodeWithTag("screen-home").assertIsDisplayed()
        compose.onNodeWithTag("aviso-identidad").assertDoesNotExist()
        assertEquals(EstadoIdentidad.VERIFICADA, sesion.sesion?.cliente?.estadoIdentidad)
        assertEquals("el token guardado es el de la sesión", sesion.sesion?.tokenRefresco, almacen.token)
    }

    @Test
    fun `una verificacion en revision entra al inicio con el aviso de identidad pendiente`() {
        mostrar()
        registrar(documento = "1098765438", correo = "revision@ejemplo.co")
        compose.esperar(hasTestTag("kyc-en-revision"))
        compose.onNodeWithText("Ir al inicio").performClick()
        compose.onNodeWithTag("aviso-identidad").assertIsDisplayed()
        assertNull("no se ofrece biometría sin identidad verificada", almacen.token)
    }

    @Test
    fun `un cliente verificado entra con contrasena y luego con huella`() {
        mostrar()
        escribir("ingreso-correo", CORREO_DEMO)
        escribir("ingreso-contrasena", CONTRASENA_DEMO)
        compose.onNodeWithText("Iniciar sesión").performClick()
        compose.esperar(hasTestTag("oferta"))
        compose.onNodeWithText("Activar").performClick()
        compose.onNodeWithText("Ir al inicio").performClick()
        compose.onNodeWithTag("screen-home").assertIsDisplayed()
        val tokenActivado = almacen.token

        // Nueva apertura de la app: el ingreso ofrece la huella y la renovación rota el token.
        sesion.sesion = null
        compose.runOnIdle { apertura += 1 }
        compose.esperar(hasText("Ingresar con huella o rostro"))
        compose.onNodeWithText("Ingresar con huella o rostro").performClick()
        compose.esperar(hasTestTag("screen-home"))
        assertEquals(listOf(PropositoBiometrico.ACTIVAR, PropositoBiometrico.INGRESAR), biometria.propositos)
        assertEquals(sesion.sesion?.tokenRefresco, almacen.token)
        assertNotEquals("la renovación rota el token", tokenActivado, almacen.token)
    }

    @Test
    fun `sin sensor biometrico el cliente verificado va directo al inicio`() {
        biometria.disponible = DisponibilidadBiometrica.SIN_SENSOR
        mostrar()
        escribir("ingreso-correo", CORREO_DEMO)
        escribir("ingreso-contrasena", CONTRASENA_DEMO)
        compose.onNodeWithText("Iniciar sesión").performClick()
        compose.esperar(hasTestTag("screen-home"))
        compose.onNodeWithTag("oferta").assertDoesNotExist()
    }
}

/** Las llamadas al mock pasan por OkHttp en otro hilo: se espera a que aparezca el resultado. */
private fun ComposeContentTestRule.esperar(matcher: SemanticsMatcher) {
    waitUntil(timeoutMillis = 5_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
}

private class AlmacenEnMemoria : AlmacenToken {
    var token: String? = null
    private val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    override fun hayToken() = token != null

    override fun guardar(token: String): Boolean {
        this.token = token
        return true
    }

    override fun prepararLectura(): Cipher? = cipher.takeIf { hayToken() }

    override fun leer(desbloqueado: Cipher) = token

    override fun borrar() {
        token = null
    }
}

private class BiometriaSimulada : Biometria {
    var disponible = DisponibilidadBiometrica.DISPONIBLE
    val propositos = mutableListOf<PropositoBiometrico>()

    override fun disponibilidad() = disponible

    override fun autenticar(cipher: Cipher, proposito: PropositoBiometrico, alTerminar: (ResultadoBiometrico) -> Unit) {
        propositos += proposito
        alTerminar(ResultadoBiometrico.Desbloqueado(cipher))
    }
}
