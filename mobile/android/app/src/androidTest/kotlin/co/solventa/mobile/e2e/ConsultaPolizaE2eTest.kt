package co.solventa.mobile.e2e

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.feature.polizas.R
import co.solventa.feature.polizas.comun.formatoDia
import co.solventa.feature.polizas.detalle.nombreArchivo
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.MainActivity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID

/**
 * La póliza emitida en la web aparece en la app sin hacer nada más (WEB-F04, #193): con «Mis pólizas» ya
 * abierta una vez, se compra por el BFF web y, al volver a entrar a la pestaña, la póliza nueva está de
 * primera en Activas, con los mismos datos que ve la web, y su PDF es el mismo que descarga la web.
 *
 * Cada fallo dice en qué paso del recorrido ocurrió. Las credenciales llegan por argumentos de
 * instrumentación y ningún mensaje las incluye; los mensajes pueden dar el número de la póliza.
 */
@RunWith(AndroidJUnit4::class)
class ConsultaPolizaE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val argumentos = InstrumentationRegistry.getArguments()
    private val correo = argumentos.getString("correo").orEmpty()
    private val clave = argumentos.getString("clave").orEmpty()
    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext
    private val web = BffWeb(BuildConfig.BASE_URL.removeSuffix("movil/") + "web/")

    @Before
    fun preparar() {
        assertFalse("La prueba no corre contra el mock: compila con -PBFF_MOCK=false.", BuildConfig.BFF_MOCK)
        assertTrue("Faltan los argumentos correo y clave.", correo.isNotEmpty() && clave.isNotEmpty())
        // El emulador puede no tener visor de PDF: se registra el ACTION_VIEW y no se abre nada.
        Intents.init()
        Intents.intending(hasAction(Intent.ACTION_VIEW))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    @After
    fun liberar() = Intents.release()

    // Dos compras seguidas (criterio 7): la cuenta acumula pólizas y cada recorrido busca solo la suya.
    @Test
    fun primeraPolizaEmitidaEnLaWebApareceEnLaApp() = recorrido()

    @Test
    fun segundaPolizaEmitidaEnLaWebApareceEnLaAppConOtroNumero() = recorrido()

    private fun recorrido() {
        paso(1, "iniciar sesión en la app") { iniciarSesion() }
        val antes = paso(2, "primera entrada a Pólizas") { primeraEntrada() }
        paso(3, "volver a Inicio") {
            tocarFijo("nav-home")
            esperar { hay("ir-reportar-siniestro") }
            assertTrue("No se ve Inicio", hay("ir-reportar-siniestro"))
        }
        val compra = paso(4, "comprar por la web") { comprar(antes) }
        paso(5, "segunda entrada a Pólizas") { comprobarTarjeta(compra) }
        paso(6, "comparar con la lista de la web") { comprobarListaWeb(compra) }
        paso(7, "detalle de la póliza emitida") { comprobarDetalle(compra) }
        paso(8, "descargar el documento") { comprobarDocumento(compra) }
        paso(9, "detalle de la póliza sembrada") { comprobarPolizaSembrada(compra.cookie) }
    }

    /** Lo que deja la compra por la web: la oferta aceptada y la póliza emitida. */
    private class Compra(val cookie: String, val oferta: JSONObject, poliza: JSONObject) {
        val numero: String = poliza.getString("numero")
        val inicio: String = poliza.getString("inicio")
        val fin: String = poliza.getString("fin")
        val producto: String = oferta.getString("nombre_producto")
    }

    private fun iniciarSesion() {
        esperar("ingreso-correo")
        compose.onNodeWithTag("ingreso-correo").performTextInput(correo)
        compose.onNodeWithTag("ingreso-contrasena").performTextInput(clave)
        conReintento(listo = { hay("ir-reportar-siniestro") || hay("oferta") || hay("kyc-aprobada") }) {
            compose.onNodeWithText("Iniciar sesión").performScrollTo().performClick()
        }
        // Sin la identidad verificada, la app muestra antes el resultado de la verificación
        // (destinoTrasIngreso). En staging pasa en el primer ingreso de la cuenta en la corrida.
        if (hay("kyc-aprobada")) {
            compose.onNodeWithText("Continuar").performScrollTo().performClick()
            esperar { hay("ir-reportar-siniestro") || hay("oferta") }
        }
        // Solo aparece en un dispositivo con huella registrada.
        if (hay("oferta")) {
            compose.onNodeWithText("Ahora no").performScrollTo().performClick()
            esperar("ir-reportar-siniestro")
        }
        assertTrue("No se llegó a Inicio", hay("ir-reportar-siniestro"))
    }

    /** Abre la pestaña por primera vez en la sesión y devuelve los números que muestra Activas. */
    private fun primeraEntrada(): Set<String> {
        tocarFijo("nav-policies")
        esperar { listaCargada() || hay("reintentar") }
        exigirSinErrorDeCarga()
        assertTrue("La lista no terminó de cargar", listaCargada())
        return tarjetas().toSet()
    }

    private fun comprar(antes: Set<String>): Compra {
        val cookie = web.iniciarSesion(correo, clave)
        // Ciudad única: el núcleo reutiliza la cotización vigente de una solicitud idéntica, y aceptar una
        // cotización ya emitida devolvería la misma póliza.
        val oferta = web.cotizarAuto(cookie, ciudadUnica())
        val suscripcion = web.suscribir(cookie, oferta.getString("id")).getString("id")
        val pago = web.pagar(cookie, suscripcion, UUID.randomUUID().toString())
        assertEquals("El pago no quedó aprobado", "aprobado", pago.getString("estado"))
        val emitida = web.firmar(cookie, suscripcion)
        assertEquals("La suscripción no quedó emitida", "emitida", emitida.getString("estado"))
        val compra = Compra(cookie, oferta, emitida.getJSONObject("poliza"))
        assertFalse("La app ya mostraba ${compra.numero} antes de la compra", compra.numero in antes)
        assertTrue("Número repetido entre corridas: ${compra.numero}", numeros.add(compra.numero))
        return compra
    }

    /** Sin deslizar ni tocar «Actualizar»: basta volver a entrar a la pestaña (criterios 1 y 2). */
    private fun comprobarTarjeta(compra: Compra) {
        tocarFijo("nav-policies")
        // El tope cubre el arranque en frío de Cloud Run; mientras tanto se ve la lista anterior.
        esperar(ESPERA_LISTA_MS) { tarjetas().firstOrNull() == compra.numero || hay("reintentar") }
        exigirSinErrorDeCarga()
        assertTrue(
            "La pestaña Activas no está elegida",
            compose.onAllNodes(hasTestTag("pestana-activas") and isSelected()).fetchSemanticsNodes().isNotEmpty(),
        )
        assertEquals("La póliza emitida no es la primera de Activas", compra.numero, tarjetas().firstOrNull())
        Capturas.tomar("app-staging-polizas")
        val descripcion = descripcionDe(abrirTarjeta(compra.numero))
        listOf(
            compra.producto,
            texto(R.string.estado_vigente),
            compra.numero,
            formatoDia(compra.inicio),
            formatoDia(compra.fin),
        ).forEach { esperado ->
            assertTrue("La tarjeta de ${compra.numero} no dice «$esperado»: $descripcion", esperado in descripcion)
        }
    }

    private fun comprobarListaWeb(compra: Compra) {
        val poliza = web.polizas(compra.cookie).singleOrNull { it.getString("numero") == compra.numero }
        assertNotNull("La web no lista ${compra.numero}", poliza)
        assertEquals("Producto en la web", compra.producto, poliza!!.getString("nombre_producto"))
        assertEquals("Estado en la web", "vigente", poliza.getString("estado"))
        assertEquals("Inicio en la web", compra.inicio, poliza.getString("inicio"))
        assertEquals("Fin en la web", compra.fin, poliza.getString("fin"))
        assertFalse("La web no ofrece el documento", poliza.isNull("documento_url"))
    }

    /** Coberturas, deducible y valor asegurado: los de la oferta, los de la web y los de la app (criterio 3). */
    private fun comprobarDetalle(compra: Compra) {
        abrirTarjeta(compra.numero).performScrollTo().performClick()
        esperarDetalle(compra.numero)
        Capturas.tomar("app-staging-poliza-detalle")
        assertTrue(
            "El encabezado no tiene la insignia Vigente",
            compose.onAllNodes(hasTestTag("insignia-vigente") and hasAnyAncestor(hasTestTag("encabezado")))
                .fetchSemanticsNodes().isNotEmpty(),
        )

        val detalle = web.poliza(compra.cookie, compra.numero)
        val oferta = compra.oferta
        listOf("valor_asegurado", "deducible").forEach { campo ->
            assertEquals("$campo: web y oferta", oferta.getString(campo), detalle.getString(campo))
        }
        val coberturas = coberturas(detalle)
        assertEquals("Coberturas: web y oferta", coberturas(oferta), coberturas)
        assertTrue("La oferta no tiene coberturas", coberturas.isNotEmpty())

        assertFila("valor-asegurado", R.string.detalle_valor_asegurado, detalle.getString("valor_asegurado"))
        assertFila("deducible", R.string.detalle_deducible, detalle.getString("deducible"))
        comprobarCoberturas(coberturas)
        val noDisponible = texto(R.string.no_disponible)
        filas().forEach { (etiqueta, valor) ->
            assertFalse("$etiqueta dice «$noDisponible»", valor.contains(noDisponible))
        }
    }

    /** La app pide abrir con el visor del sistema el mismo PDF que descarga la web (criterio 4). */
    private fun comprobarDocumento(compra: Compra) {
        val previos = Intents.getIntents().size
        tocar("descargar-pdf")
        esperar { pdfPedido(previos) != null || hayAviso() }
        assertFalse("Apareció un aviso de error de la descarga", hayAviso())
        val intento = pdfPedido(previos)
        assertNotNull("La app no pidió abrir el PDF", intento)
        assertEquals("Tipo del ACTION_VIEW", "application/pdf", intento!!.type)
        assertTrue(
            "El ACTION_VIEW no da permiso de lectura",
            intento.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        val uri = checkNotNull(intento.data) { "El ACTION_VIEW no trae la URI" }
        assertEquals("Archivo del PDF", nombreArchivo(compra.numero), uri.lastPathSegment)
        val enLaApp = checkNotNull(contexto.contentResolver.openInputStream(uri)) { "No se pudo leer la URI" }
            .use { it.readBytes() }
        assertTrue("El archivo no empieza por %PDF-", enLaApp.copyOf(CABECERA_PDF.size).contentEquals(CABECERA_PDF))
        val enLaWeb = web.documento(compra.cookie, compra.numero)
        assertEquals("SHA-256 del PDF: app y web", sha256(enLaWeb), sha256(enLaApp))
        assertFalse("Apareció un aviso de error tras abrir el PDF", hayAviso())
    }

    /**
     * La póliza de la semilla no tiene documento: se explica y no se ofrece la descarga (criterio 5).
     *
     * Sus condiciones no son fijas: el núcleo completa las pólizas de prueba con las del catálogo de su
     * producto (criterio 4 de #186, `ServicioPolizas._completar`), y solo si el producto no está en el
     * catálogo quedan sin coberturas. La app debe decir lo mismo que el BFF web para esa póliza: sus
     * coberturas, o «sin coberturas»; su valor asegurado y deducible, o «No disponible».
     */
    private fun comprobarPolizaSembrada(cookie: String) {
        tocarFijo("volver")
        esperar { hay("pestana-todas") || hay("reintentar") }
        exigirSinErrorDeCarga()
        tocar("pestana-todas")
        esperar { compose.onAllNodes(polizaSembrada()).fetchSemanticsNodes().isNotEmpty() }
        val tarjeta = compose.onAllNodes(polizaSembrada()).fetchSemanticsNodes().firstOrNull()
        assertNotNull("Todas no muestra la póliza sembrada $POLIZA_SEMBRADA", tarjeta)
        val numero = tarjeta!!.config[SemanticsProperties.TestTag].removePrefix("poliza-")
        abrirTarjeta(numero).performScrollTo().performClick()
        esperarDetalle(numero)

        assertTrue("No dice que el documento no está disponible", hay("sin-documento"))
        assertFalse("Ofrece descargar un documento que no existe", hay("descargar-pdf"))

        val detalle = web.poliza(cookie, numero)
        assertTrue("La web ofrece un documento para la póliza sembrada", detalle.isNull("documento_url"))
        val noDisponible = texto(R.string.no_disponible)
        val valorAsegurado = textoOpcional(detalle, "valor_asegurado") ?: noDisponible
        assertFila("valor-asegurado", R.string.detalle_valor_asegurado, valorAsegurado)
        assertFila("deducible", R.string.detalle_deducible, textoOpcional(detalle, "deducible") ?: noDisponible)
        val coberturas = coberturas(detalle)
        if (coberturas.isEmpty()) {
            assertTrue("No dice que no hay coberturas", hay("sin-coberturas"))
        } else {
            assertFalse("Dice que no hay coberturas y la web tiene ${coberturas.size}", hay("sin-coberturas"))
            comprobarCoberturas(coberturas)
        }
    }

    /** Una fila por cobertura, con su límite, y ninguna de más. */
    private fun comprobarCoberturas(coberturas: List<Pair<String, String>>) {
        coberturas.forEachIndexed { indice, (nombre, limite) ->
            val esperada = texto(R.string.fila_descripcion, nombre, limite)
            assertEquals("Cobertura $indice", esperada, fila("cobertura-$indice"))
        }
        assertFalse("La app muestra más coberturas que la web", hay("fila-cobertura-${coberturas.size}"))
    }

    /** El texto del campo, o null si falta, es null o está en blanco (la app dice entonces «No disponible»). */
    private fun textoOpcional(json: JSONObject, campo: String): String? =
        if (!json.has(campo) || json.isNull(campo)) null else json.getString(campo).takeIf(String::isNotBlank)

    private fun esperarDetalle(numero: String) {
        val enEncabezado = hasText(numero) and hasAnyAncestor(hasTestTag("encabezado"))
        val cargado = { compose.onAllNodes(enEncabezado).fetchSemanticsNodes().isNotEmpty() }
        esperar { cargado() || hay("reintentar") || hay("poliza-no-encontrada") }
        assertFalse("La app no encuentra $numero", hay("poliza-no-encontrada"))
        exigirSinErrorDeCarga()
        assertTrue("El encabezado del detalle no tiene $numero", cargado())
    }

    private fun listaCargada() = hay("pantalla-mis-polizas") && !hay("cargando") &&
        (hay("pestana-activas") || hay("sin-polizas"))

    /** Números de las tarjetas en el orden en que se ven. La lista no es perezosa: están todas. */
    private fun tarjetas(): List<String> = compose.onAllNodes(tarjetaDePoliza()).fetchSemanticsNodes()
        .map { it.config[SemanticsProperties.TestTag].removePrefix("poliza-") }

    private fun tarjetaDePoliza() = SemanticsMatcher("tarjeta de póliza") { nodo ->
        nodo.config.getOrNull(SemanticsProperties.TestTag).orEmpty().startsWith("poliza-")
    }

    /** El control de la tarjeta: reemplaza la semántica de sus textos con una sola descripción. */
    private fun abrirTarjeta(numero: String): SemanticsNodeInteraction =
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("poliza-$numero")))

    /**
     * La semilla de staging da `POL-STG-AUTO` a cualquier cliente, y el núcleo la entrega con el sufijo
     * de su id (`POL-STG-AUTO-000001`). Se acepta también el número sin sufijo.
     */
    private fun polizaSembrada() = SemanticsMatcher("póliza $POLIZA_SEMBRADA del cliente") { nodo ->
        val etiqueta = nodo.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
        etiqueta == "poliza-$POLIZA_SEMBRADA" || etiqueta.startsWith("poliza-$POLIZA_SEMBRADA-")
    }

    private fun fila(tag: String): String = descripcionDe(compose.onNodeWithTag("fila-$tag").performScrollTo())

    private fun assertFila(tag: String, @StringRes etiqueta: Int, valor: String) =
        assertEquals("Fila ${texto(etiqueta)}", texto(R.string.fila_descripcion, texto(etiqueta), valor), fila(tag))

    /** Etiqueta y descripción de cada fila del detalle. */
    private fun filas(): List<Pair<String, String>> = compose.onAllNodes(filaDelDetalle()).fetchSemanticsNodes()
        .map { nodo ->
            nodo.config[SemanticsProperties.TestTag] to
                nodo.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
        }

    private fun filaDelDetalle() = SemanticsMatcher("fila del detalle") { nodo ->
        nodo.config.getOrNull(SemanticsProperties.TestTag).orEmpty().startsWith("fila-")
    }

    private fun coberturas(json: JSONObject): List<Pair<String, String>> {
        val lista = json.getJSONArray("coberturas")
        return List(lista.length()) { indice ->
            lista.getJSONObject(indice).let { it.getString("nombre") to it.getString("limite") }
        }
    }

    private fun pdfPedido(previos: Int): Intent? =
        Intents.getIntents().drop(previos).lastOrNull { it.action == Intent.ACTION_VIEW }

    private fun hayAviso() = AVISOS.any { aviso ->
        compose.onAllNodesWithText(texto(aviso)).fetchSemanticsNodes().isNotEmpty()
    }

    /** «Reintentar» solo aparece si la consulta falló: la prueba no lo toca, falla en el paso. */
    private fun exigirSinErrorDeCarga() = assertFalse("La app no pudo cargar las pólizas", hay("reintentar"))

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
        esperar(condicion = listo)
    }

    private fun descripcionDe(nodo: SemanticsNodeInteraction): String =
        nodo.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")

    private fun texto(@StringRes id: Int, vararg argumentos: Any): String = contexto.getString(id, *argumentos)

    private fun tocar(etiqueta: String) {
        compose.onNodeWithTag(etiqueta).performScrollTo().performClick()
    }

    /** Barra inferior y barra superior: fuera del contenido desplazable. */
    private fun tocarFijo(etiqueta: String) {
        compose.onNodeWithTag(etiqueta).performClick()
    }

    private fun hay(etiqueta: String) = compose.onAllNodesWithTag(etiqueta).fetchSemanticsNodes().isNotEmpty()

    private fun esperar(etiqueta: String) = esperar { hay(etiqueta) }

    /**
     * Las llamadas al BFF no cuentan como trabajo pendiente para Compose: hay que esperar lo que se ve. Si
     * se agota el tiempo no falla aquí: la aserción que sigue dice qué faltó.
     */
    private fun esperar(tope: Long = ESPERA_MS, condicion: () -> Boolean) {
        runCatching { compose.waitUntil(tope, condicion) }
    }

    /** Antepone el paso al mensaje de cualquier fallo, también de las llamadas al BFF web (criterio 6). */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> paso(numero: Int, nombre: String, cuerpo: () -> T): T = try {
        cuerpo()
    } catch (error: Throwable) {
        throw AssertionError("Paso $numero ($nombre): ${error.message}", error)
    }

    private companion object {
        const val POLIZA_SEMBRADA = "POL-STG-AUTO"
        const val ESPERA_MS = 60_000L
        const val ESPERA_LISTA_MS = 120_000L
        const val INTENTOS = 10
        const val PAUSA_MS = 3_000L
        val CABECERA_PDF = "%PDF-".toByteArray(Charsets.US_ASCII)
        val AVISOS = listOf(
            R.string.aviso_documento_no_disponible,
            R.string.aviso_descarga_sin_red,
            R.string.aviso_descarga_fallida,
            R.string.aviso_sin_visor,
        )
        val numeros = mutableSetOf<String>()

        /** Como `ciudadUnica()` de `apps/web/e2e/apoyo.ts`. */
        fun ciudadUnica() = "Bogotá ${java.lang.Long.toString(System.currentTimeMillis(), RADIX)}"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        const val RADIX = 36
    }
}
