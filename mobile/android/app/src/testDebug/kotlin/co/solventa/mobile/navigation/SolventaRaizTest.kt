package co.solventa.mobile.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
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
import co.solventa.feature.siniestros.DependenciasSiniestros
import co.solventa.feature.siniestros.borrador.MonitorConectividad
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.DebugMockInterceptor
import co.solventa.mobile.mock.CODIGO_OTP
import co.solventa.mobile.mock.CONTRASENA_DEMO
import co.solventa.mobile.mock.CORREO_DEMO
import co.solventa.mobile.mock.MockBffMovil
import javax.crypto.Cipher
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val almacen = AlmacenEnMemoria()
    private val sesion = GestorSesion(almacen)
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

    /** La red que ve el enviador; el mock no se cae, así que sin red el reporte no sale. */
    private val red = object : MonitorConectividad {
        override val conectado = MutableStateFlow(true)
    }

    private val siniestros = DependenciasSiniestros(
        dependencias.api,
        enviadorDePrueba(dependencias.api, red) { sesion.clienteId },
    )

    private fun mostrar() = compose.setContent {
        SolventaTheme { key(apertura) { SolventaRaiz(dependencias, dependenciasSiniestros = siniestros) } }
    }

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

    private fun entrarConHuellaActiva() {
        mostrar()
        escribir("ingreso-correo", CORREO_DEMO)
        escribir("ingreso-contrasena", CONTRASENA_DEMO)
        compose.onNodeWithText("Iniciar sesión").performClick()
        compose.esperar(hasTestTag("oferta"))
        compose.onNodeWithText("Activar").performClick()
        compose.onNodeWithText("Ir al inicio").performClick()
        compose.onNodeWithTag("screen-home").assertIsDisplayed()
    }

    @Test
    fun `una sesion vencida que no se pudo renovar vuelve a ingreso con la huella activa`() {
        entrarConHuellaActiva()
        compose.runOnIdle { sesion.alVencer(tokenRechazado = false) }
        compose.esperar(hasText("Ingresar con huella o rostro"))
        compose.onNodeWithTag("screen-home").assertDoesNotExist()
        assertNull(sesion.sesion)
        assertFalse("el aviso se consume al navegar", sesion.vencida)
    }

    @Test
    fun `un token de refresco rechazado vuelve a ingreso y pide la contrasena`() {
        entrarConHuellaActiva()
        compose.runOnIdle { sesion.alVencer(tokenRechazado = true) }
        compose.esperar(hasTestTag("ingreso-contrasena"))
        compose.onNodeWithText("Ingresar con huella o rostro").assertDoesNotExist()
        assertNull(almacen.token)
    }

    /** El cliente demo entra y completa el asistente hasta la revisión, sin enviar. */
    private fun hastaRevisionDemo() {
        biometria.disponible = DisponibilidadBiometrica.SIN_SENSOR
        mostrar()
        escribir("ingreso-correo", CORREO_DEMO)
        escribir("ingreso-contrasena", CONTRASENA_DEMO)
        compose.onNodeWithText("Iniciar sesión").performClick()
        compose.esperar(hasTestTag("ir-reportar-siniestro"))
        compose.onNodeWithTag("ir-reportar-siniestro").performClick()

        compose.esperar(hasTestTag("poliza-POL-2026-001101"))
        compose.onNodeWithTag("poliza-POL-2026-001101").performClick()
        compose.onNodeWithTag("continuar").performScrollTo().performClick()
        compose.onNodeWithText("Hurto").performScrollTo().performClick()
        compose.onNodeWithTag("abrir-fecha").performScrollTo().performClick()
        compose.onAllNodes(diaDelMes(1) and hasClickAction()).onFirst().performClick()
        compose.onNodeWithTag("aceptar-fecha").performClick()
        escribir("campo-descripcion", "Me robaron el celular en el bus")
        compose.onNodeWithTag("continuar").performScrollTo().performClick()
        compose.onNodeWithTag("continuar").performScrollTo().performClick()
    }

    @Test
    fun `sin red el reporte queda guardado y al volver la red inicio muestra la referencia`() {
        hastaRevisionDemo()
        compose.runOnIdle { red.conectado.value = false }
        compose.onNodeWithTag("revision-sin-conexion").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("enviar").performScrollTo().performClick()
        compose.esperar(hasTestTag("guardado-sin_conexion"))
        compose.onNodeWithText("Volver al inicio").performScrollTo().performClick()

        compose.esperar(hasText("Reporte pendiente de envío"))
        compose.onNodeWithText("Esperando conexión").assertIsDisplayed()
        compose.onNodeWithText("Hurto").assertIsDisplayed()
        compose.runOnIdle { red.conectado.value = true }
        compose.esperar(hasTestTag("referencia-enviada"))
        compose.onNodeWithText("SIN-2026-000001").assertIsDisplayed()
        compose.onNodeWithText("Entendido").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("referencia-enviada").assertDoesNotExist()
    }

    @Test
    fun `con un reporte sin confirmar inicio no ofrece reportar otro`() {
        hastaRevisionDemo()
        // Descripción con #sin-respuesta: el mock lo registra pero la app ve un tiempo agotado.
        compose.onNodeWithContentDescription("Cambiar descripción").performScrollTo().performClick()
        escribir("campo-descripcion", " #sin-respuesta")
        compose.onNodeWithTag("continuar").performScrollTo().performClick()
        compose.onNodeWithTag("continuar").performScrollTo().performClick()
        compose.onNodeWithTag("enviar").performScrollTo().performClick()
        compose.esperar(hasTestTag("guardado-sin_confirmar"))
        compose.onNodeWithText("Volver al inicio").performScrollTo().performClick()
        compose.esperar(hasTestTag("reporte-bloqueado"))
        compose.onNodeWithTag("ir-reportar-siniestro").assertIsNotEnabled()
    }

    @Test
    fun `cerrar sesion con un reporte sin enviar avisa que se conserva`() {
        hastaRevisionDemo()
        compose.runOnIdle { red.conectado.value = false }
        compose.onNodeWithTag("enviar").performScrollTo().performClick()
        compose.esperar(hasTestTag("guardado-sin_conexion"))
        compose.onNodeWithText("Volver al inicio").performScrollTo().performClick()
        compose.onNode(isSelectable() and hasText("Perfil")).performClick()
        compose.onNodeWithTag("cerrar-sesion").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Tienes 1 reporte sin enviar").assertIsDisplayed()
        compose.onNodeWithText("Otra cuenta no podrá verlo", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("confirmar-cerrar-sesion").performClick()
        compose.esperar(hasTestTag("ingreso-contrasena"))
        assertNull(sesion.sesion)
    }

    @Test
    fun `el cliente demo reporta un siniestro desde inicio y ve la referencia`() {
        biometria.disponible = DisponibilidadBiometrica.SIN_SENSOR
        mostrar()
        escribir("ingreso-correo", CORREO_DEMO)
        escribir("ingreso-contrasena", CONTRASENA_DEMO)
        compose.onNodeWithText("Iniciar sesión").performClick()
        compose.esperar(hasTestTag("ir-reportar-siniestro"))
        compose.onNodeWithTag("ir-reportar-siniestro").performClick()

        compose.esperar(hasTestTag("poliza-POL-2026-001101"))
        compose.onNodeWithTag("poliza-POL-2026-001101").performClick()
        compose.onNodeWithTag("continuar").performScrollTo().performClick()
        compose.onNodeWithText("Hurto").performScrollTo().performClick()
        // El día 1 del mes en curso siempre es elegible y cae en la vigencia de la póliza de auto.
        compose.onNodeWithTag("abrir-fecha").performScrollTo().performClick()
        compose.onAllNodes(diaDelMes(1) and hasClickAction()).onFirst().performClick()
        compose.onNodeWithTag("aceptar-fecha").performClick()
        escribir("campo-descripcion", "Me robaron el celular en el bus")
        compose.onNodeWithTag("continuar").performScrollTo().performClick()
        // Evidencia: es opcional.
        compose.onNodeWithTag("continuar").performScrollTo().performClick()
        compose.onNodeWithTag("enviar").performScrollTo().performClick()

        compose.esperar(hasTestTag("referencia"))
        compose.onNodeWithText("SIN-2026-000001").assertIsDisplayed()
        compose.onNodeWithText("Volver al inicio").performScrollTo().performClick()
        compose.onNodeWithTag("screen-home").assertIsDisplayed()
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

/** Celda del calendario de Material: su texto es la fecha completa («jueves, 1 de octubre de 2026»). */
private fun diaDelMes(dia: Int): SemanticsMatcher {
    val patron = Regex("(^|\\D)$dia(\\D|$)")
    return SemanticsMatcher("día $dia del calendario") { nodo ->
        nodo.config.getOrNull(SemanticsProperties.Text).orEmpty().any { patron.containsMatchIn(it.text) }
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
