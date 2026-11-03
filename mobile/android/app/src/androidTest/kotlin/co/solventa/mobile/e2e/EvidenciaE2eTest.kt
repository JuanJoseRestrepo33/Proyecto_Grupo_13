package co.solventa.mobile.e2e

import android.Manifest
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
 * Reporte con foto sin duplicar el siniestro (MOB-F05, #179): la foto se toma con la cámara de la app
 * (la cámara emulada del emulador), se envía con el reporte contra el BFF del ambiente, y la web ve un
 * solo siniestro con una sola evidencia. Solo mira testTag y estado, para no imprimir datos de la cuenta.
 */
@RunWith(AndroidJUnit4::class)
class EvidenciaE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val argumentos = InstrumentationRegistry.getArguments()
    private val correo = argumentos.getString("correo").orEmpty()
    private val clave = argumentos.getString("clave").orEmpty()
    private var paso = "preparación"

    @Before
    fun exigirModoReal() {
        assertFalse("La prueba no corre contra el mock: compila con -PBFF_MOCK=false.", BuildConfig.BFF_MOCK)
        assertTrue("Faltan los argumentos correo y clave.", correo.isNotEmpty() && clave.isNotEmpty())
        // Los diálogos de permisos son del sistema: se conceden antes, como si el cliente ya hubiera aceptado.
        val instrumentacion = InstrumentationRegistry.getInstrumentation()
        val paquete = instrumentacion.targetContext.packageName
        PERMISOS.forEach { instrumentacion.uiAutomation.grantRuntimePermission(paquete, it) }
    }

    @Test
    fun reporteConFotoDejaUnSiniestroConSuEvidencia() {
        val marca = "e2e-${UUID.randomUUID()}"

        paso = "iniciar sesión"
        iniciarSesion()
        paso = "llenar el reporte"
        val poliza = llenarInformacion("Siniestro con foto ($marca).")
        paso = "tomar la foto"
        tomarFoto()
        paso = "enviar el reporte"
        avanzarHastaEnviar()
        // Reintentar es seguro: el asistente reutiliza la misma Idempotency-Key, y la web debe ver uno solo.
        conReintento(listo = { hay("referencia") }) { tocar("enviar") }
        val referencia = textoDe("referencia")
        assertTrue("Referencia inesperada: $referencia", REFERENCIA.matches(referencia))
        paso = "ver la foto entregada"
        esperar("evidencia-0")

        val web = BffWeb(BuildConfig.BASE_URL.removeSuffix("movil/") + "web/")
        val cookie = web.iniciarSesion(correo, clave)
        val encontrados = web.siniestros(cookie).filter { it.getString("descripcion").contains(marca) }
        assertEquals("Siniestros con la marca de esta ejecución", 1, encontrados.size)
        val siniestro = encontrados.single()
        assertEquals(referencia, siniestro.getString("referencia"))
        assertEquals(poliza, siniestro.getString("numero_poliza"))
        assertEquals("movil", siniestro.getString("canal"))
        val evidencias = web.evidencias(cookie, siniestro.getString("id"))
        assertEquals("Evidencias del siniestro", 1, evidencias.size)
        assertEquals("image/jpeg", evidencias.single().getString("tipo_contenido"))
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

    /** Póliza e información; devuelve el número de la póliza, que el núcleo deriva de la semilla. */
    private fun llenarInformacion(descripcion: String): String {
        compose.onNodeWithTag("ir-reportar-siniestro").performClick()
        esperar("la póliza sembrada o reintentar") { hayPoliza() || hayReintentar() }
        if (hayReintentar()) {
            conReintento(listo = { hayPoliza() }) { compose.onNodeWithText(REINTENTAR).performClick() }
        }
        val nodo = compose.onAllNodes(polizaSembrada()).onFirst()
        val poliza = nodo.fetchSemanticsNode().config[SemanticsProperties.TestTag].removePrefix("poliza-")
        nodo.performScrollTo().performClick()
        tocar("continuar")
        esperar("tipo-accidente_vehiculo")
        tocar("tipo-accidente_vehiculo")
        elegirHoy()
        compose.onNodeWithTag("campo-descripcion").performScrollTo().performTextInput(descripcion)
        tocar("continuar")
        esperar("paso-evidencia")
        return poliza
    }

    /** Cámara de la app: el botón abre la vista previa y «Capturar» guarda la foto como adjunto. */
    private fun tomarFoto() {
        esperar("tomar-foto habilitado") {
            compose.onAllNodes(hasTestTag("tomar-foto") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        tocar("tomar-foto")
        // «Capturar» se habilita cuando CameraX enlazó la cámara. Un solo clic: reintentar podría dejar dos fotos.
        esperar("«$CAPTURAR» habilitado") {
            compose.onAllNodes(hasText(CAPTURAR) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(CAPTURAR).performClick()
        esperar("adjunto-0")
        assertFalse("Se adjuntó más de una foto", hay("adjunto-1"))
    }

    private fun avanzarHastaEnviar() {
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

    /** La semilla da `POL-STG-AUTO` a cualquier cliente; el núcleo la entrega con el sufijo de su id. */
    private fun polizaSembrada() = SemanticsMatcher("póliza $POLIZA del cliente") { nodo ->
        val etiqueta = nodo.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
        etiqueta == "poliza-$POLIZA" || etiqueta.startsWith("poliza-$POLIZA-")
    }

    private fun hayPoliza() = compose.onAllNodes(polizaSembrada()).fetchSemanticsNodes().isNotEmpty()

    private fun hayReintentar() = compose.onAllNodesWithText(REINTENTAR).fetchSemanticsNodes().isNotEmpty()

    /** Un emulador recién encendido pierde la red unos segundos: la app lo avisa y se vuelve a intentar. */
    private fun conReintento(listo: () -> Boolean, accion: () -> Unit) {
        repeat(INTENTOS) {
            accion()
            esperar("respuesta o mensaje") { listo() || hay("mensaje") }
            if (listo()) return
            SystemClock.sleep(PAUSA_MS)
            compose.waitForIdle()
            if (listo()) return
        }
        esperar("el resultado tras reintentar", listo)
    }

    private fun textoDe(etiqueta: String): String =
        compose.onNodeWithTag(etiqueta).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")

    private fun tocar(etiqueta: String) {
        compose.onNodeWithTag(etiqueta).performScrollTo().performClick()
    }

    private fun hay(etiqueta: String) = compose.onAllNodesWithTag(etiqueta).fetchSemanticsNodes().isNotEmpty()

    private fun esperar(etiqueta: String) = esperar(etiqueta) { hay(etiqueta) }

    /**
     * Las llamadas al BFF no cuentan como trabajo pendiente para Compose: hay que esperar lo que se ve.
     * Si no llega, el error dice el paso, lo que se esperaba y las etiquetas en pantalla (nunca textos).
     */
    private fun esperar(que: String, condicion: () -> Boolean) {
        try {
            compose.waitUntil(ESPERA_MS, condicion)
        } catch (error: ComposeTimeoutException) {
            val segundos = ESPERA_MS / 1000
            throw AssertionError("Paso «$paso»: sin «$que» tras $segundos s. En pantalla: ${etiquetas()}", error)
        }
    }

    private fun etiquetas(): List<String> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag] }
            .distinct()
            .sorted()

    private companion object {
        const val POLIZA = "POL-STG-AUTO"
        const val CAPTURAR = "Capturar"
        const val REINTENTAR = "Reintentar"
        const val ESPERA_MS = 60_000L
        const val PASOS_MAXIMOS = 5
        const val INTENTOS = 10
        const val PAUSA_MS = 3_000L
        val REFERENCIA = Regex("SIN-\\d{4}-\\d{6}")
        val PERMISOS = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
