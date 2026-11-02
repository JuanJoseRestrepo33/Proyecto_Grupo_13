package co.solventa.mobile.e2e

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/**
 * El reporte es uno aunque tenga dos canales (WEB-F05, #172): lo que se reporta en la app, contra el
 * BFF del ambiente, se ve en la web con la misma referencia. Las credenciales llegan por argumentos de
 * instrumentación; la comprobación solo mira testTag y estado, para no imprimir datos de la cuenta.
 */
@RunWith(AndroidJUnit4::class)
class ReporteSiniestroE2eTest {
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

    // Dos reportes seguidos: los siniestros de la cuenta se acumulan y cada uno debe encontrar solo el suyo.
    @Test
    fun primerReporteLlegaALaWeb() = reportarYComprobarEnLaWeb()

    @Test
    fun segundoReporteLlegaALaWebConOtraReferencia() = reportarYComprobarEnLaWeb()

    private fun reportarYComprobarEnLaWeb() {
        val marca = "e2e-${UUID.randomUUID()}"
        val fecha = LocalDate.now()

        iniciarSesion()
        llenarReporte("Siniestro de prueba ($marca).")
        val referencia = enviarYLeerConfirmacion()

        val web = BffWeb(BuildConfig.BASE_URL.removeSuffix("movil/") + "web/")
        val cookie = web.iniciarSesion(correo, clave)
        val encontrados = web.siniestros(cookie).filter { it.getString("descripcion").contains(marca) }
        assertEquals("Siniestros con la marca de esta ejecución", 1, encontrados.size)
        val siniestro = encontrados.single()
        assertEquals(referencia, siniestro.getString("referencia"))
        assertEquals(POLIZA, siniestro.getString("numero_poliza"))
        assertEquals("reportado", siniestro.getString("estado"))
        assertEquals("movil", siniestro.getString("canal"))

        val detalle = web.siniestro(cookie, siniestro.getString("id"))
        assertEquals(referencia, detalle.getString("referencia"))
        assertEquals("accidente_vehiculo", detalle.getString("tipo_evento"))
        assertEquals(fecha.toString(), detalle.getString("ocurrido_el"))

        assertTrue("Referencia repetida entre reportes: $referencia", referencias.add(referencia))
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

    private fun llenarReporte(descripcion: String) {
        compose.onNodeWithTag("ir-reportar-siniestro").performClick()
        esperar { hay("poliza-$POLIZA") || hayReintentar() }
        if (hayReintentar()) {
            conReintento(listo = { hay("poliza-$POLIZA") }) { compose.onNodeWithText(REINTENTAR).performClick() }
        }
        tocar("poliza-$POLIZA")
        tocar("continuar")
        esperar("tipo-accidente_vehiculo")
        tocar("tipo-accidente_vehiculo")
        elegirHoy()
        compose.onNodeWithTag("campo-descripcion").performScrollTo().performTextInput(descripcion)
        // Sin fijar cuántos pasos hay: el de evidencia (#137) se inserta antes de la revisión.
        repeat(PASOS_MAXIMOS) {
            if (hay("enviar")) return
            tocar("continuar")
            compose.waitForIdle()
        }
        esperar("enviar")
    }

    private fun elegirHoy() {
        val hoy = LocalDate.now()
        tocar("abrir-fecha")
        esperar("aceptar-fecha")
        compose.onAllNodes(diaDelCalendario(hoy) and isEnabled()).onFirst().performClick()
        compose.onNodeWithTag("aceptar-fecha").performClick()
    }

    /** La celda del día dice la fecha completa en el idioma del dispositivo; número y año no dependen de él. */
    private fun diaDelCalendario(dia: LocalDate): SemanticsMatcher {
        val numero = Regex("\\b${dia.dayOfMonth}\\b")
        return SemanticsMatcher("día ${dia.dayOfMonth} de ${dia.year}") { nodo ->
            val texto = nodo.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ")
            SemanticsActions.OnClick in nodo.config && dia.year.toString() in texto && numero.containsMatchIn(texto)
        }
    }

    private fun enviarYLeerConfirmacion(): String {
        // Reintentar es seguro: el asistente reutiliza la misma Idempotency-Key, y la web debe ver uno solo.
        conReintento(listo = { hay("referencia") }) { tocar("enviar") }
        val referencia = textoDe(compose.onNodeWithTag("referencia"))
        assertTrue("Referencia inesperada: $referencia", REFERENCIA.matches(referencia))
        assertTrue(textoDe(compose.onNodeWithTag("estado")).contains("Reportado"))
        assertTrue(textoDe(compose.onNodeWithTag("siguiente-paso")).isNotBlank())
        return referencia
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

    private fun hayReintentar() = compose.onAllNodesWithText(REINTENTAR).fetchSemanticsNodes().isNotEmpty()

    private fun textoDe(nodo: SemanticsNodeInteraction): String =
        nodo.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")

    private fun tocar(etiqueta: String) {
        compose.onNodeWithTag(etiqueta).performScrollTo().performClick()
    }

    private fun hay(etiqueta: String) = compose.onAllNodesWithTag(etiqueta).fetchSemanticsNodes().isNotEmpty()

    private fun esperar(etiqueta: String) = esperar { hay(etiqueta) }

    // Las llamadas al BFF no cuentan como trabajo pendiente para Compose: hay que esperar lo que se ve.
    private fun esperar(condicion: () -> Boolean) = compose.waitUntil(ESPERA_MS, condicion)

    private companion object {
        const val POLIZA = "POL-STG-AUTO"
        const val ESPERA_MS = 60_000L
        const val PASOS_MAXIMOS = 5
        const val INTENTOS = 10
        const val PAUSA_MS = 3_000L
        const val REINTENTAR = "Reintentar"
        val REFERENCIA = Regex("SIN-\\d{4}-\\d{6}")
        val referencias = mutableSetOf<String>()
    }
}
