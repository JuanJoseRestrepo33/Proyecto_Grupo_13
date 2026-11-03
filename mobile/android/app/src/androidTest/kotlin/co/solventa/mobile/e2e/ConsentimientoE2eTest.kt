package co.solventa.mobile.e2e

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El consentimiento es del cliente, no del canal (MOB-F02, #162): lo que se otorga y revoca en la app,
 * contra el BFF del ambiente, lo ve la web, y lo que se otorga en la web lo ve la app. Tras revocar
 * en la app, la siguiente cotización desde la web sale a tarifa estándar (criterio 4, RC-04).
 *
 * La cuenta de prueba es compartida con el E2E web: el estado inicial se prepara por el BFF web, y la
 * prueba termina con el consentimiento de cotización vigente.
 */
@RunWith(AndroidJUnit4::class)
class ConsentimientoE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val argumentos = InstrumentationRegistry.getArguments()
    private val correo = argumentos.getString("correo").orEmpty()
    private val clave = argumentos.getString("clave").orEmpty()

    @Before
    fun exigirModoReal() {
        assertFalse("La prueba no corre contra el mock: compila con -PBFF_MOCK=false.", BuildConfig.BFF_MOCK)
        assertTrue("Faltan los argumentos correo y clave.", correo.isNotEmpty() && clave.isNotEmpty())
    }

    @Test
    fun otorgarYRevocarEnLaAppSeVeEnLaWeb() {
        val web = BffWeb(BuildConfig.BASE_URL.removeSuffix("movil/") + "web/")
        val cookie = web.iniciarSesion(correo, clave)
        // Preparación: sin consentimiento vigente de cotización, para que la app pueda otorgarlo.
        web.consentimientoVigente(cookie, FINALIDAD)?.let { web.revocar(cookie, it.getString("id")) }

        iniciarSesion()
        abrirConsentimientos()

        // Otorgar en la app (criterios 2 y 3): la web lo ve vigente, con el canal de la app.
        otorgarEnLaApp()
        val otorgado = web.consentimientoVigente(cookie, FINALIDAD)
        assertNotNull("La web no ve vigente el consentimiento otorgado en la app", otorgado)
        assertEquals("movil", otorgado!!.getString("canal"))
        assertEquals("personalizada", web.cotizarAuto(cookie).getString("tipo_tarifa"))

        // Revocar en la app (criterios 2 y 4): la web lo ve revocado y la siguiente cotización es estándar.
        revocarEnLaApp()
        assertNull("La web aún ve vigente el consentimiento revocado", web.consentimientoVigente(cookie, FINALIDAD))
        val revocado = web.consentimientos(cookie).single { it.getString("id") == otorgado.getString("id") }
        assertEquals("revocado", revocado.getString("estado"))
        val cotizacion = web.cotizarAuto(cookie)
        assertEquals("estandar", cotizacion.getString("tipo_tarifa"))
        assertEquals("sin_consentimiento", cotizacion.getString("motivo_tarifa"))

        // Otorgar en la web (criterio 2, al revés): la app lo muestra vigente al volver a consultar.
        web.otorgar(cookie, FINALIDAD)
        compose.onNodeWithTag("boton-volver").performClick()
        abrirConsentimientos()
        esperar("boton-revocar-$FINALIDAD")
    }

    private fun iniciarSesion() {
        esperar("ingreso-correo")
        compose.onNodeWithTag("ingreso-correo").performTextInput(correo)
        compose.onNodeWithTag("ingreso-contrasena").performTextInput(clave)
        conReintento(listo = { hay("ir-reportar-siniestro") || hay("oferta") }) {
            compose.onNodeWithText("Iniciar sesión").performScrollTo().performClick()
        }
        // Solo aparece en un dispositivo con huella registrada.
        if (hay("oferta")) {
            compose.onNodeWithText("Ahora no").performScrollTo().performClick()
            esperar("ir-reportar-siniestro")
        }
    }

    /** Desde Inicio o desde Perfil. Espera a que la tarjeta de cotización tenga su acción. */
    private fun abrirConsentimientos() {
        esperar { hay("ir-consentimiento") || hay("ir-reportar-siniestro") }
        if (!hay("ir-consentimiento")) compose.onNodeWithText("Perfil").performClick()
        esperar("ir-consentimiento")
        compose.onNodeWithTag("ir-consentimiento").performClick()
        esperar { ACCIONES.any { hay("boton-$it-$FINALIDAD") } }
    }

    private fun otorgarEnLaApp() {
        esperar("boton-otorgar-$FINALIDAD")
        tocar("boton-otorgar-$FINALIDAD")
        esperar("boton-confirmar-otorgar")
        // Ninguna entidad viene marcada (criterio 3): se elige una y se acepta la versión del texto legal.
        compose.onAllNodes(etiquetaQueEmpiezaPor("entidad-")).onFirst().performClick()
        compose.onNodeWithTag("check-version").performClick()
        compose.onNodeWithTag("boton-confirmar-otorgar").performClick()
        esperar("boton-revocar-$FINALIDAD")
    }

    private fun revocarEnLaApp() {
        tocar("boton-revocar-$FINALIDAD")
        esperar("advertencia-cotizacion")
        compose.onNodeWithTag("boton-confirmar-revocar").performClick()
        esperar("boton-otorgar-$FINALIDAD")
    }

    private fun etiquetaQueEmpiezaPor(prefijo: String) = SemanticsMatcher("etiqueta $prefijo…") { nodo ->
        nodo.config.getOrNull(SemanticsProperties.TestTag).orEmpty().startsWith(prefijo)
    }

    /** Un emulador recién encendido pierde la red unos segundos: la app lo avisa y se vuelve a intentar. */
    private fun conReintento(listo: () -> Boolean, accion: () -> Unit) {
        repeat(INTENTOS) {
            accion()
            esperar { listo() || hay("mensaje") }
            if (listo()) return
            SystemClock.sleep(PAUSA_MS)
            compose.waitForIdle()
            if (listo()) return
        }
        esperar(listo)
    }

    private fun tocar(etiqueta: String) {
        compose.onNodeWithTag(etiqueta).performScrollTo().performClick()
    }

    private fun hay(etiqueta: String) = compose.onAllNodesWithTag(etiqueta).fetchSemanticsNodes().isNotEmpty()

    private fun esperar(etiqueta: String) = esperar { hay(etiqueta) }

    // Las llamadas al BFF no cuentan como trabajo pendiente para Compose: hay que esperar lo que se ve.
    private fun esperar(condicion: () -> Boolean) = compose.waitUntil(ESPERA_MS, condicion)

    private companion object {
        const val FINALIDAD = "cotizacion"
        const val ESPERA_MS = 60_000L
        const val INTENTOS = 10
        const val PAUSA_MS = 3_000L
        val ACCIONES = listOf("otorgar", "revocar", "renovar")
    }
}
