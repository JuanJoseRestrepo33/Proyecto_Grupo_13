package co.solventa.mobile.e2e

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.MainActivity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/**
 * Reporte sin conexión de punta a punta (MOB-F05-02, #213): con el emulador en modo avión el reporte
 * queda como borrador cifrado y, al volver la red, la app lo envía sola. La web ve **un solo** siniestro,
 * con la misma referencia, `canal: movil` y su evidencia. Cierra en staging los criterios 1 y 2 de #211;
 * lo que no se puede provocar contra staging lo cubren las pruebas de #212.
 *
 * La red se corta de verdad, con `cmd connectivity airplane-mode`, sin tocar el código de la app. El
 * `@Before` y el `@After` la restauran siempre, y el workflow vuelve a hacerlo con `if: always()`. Como
 * las demás E2E, solo mira testTag y estado, para no imprimir datos de la cuenta.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("LargeClass")
class ReporteSinConexionE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentacion = InstrumentationRegistry.getInstrumentation()
    private val argumentos = InstrumentationRegistry.getArguments()
    private val correo = argumentos.getString("correo").orEmpty()
    private val clave = argumentos.getString("clave").orEmpty()
    private var paso = "preparación"

    @Before
    fun preparar() {
        assertFalse("La prueba no corre contra el mock: compila con -PBFF_MOCK=false.", BuildConfig.BFF_MOCK)
        assertTrue("Faltan los argumentos correo y clave.", correo.isNotEmpty() && clave.isNotEmpty())
        val transcurrido = SystemClock.elapsedRealtime() - inicioClase
        assertTrue(
            "La clase superó su límite de ${LIMITE_CLASE_MS / MINUTO_MS} min antes de esta prueba.",
            transcurrido < LIMITE_CLASE_MS,
        )
        // Los diálogos de permisos son del sistema: se conceden antes, como si el cliente ya hubiera aceptado.
        val paquete = instrumentacion.targetContext.packageName
        PERMISOS.forEach { instrumentacion.uiAutomation.grantRuntimePermission(paquete, it) }
        // Por si una ejecución anterior murió antes de su @After (criterio 7).
        restaurarRed()
    }

    @After
    fun restaurar() {
        // La red primero: si esto falla, la prueba siguiente no tendría con qué correr.
        restaurarRed()
        cerrarSesion()
    }

    // ── Recorrido 1: se corta la red en medio del asistente, con la app en primer plano ──────────

    // Dos veces, como en ReporteSiniestroE2eTest: los siniestros y las tarjetas de la cuenta se acumulan
    // y cada ejecución debe encontrar solo los suyos (criterio 6).
    @Test
    fun reporteGuardadoSinRedSeEnviaAlReconectar() = cortarEnMedioYReconectar()

    @Test
    fun segundoReporteSinRedTieneOtraReferencia() = cortarEnMedioYReconectar()

    private fun cortarEnMedioYReconectar() {
        val marca = "e2e-${UUID.randomUUID()}"

        paso = "iniciar sesión"
        iniciarSesion()
        val previas = tarjetasEnInicio()
        paso = "llenar el reporte con red"
        val poliza = abrirYElegirPoliza()
        llenarInformacion("Siniestro sin conexión ($marca).")
        paso = "tomar la foto"
        tomarFoto()

        paso = "cortar la red"
        modoAvion(activar = true)
        esperarRed(validada = false)
        esperar("sin-conexion")

        paso = "enviar sin red"
        avanzarHastaEnviar()
        esperar("revision-sin-conexion")
        // Un solo toque: el borrador ya quedó guardado, y otro toque no es parte del recorrido del cliente.
        tocar("enviar")
        esperar("guardado-sin_conexion")
        compose.onNodeWithText(VOLVER_AL_INICIO).performScrollTo().performClick()

        // Criterio 1: Inicio muestra una tarjeta pendiente nueva con «Esperando conexión» (M5).
        paso = "ver el borrador pendiente"
        val borrador = esperarTarjetaNueva(PREFIJO_BORRADOR, previas)
        val enLaTarjeta = hasAnyAncestor(hasTestTag(borrador))
        assertTrue(
            "La tarjeta pendiente no dice «$ESPERANDO_CONEXION»",
            compose.onAllNodes(hasText(ESPERANDO_CONEXION) and enLaTarjeta, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )

        // Criterio 2: se vuelve la red y la prueba no toca nada más.
        paso = "reconectar sin tocar la app"
        modoAvion(activar = false)
        esperarRed(validada = true)
        val reconexion = SystemClock.elapsedRealtime()
        val enviado = esperarEnvio(borrador, previas)
        val referencia = referenciaDe(enviado)
        assertTrue("Referencia inesperada: $referencia", REFERENCIA.matches(referencia))

        // Criterio 3: un solo siniestro en la web, con su única foto.
        paso = "consultar la web"
        val web = BffWeb(BuildConfig.BASE_URL.removeSuffix("movil/") + "web/")
        val cookie = web.iniciarSesion(correo, clave)
        val siniestro = sondearWeb(web, cookie, marca, restante(reconexion))
            ?: throw AssertionError("La web no muestra el siniestro con la marca de esta ejecución.")
        comprobarSiniestro(conMarca(web, cookie, marca), referencia, poliza)
        val evidencias = web.evidencias(cookie, siniestro.getString("id"))
        assertEquals("Evidencias del siniestro", 1, evidencias.size)
        assertEquals("image/jpeg", evidencias.single().getString("tipo_contenido"))

        paso = "cerrar la tarjeta enviada"
        entendido(enviado)
        assertTrue("Referencia repetida entre reportes: $referencia", referencias.add(referencia))
    }

    // ── Recorrido 2: sin señal desde el paso 1 y reconexión con la app en segundo plano ───────────

    @Test
    fun sinSenalDesdeElInicioSeEnviaEnSegundoPlano() {
        val marca = "e2e-${UUID.randomUUID()}"

        paso = "iniciar sesión y guardar las pólizas con red"
        iniciarSesion()
        // Ver la póliza sembrada es ver que la caché de pólizas ya se escribió.
        abrirYElegirPoliza(elegir = false)
        compose.onNodeWithContentDescription(VOLVER).performClick()
        esperar("ir-reportar-siniestro")
        val previas = tarjetasEnInicio()

        // Criterio 4: el paso 1 sin señal muestra las pólizas guardadas con su última actualización (M1).
        paso = "abrir el reporte sin red"
        modoAvion(activar = true)
        esperarRed(validada = false)
        val poliza = abrirYElegirPoliza(esperarCache = true)
        llenarInformacion("Siniestro en segundo plano ($marca).")
        paso = "enviar sin red y sin evidencia"
        avanzarHastaEnviar()
        tocar("enviar")
        esperar("guardado-sin_conexion")
        compose.onNodeWithText(VOLVER_AL_INICIO).performScrollTo().performClick()
        esperarTarjetaNueva(PREFIJO_BORRADOR, previas)

        // Criterio 5: con la app en segundo plano, vuelve la red y el siniestro llega solo a la web.
        paso = "reconectar con la app en segundo plano"
        UiDevice.getInstance(instrumentacion).pressHome()
        modoAvion(activar = false)
        esperarRed(validada = true)
        val web = BffWeb(BuildConfig.BASE_URL.removeSuffix("movil/") + "web/")
        val cookie = web.iniciarSesion(correo, clave)
        // Sin tocar Compose ni abrir la app: onStart() también despierta el enviador y ocultaría el fallo.
        val siniestro = sondearWeb(web, cookie, marca, ENVIO_MS)
            ?: throw AssertionError("No se envió en segundo plano: la web no lo ve tras ${ENVIO_MS / 1000} s.")
        val referencia = siniestro.getString("referencia")
        comprobarSiniestro(conMarca(web, cookie, marca), referencia, poliza)

        // Solo después de verlo en la web se vuelve a la app, que muestra la misma referencia (M7).
        paso = "volver a la app"
        volverALaApp()
        val enviado = obtener("la tarjeta enviada con la referencia de la web", ESPERA_MS) {
            tarjetasNuevas(PREFIJO_ENVIADO, previas).firstOrNull { referenciaDe(it) == referencia }
        }
        entendido(enviado)
        assertTrue("Referencia repetida entre reportes: $referencia", referencias.add(referencia))
    }

    // ── Pasos del asistente ───────────────────────────────────────────────────────────────────

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
        }
        esperar("ir-reportar-siniestro habilitado") {
            compose.onAllNodes(hasTestTag("ir-reportar-siniestro") and isEnabled()).fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /**
     * Abre «Reportar siniestro» y espera la póliza sembrada. Con [esperarCache], sin red: espera además
     * la indicación de las pólizas guardadas, tras el timeout de red que pasa antes de usar la caché.
     * Devuelve el número de la póliza, que el núcleo deriva de la semilla para cada cliente.
     */
    private fun abrirYElegirPoliza(elegir: Boolean = true, esperarCache: Boolean = false): String {
        tocar("ir-reportar-siniestro")
        if (esperarCache) {
            esperar("sin-conexion")
            // No se compara el texto de la fecha: depende del idioma del emulador.
            esperar("polizas-guardadas")
            esperar("la póliza sembrada guardada") { hayPoliza() }
        } else {
            esperar("la póliza sembrada o reintentar") { hayPoliza() || hayReintentar() }
            if (hayReintentar()) {
                conReintento(listo = { hayPoliza() }) { compose.onNodeWithText(REINTENTAR).performClick() }
            }
        }
        val nodo = compose.onAllNodes(polizaSembrada()).onFirst()
        val poliza = nodo.fetchSemanticsNode().config[SemanticsProperties.TestTag].removePrefix("poliza-")
        if (elegir) {
            nodo.performScrollTo().performClick()
            tocar("continuar")
        }
        return poliza
    }

    private fun llenarInformacion(descripcion: String) {
        esperar("tipo-accidente_vehiculo")
        tocar("tipo-accidente_vehiculo")
        elegirHoy()
        compose.onNodeWithTag("campo-descripcion").performScrollTo().performTextInput(descripcion)
        tocar("continuar")
        esperar("paso-evidencia")
    }

    /** Cámara de la app, como en #179. «Capturar» se toca una sola vez: reintentar podría dejar dos fotos. */
    private fun tomarFoto() {
        esperar("tomar-foto habilitado") {
            compose.onAllNodes(hasTestTag("tomar-foto") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        tocar("tomar-foto")
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

    // ── Tarjetas de Inicio ────────────────────────────────────────────────────────────────────

    /**
     * Las tarjetas `borrador-*` y `enviado-*` que ya hay, de ejecuciones anteriores. La prueba solo mira
     * las nuevas: nunca usa `referencia-enviada` de forma global.
     */
    private fun tarjetasEnInicio(): Set<String> {
        esperar("ir-reportar-siniestro")
        compose.waitForIdle()
        return (etiquetasCon(PREFIJO_BORRADOR) + etiquetasCon(PREFIJO_ENVIADO)).toSet() + vistas
    }

    private fun tarjetasNuevas(prefijo: String, previas: Set<String>) =
        etiquetasCon(prefijo).filterNot { it in previas }

    private fun esperarTarjetaNueva(prefijo: String, previas: Set<String>): String {
        val nueva = obtener("una tarjeta $prefijo… nueva", ESPERA_MS) { tarjetasNuevas(prefijo, previas).firstOrNull() }
        val nuevas = tarjetasNuevas(prefijo, previas)
        assertEquals("Tarjetas $prefijo… nuevas", 1, nuevas.size)
        vistas += nueva
        return nueva
    }

    /** Criterio 2: el borrador desaparece y aparece una tarjeta enviada nueva, con otro id (M6 → M7). */
    private fun esperarEnvio(borrador: String, previas: Set<String>): String {
        val enviado = obtener("la tarjeta enviada", ENVIO_MS) {
            tarjetasNuevas(PREFIJO_ENVIADO, previas).firstOrNull()?.takeIf { !hay(borrador) }
        }
        assertEquals("Tarjetas enviadas nuevas", 1, tarjetasNuevas(PREFIJO_ENVIADO, previas).size)
        vistas += enviado
        return enviado
    }

    private fun referenciaDe(tarjeta: String): String {
        val nodos = compose.onAllNodes(
            hasTestTag("referencia-enviada") and hasAnyAncestor(hasTestTag(tarjeta)),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        return nodos.singleOrNull()?.config?.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("")
    }

    /** «Entendido» dentro de la tarjeta propia la retira; la clase termina sin tarjetas suyas. */
    private fun entendido(tarjeta: String) {
        compose.onNode(hasText(ENTENDIDO) and hasClickAction() and hasAnyAncestor(hasTestTag(tarjeta)))
            .performScrollTo()
            .performClick()
        esperar("que se retire $tarjeta") { !hay(tarjeta) }
    }

    // ── Lo que ve la web ──────────────────────────────────────────────────────────────────────

    /** Lo que queda de los 4 min desde [reconexion]; al menos un sondeo. */
    private fun restante(reconexion: Long) =
        maxOf(ENVIO_MS - (SystemClock.elapsedRealtime() - reconexion), SONDEO_MS)

    /** Sondea cada 5 s hasta [plazoMs] el siniestro con [marca]; null si no aparece. */
    private fun sondearWeb(web: BffWeb, cookie: String, marca: String, plazoMs: Long): JSONObject? {
        val limite = SystemClock.elapsedRealtime() + plazoMs
        while (true) {
            val encontrado = runCatching { conMarca(web, cookie, marca).firstOrNull() }.getOrNull()
            if (encontrado != null) return encontrado
            if (SystemClock.elapsedRealtime() >= limite) return null
            SystemClock.sleep(SONDEO_MS)
        }
    }

    private fun conMarca(web: BffWeb, cookie: String, marca: String) =
        web.siniestros(cookie).filter { it.getString("descripcion").contains(marca) }

    /** Exactamente un siniestro con la marca, con la referencia, la póliza, `reportado` y `canal: movil`. */
    private fun comprobarSiniestro(conMarca: List<JSONObject>, referencia: String, poliza: String) {
        assertEquals("Siniestros con la marca de esta ejecución", 1, conMarca.size)
        val siniestro = conMarca.single()
        assertTrue("Referencia inesperada: $referencia", REFERENCIA.matches(referencia))
        assertEquals(referencia, siniestro.getString("referencia"))
        assertEquals(poliza, siniestro.getString("numero_poliza"))
        assertEquals("reportado", siniestro.getString("estado"))
        assertEquals("movil", siniestro.getString("canal"))
    }

    // ── Red, segundo plano y sesión ──────────────────────────────────────────────────────────

    /**
     * Vuelve a la app con la intención de lanzamiento, como desde el lanzador. Si Android creó otra
     * Activity, empieza en Ingreso: se vuelve a ingresar con la misma cuenta, lo que no cambia nada de lo
     * ya comprobado en la web.
     */
    private fun volverALaApp() {
        val contexto = instrumentacion.targetContext
        val intencion = checkNotNull(contexto.packageManager.getLaunchIntentForPackage(contexto.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        contexto.startActivity(intencion)
        esperar("Inicio o Ingreso") { hay("ir-reportar-siniestro") || hay("ingreso-correo") }
        if (hay("ingreso-correo")) iniciarSesion()
    }

    /** Desactiva el modo avión y espera la red validada. Falla si no vuelve: el resto no podría correr. */
    private fun restaurarRed() {
        modoAvion(activar = false)
        esperarRed(validada = true)
    }

    /** Best effort: si la prueba falló a mitad, la pantalla puede ser cualquiera. Ingreso queda para la siguiente. */
    private fun cerrarSesion() {
        runCatching {
            if (!hay("ir-reportar-siniestro") && !hay("cerrar-sesion")) return
            if (!hay("cerrar-sesion")) compose.onNodeWithText(PERFIL).performClick()
            esperar("cerrar-sesion")
            tocar("cerrar-sesion")
            compose.waitForIdle()
            // Con un borrador que quedó de una prueba fallida, la app pide confirmar (M13).
            if (hay("confirmar-cerrar-sesion")) compose.onNodeWithTag("confirmar-cerrar-sesion").performClick()
            esperar("ingreso-correo")
        }
    }

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

    private fun tocar(etiqueta: String) {
        compose.onNodeWithTag(etiqueta).performScrollTo().performClick()
    }

    private fun hay(etiqueta: String) =
        compose.onAllNodesWithTag(etiqueta, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun etiquetasCon(prefijo: String): List<String> = etiquetas().filter { it.startsWith(prefijo) }

    private fun esperar(etiqueta: String) = esperar(etiqueta) { hay(etiqueta) }

    private fun esperar(que: String, condicion: () -> Boolean) {
        obtener(que, ESPERA_MS) { true.takeIf { condicion() } }
    }

    /**
     * Las llamadas al BFF no cuentan como trabajo pendiente para Compose: hay que esperar lo que se ve.
     * Si no llega, el error dice el paso, lo que se esperaba y las etiquetas en pantalla (nunca textos).
     */
    private fun <T : Any> obtener(que: String, plazoMs: Long, valor: () -> T?): T {
        var resultado: T? = null
        try {
            compose.waitUntil(plazoMs) {
                resultado = valor()
                resultado != null
            }
        } catch (error: ComposeTimeoutException) {
            val segundos = plazoMs / 1000
            throw AssertionError("Paso «$paso»: sin «$que» tras $segundos s. En pantalla: ${etiquetas()}", error)
        }
        return checkNotNull(resultado)
    }

    private fun etiquetas(): List<String> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag] }
            .distinct()
            .sorted()

    companion object {
        /** Recuperar `VALIDATED` tras salir del modo avión, o perderla al entrar. */
        const val RED_MS = 2 * 60_000L

        /** De la reconexión a la tarjeta enviada: el primer intento y los reintentos de 30 s, 1 y 2 min. */
        const val ENVIO_MS = 4 * 60_000L

        /** Cada cuánto se consulta el BFF web. */
        const val SONDEO_MS = 5_000L

        /** La clase completa, para que el job E2E siga dentro de sus 45 min. */
        const val LIMITE_CLASE_MS = 15 * 60_000L

        private const val MINUTO_MS = 60_000L
        private const val ESPERA_MS = 60_000L
        private const val PASOS_MAXIMOS = 5
        private const val INTENTOS = 10
        private const val PAUSA_MS = 3_000L
        private const val POLIZA = "POL-STG-AUTO"
        private const val PREFIJO_BORRADOR = "borrador-"
        private const val PREFIJO_ENVIADO = "enviado-"
        private const val CAPTURAR = "Capturar"
        private const val REINTENTAR = "Reintentar"
        private const val VOLVER = "Volver"
        private const val VOLVER_AL_INICIO = "Volver al inicio"
        private const val ESPERANDO_CONEXION = "Esperando conexión"
        private const val ENTENDIDO = "Entendido"
        private const val PERFIL = "Perfil"
        private val REFERENCIA = Regex("SIN-\\d{4}-\\d{6}")
        private val PERMISOS = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        /** Referencias de toda la clase: cada prueba comprueba que la suya es nueva (criterio 6). */
        private val referencias = mutableSetOf<String>()

        /** Tarjetas que ya usó otra prueba de la clase: nunca cuentan como nuevas. */
        private val vistas = mutableSetOf<String>()

        private var inicioClase = 0L

        /**
         * Comprobación previa: el runner deja cortar la red y recuperar una red `VALIDATED`, que es lo que
         * exige `MonitorConectividad`. Si no, el resultado no es concluyente y toca el plan B (README).
         */
        @BeforeClass
        @JvmStatic
        fun comprobarModoAvion() {
            inicioClase = SystemClock.elapsedRealtime()
            try {
                modoAvion(activar = false)
                esperarRed(validada = true)
                modoAvion(activar = true)
                esperarRed(validada = false)
                modoAvion(activar = false)
                esperarRed(validada = true)
            } catch (error: AssertionError) {
                modoAvion(activar = false)
                throw AssertionError(
                    "No concluyente: el emulador no deja cortar y recuperar la red con el modo avión " +
                        "(${error.message}). Hacer los recorridos a mano (plan B del README).",
                    error,
                )
            }
        }

        /** Por la shell de la instrumentación; se lee la salida hasta el final y se cierra el descriptor. */
        fun modoAvion(activar: Boolean) {
            val comando = "cmd connectivity airplane-mode ${if (activar) "enable" else "disable"}"
            val descriptor: ParcelFileDescriptor =
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(comando)
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }

        /** La misma condición que `MonitorConectividadAndroid`: red por defecto con internet y validada. */
        fun esperarRed(validada: Boolean) {
            val contexto = InstrumentationRegistry.getInstrumentation().targetContext
            val gestor = contexto.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val limite = SystemClock.elapsedRealtime() + RED_MS
            while (redValidada(gestor) != validada) {
                if (SystemClock.elapsedRealtime() >= limite) {
                    val estado = if (validada) "validada" else "cortada"
                    throw AssertionError("La red no quedó $estado tras ${RED_MS / 1000} s.")
                }
                SystemClock.sleep(PAUSA_RED_MS)
            }
        }

        private fun redValidada(gestor: ConnectivityManager): Boolean {
            val capacidades = gestor.getNetworkCapabilities(gestor.activeNetwork) ?: return false
            return capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        private const val PAUSA_RED_MS = 1_000L
    }
}
