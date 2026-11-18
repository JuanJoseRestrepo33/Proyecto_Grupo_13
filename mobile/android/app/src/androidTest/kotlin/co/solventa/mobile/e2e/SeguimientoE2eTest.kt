package co.solventa.mobile.e2e

import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.feature.onboarding.R as RIngreso
import co.solventa.feature.siniestros.R
import co.solventa.feature.siniestros.reporte.formatoFechaHora
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
import java.time.ZoneId
import java.util.UUID

/**
 * El cambio de estado llega a la app (WEB-F06, #202): un caso reportado por el BFF web aparece en «Mis
 * siniestros» con la misma referencia; el núcleo lo cambia de estado con la operación interna y, al tocar
 * «Actualizar», la app muestra el estado nuevo en la lista, en el detalle y en el historial.
 *
 * Cada corrida crea su propio caso y lo busca por `id`. Los textos esperados salen de `strings.xml` y las
 * fechas del mismo formateador de la app. Un fallo dice el paso; nunca imprime credenciales ni el token.
 */
@RunWith(AndroidJUnit4::class)
class SeguimientoE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val argumentos = InstrumentationRegistry.getArguments()
    private val correo = argumentos.getString("correo").orEmpty()
    private val clave = argumentos.getString("clave").orEmpty()
    private val urlNucleo = argumentos.getString("nucleo").orEmpty()
    private val tokenInterno = argumentos.getString("tokenInterno").orEmpty()
    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext

    private val web = BffWeb(BuildConfig.BASE_URL.removeSuffix("movil/") + "web/")
    private lateinit var nucleo: NucleoInterno
    private lateinit var cookie: String

    @Before
    fun exigirModoReal() {
        assertFalse("La prueba no corre contra el mock: compila con -PBFF_MOCK=false.", BuildConfig.BFF_MOCK)
        assertTrue("Faltan los argumentos correo y clave.", correo.isNotEmpty() && clave.isNotEmpty())
        // Sin la operación interna no hay cambio de estado: la prueba falla, no se omite.
        assertTrue("Faltan los argumentos nucleo y tokenInterno.", urlNucleo.isNotEmpty() && tokenInterno.isNotEmpty())
        nucleo = NucleoInterno(urlNucleo, tokenInterno)
    }

    @Test
    fun elCambioDeEstadoSeVeEnLaApp() {
        val marca = "e2e-${UUID.randomUUID()}"
        val caso = paso("1-2 · reportar por la web") { reportarPorLaWeb(marca) }
        paso("3 · iniciar sesión y abrir «Mis siniestros»") { abrirMisSiniestros() }
        paso("4 · ver el caso «Reportado» por la web") {
            esperarTarjeta(caso, R.string.estado_reportado)
        }
        paso("5 · cambiar a en_evaluacion en el núcleo") {
            nucleo.cambiarEstado(caso.id, "en_evaluacion")
        }
        paso("6 · actualizar la lista") {
            actualizar()
            esperarTarjeta(caso, R.string.estado_en_evaluacion)
        }
        paso("7 · abrir el detalle") {
            // La acción de abrir está en la columna clicable dentro de la tarjeta, no en la tarjeta.
            compose.onNode(hasAnyAncestor(hasTestTag("siniestro-${caso.id}")) and hasClickAction())
                .performScrollTo()
                .performClick()
            esperar("pantalla-detalle-siniestro")
            esperarInsignia(R.string.estado_en_evaluacion)
            comprobarHistorial(caso, listOf(R.string.seguimiento_evento_reporte, R.string.estado_en_evaluacion))
        }
        val requerimiento = "Factura de la reparación · $marca"
        paso("8 · cambiar a requiere_informacion en el núcleo") {
            nucleo.cambiarEstado(caso.id, "requiere_informacion", listOf(requerimiento))
        }
        paso("9 · actualizar el detalle") {
            actualizar()
            esperarInsignia(R.string.estado_requiere_informacion)
            comprobarHistorial(
                caso,
                listOf(
                    R.string.seguimiento_evento_reporte,
                    R.string.estado_en_evaluacion,
                    R.string.estado_requiere_informacion,
                ),
            )
            comprobarRequerimiento(caso, requerimiento)
            comprobarSiguientePaso()
        }
    }

    /** El caso como lo crea la web, con la póliza de auto vigente que el núcleo derivó para la cuenta. */
    private fun reportarPorLaWeb(marca: String): Caso {
        cookie = web.iniciarSesion(correo, clave)
        val poliza = web.polizas(cookie)
            .filter { it.getString("estado") == "vigente" }
            .map { it.getString("numero") }
            .firstOrNull { it == POLIZA || it.startsWith("$POLIZA-") }
        checkNotNull(poliza) { "La cuenta no tiene vigente la póliza $POLIZA" }
        // El día de Bogotá: nunca es futuro para el núcleo, aunque el emulador esté en UTC.
        val reporte = ReporteWeb(poliza, "accidente_vehiculo", LocalDate.now(BOGOTA), "Siniestro de prueba ($marca).")
        val siniestro = web.reportar(cookie, reporte, UUID.randomUUID())
        val referencia = siniestro.getString("referencia")
        assertTrue("Referencia inesperada: $referencia", REFERENCIA.matches(referencia))
        assertEquals("web", siniestro.getString("canal"))
        return Caso(
            id = siniestro.getString("id"),
            referencia = referencia,
            poliza = siniestro.getString("numero_poliza"),
            reportadoEn = siniestro.getString("reportado_en"),
        )
    }

    private fun abrirMisSiniestros() {
        esperar("el ingreso o Inicio") { hay("ingreso-correo") || hay("ir-mis-siniestros") }
        if (hay("ingreso-correo")) iniciarSesion()
        tocar("ir-mis-siniestros")
        esperar("pantalla-mis-siniestros")
    }

    private fun iniciarSesion() {
        compose.onNodeWithTag("ingreso-correo").performTextInput(correo)
        compose.onNodeWithTag("ingreso-contrasena").performTextInput(clave)
        compose.onNodeWithText(texto(RIngreso.string.ingreso_boton)).performScrollTo().performClick()
        esperar("Inicio") { hay("ir-mis-siniestros") || hay("oferta") }
        // Solo aparece en un dispositivo con huella registrada.
        if (hay("oferta")) {
            compose.onNodeWithText(texto(RIngreso.string.oferta_ahora_no)).performScrollTo().performClick()
            esperar("ir-mis-siniestros")
        }
    }

    /**
     * La tarjeta es un solo control con una descripción accesible (`clearAndSetSemantics`): referencia,
     * estado y canal se comprueban en ella, armada con la misma plantilla de la app.
     */
    private fun esperarTarjeta(caso: Caso, @StringRes estado: Int) {
        val esperada = texto(
            R.string.seguimiento_tarjeta,
            texto(R.string.tipo_accidente_vehiculo),
            texto(estado),
            caso.referencia,
            caso.poliza,
            formatoFechaHora(caso.reportadoEn),
            texto(R.string.seguimiento_canal_web),
        )
        val etiqueta = "siniestro-${caso.id}"
        try {
            compose.waitUntil(ESPERA_MS) {
                fallarSiHayError()
                hay(etiqueta) && descripcion(etiqueta) == esperada
            }
        } catch (error: ComposeTimeoutException) {
            val actual = if (hay(etiqueta)) descripcion(etiqueta) else ""
            val faltan = listOf(
                "la tarjeta del caso" to hay(etiqueta),
                "la referencia" to actual.contains(caso.referencia),
                "el estado ${texto(estado)}" to actual.contains(", ${texto(estado)}. "),
                "el canal ${texto(R.string.seguimiento_canal_web)}" to
                    actual.contains(texto(R.string.seguimiento_canal_web)),
            ).filterNot { it.second }.map { it.first }
            val diferencias = faltan.ifEmpty { listOf("el formato") }
            throw AssertionError("La tarjeta no muestra lo esperado; no coincide: $diferencias", error)
        }
    }

    /** Se espera a que el botón vuelva a estar habilitado: mientras actualiza, no recibe toques. */
    private fun actualizar() {
        esperar("«Actualizar» habilitado") {
            compose.onAllNodes(hasTestTag("actualizar") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("actualizar").assertIsEnabled().performClick()
    }

    /** La insignia está dentro del encabezado, que fusiona su semántica. */
    private fun esperarInsignia(@StringRes estado: Int) {
        val etiqueta = texto(estado)
        val descripcion = texto(R.string.seguimiento_estado, etiqueta)
        esperar("la insignia «$etiqueta»") {
            fallarSiHayError()
            hay("insignia-estado", sinFusionar = true) &&
                textos("insignia-estado", sinFusionar = true).containsAll(listOf(descripcion, etiqueta))
        }
        compose.onNodeWithTag("insignia-estado", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    /** Un evento por cambio, cada uno con su fecha; solo el último es el actual. */
    private fun comprobarHistorial(caso: Caso, nombres: List<Int>) {
        val historial = web.siniestro(cookie, caso.id).getJSONArray("historial")
        assertEquals("Eventos del historial en el BFF", nombres.size, historial.length())
        compose.onNodeWithTag("historial").performScrollTo()
        esperar("${nombres.size} eventos en el historial") {
            compose.onNodeWithTag("historial").onChildren().fetchSemanticsNodes().size == nombres.size
        }
        assertEquals("Eventos resaltados", 1, compose.onAllNodesWithTag("evento-actual").fetchSemanticsNodes().size)
        nombres.forEachIndexed { indice, nombre ->
            val fecha = formatoFechaHora(historial.getJSONObject(indice).getString("ocurrido_en"))
            val actual = indice == nombres.lastIndex
            val etiqueta = if (actual) "evento-actual" else "evento-$indice"
            compose.onNodeWithTag(etiqueta).performScrollTo().assertIsDisplayed()
            val contenido = textos(etiqueta)
            assertTrue("Evento $indice sin su nombre", texto(nombre) in contenido)
            assertTrue("Evento $indice sin su fecha", fecha in contenido)
            assertEquals(
                "Evento $indice resaltado",
                actual,
                texto(R.string.seguimiento_estado_actual) in contenido,
            )
            if (actual) {
                assertTrue(
                    "Evento actual sin su descripción accesible",
                    texto(R.string.seguimiento_evento_actual, texto(nombre), fecha) in contenido,
                )
            }
        }
    }

    private fun comprobarRequerimiento(caso: Caso, descripcion: String) {
        val requerimientos = web.siniestro(cookie, caso.id).getJSONArray("requerimientos")
        val pedido = List(requerimientos.length(), requerimientos::getJSONObject)
            .singleOrNull { it.getString("descripcion") == descripcion }
        checkNotNull(pedido) { "El BFF no tiene el requerimiento de esta corrida" }
        val id = pedido.getString("id")
        compose.onNodeWithTag("requerimientos").performScrollTo().assertIsDisplayed()
        esperar("requerimiento-$id")
        compose.onNodeWithTag("estado-requerimiento-$id", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        assertEquals(
            "Estado del requerimiento",
            listOf(texto(R.string.requerimiento_pendiente)),
            textos("estado-requerimiento-$id", sinFusionar = true),
        )
        compose.onNodeWithTag("responder-$id").performScrollTo().assertIsDisplayed()
        assertTrue("Sin «Responder»", texto(R.string.requerimiento_responder) in textos("responder-$id"))
    }

    private fun comprobarSiguientePaso() {
        compose.onNodeWithTag("resumen").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fila-siguiente-paso").performScrollTo().assertIsDisplayed()
        assertTrue(
            "Siguiente paso distinto de «${texto(R.string.paso_responder)}»",
            texto(R.string.paso_responder) in textos("fila-siguiente-paso"),
        )
    }

    /** «Reintentar» aparece si la consulta falló: la prueba no lo toca, falla en el paso. */
    private fun fallarSiHayError() {
        if (hay("reintentar")) throw AssertionError("La app mostró un error de carga con «Reintentar»")
    }

    /** Ejecuta un paso del recorrido; si falla, el error dice cuál, sin datos de la cuenta. */
    // Cualquier fallo (aserción, red, tiempo) se envuelve igual para nombrar el paso; nada se traga.
    @Suppress("TooGenericExceptionCaught")
    private fun <T> paso(nombre: String, bloque: () -> T): T = try {
        bloque()
    } catch (error: Throwable) {
        throw AssertionError("Paso «$nombre»: ${error.message}. En pantalla: ${etiquetas()}", error)
    }

    private fun descripcion(etiqueta: String): String =
        compose.onNodeWithTag(etiqueta).fetchSemanticsNode().let(::recolectar)
            .filter { it.first == SemanticsProperties.ContentDescription.name }
            .map { it.second }
            .distinct()
            .joinToString("")

    /** Textos y descripciones del nodo y sus descendientes. */
    private fun textos(etiqueta: String, sinFusionar: Boolean = false): List<String> =
        compose.onNodeWithTag(etiqueta, useUnmergedTree = sinFusionar).fetchSemanticsNode()
            .let(::recolectar)
            .map { it.second }
            .distinct()

    private fun recolectar(nodo: SemanticsNode): List<Pair<String, String>> {
        val descripciones = nodo.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            .map { SemanticsProperties.ContentDescription.name to it }
        val textos = nodo.config.getOrNull(SemanticsProperties.Text).orEmpty()
            .map { SemanticsProperties.Text.name to it.text }
        return descripciones + textos + nodo.children.flatMap(::recolectar)
    }

    private fun texto(@StringRes id: Int, vararg argumentos: Any): String = contexto.getString(id, *argumentos)

    private fun tocar(etiqueta: String) {
        compose.onNodeWithTag(etiqueta).performScrollTo().performClick()
    }

    private fun hay(etiqueta: String, sinFusionar: Boolean = false) =
        compose.onAllNodesWithTag(etiqueta, useUnmergedTree = sinFusionar).fetchSemanticsNodes().isNotEmpty()

    private fun esperar(etiqueta: String) = esperar(etiqueta) { hay(etiqueta) }

    // Las llamadas al BFF no cuentan como trabajo pendiente para Compose: hay que esperar lo que se ve.
    private fun esperar(que: String, condicion: () -> Boolean) {
        try {
            compose.waitUntil(ESPERA_MS, condicion)
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("Sin «$que» tras ${ESPERA_MS / 1000} s", error)
        }
    }

    /** Solo etiquetas, nunca textos: los textos podrían traer datos de la cuenta. */
    private fun etiquetas(): List<String> = runCatching {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag] }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

    private data class Caso(val id: String, val referencia: String, val poliza: String, val reportadoEn: String)

    private companion object {
        const val POLIZA = "POL-STG-AUTO"
        const val ESPERA_MS = 60_000L
        val BOGOTA: ZoneId = ZoneId.of("America/Bogota")
        val REFERENCIA = Regex("SIN-\\d{4}-\\d{6}")
    }
}
