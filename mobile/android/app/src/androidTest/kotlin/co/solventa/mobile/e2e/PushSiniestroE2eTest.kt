package co.solventa.mobile.e2e

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import co.solventa.feature.notificaciones.AlmacenPushAndroid
import co.solventa.feature.notificaciones.ReceptorPush
import co.solventa.feature.notificaciones.notificacionesPermitidas
import co.solventa.feature.onboarding.R as RIngreso
import co.solventa.feature.siniestros.R
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * El cambio de estado llega como push y abre el detalle (MOB-F06, #209). Un caso reportado por el BFF web
 * cambia a `en_evaluacion` con la operación interna del núcleo mientras la app está en segundo plano; la
 * notificación «Tu siniestro {referencia} cambió» aparece en la bandeja del sistema y, al tocarla, la app
 * abre el detalle de ese caso. El atrás del sistema lleva a «Mis siniestros» y la notificación queda leída.
 *
 * Con el proveedor simulado de *staging*, «push recibido» es esto: el núcleo crea la notificación, el
 * receptor de la app la lee de `GET /notificaciones` y la muestra como notificación local. La prueba no
 * afirma el estado de entrega del proveedor: no sale por el BFF (#205).
 *
 * La app queda en segundo plano con `pressHome()`, pero su proceso sigue vivo porque la instrumentación
 * corre en él: el push con el proceso muerto queda fuera. Un fallo dice el paso y nunca imprime
 * credenciales, el token interno, tokens de sesión ni el `token_push`.
 */
@RunWith(AndroidJUnit4::class)
class PushSiniestroE2eTest {
    private val instrumentacion = InstrumentationRegistry.getInstrumentation()
    private val argumentos = InstrumentationRegistry.getArguments()
    private val correo = argumentos.getString("correo").orEmpty()
    private val clave = argumentos.getString("clave").orEmpty()
    private val urlNucleo = argumentos.getString("nucleo").orEmpty()
    private val tokenInterno = argumentos.getString("tokenInterno").orEmpty()
    private val contexto = instrumentacion.targetContext
    private val gestor = contexto.getSystemService(NotificationManager::class.java)
    private val dispositivo = UiDevice.getInstance(instrumentacion)
    private val almacen = AlmacenPushAndroid(contexto)

    private val web = BffWeb(BuildConfig.BASE_URL.removeSuffix("movil/") + "web/")
    private val movil = BffMovil(BuildConfig.BASE_URL)
    private lateinit var nucleo: NucleoInterno

    private val compose = createAndroidComposeRule<MainActivity>()

    /**
     * El permiso por fuera y la app por dentro: queda concedido antes de crear `MainActivity`, así Inicio no
     * muestra el aviso. El estado inicial también se prepara antes de lanzarla.
     */
    @get:Rule
    val reglas: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(EstadoInicial())
        .around(compose)

    @Before
    fun exigirOperacionInterna() {
        // Sin la operación interna no hay cambio de estado: la prueba falla, no se omite.
        assertTrue("Faltan los argumentos nucleo y tokenInterno.", urlNucleo.isNotEmpty() && tokenInterno.isNotEmpty())
        nucleo = NucleoInterno(urlNucleo, tokenInterno)
    }

    /** Ni la bandeja abierta ni avisos de la app para las pruebas siguientes, aunque esta falle. */
    @After
    fun limpiar() {
        // No hace nada si la bandeja ya está cerrada; pressBack, en cambio, navegaría dentro de la app.
        instrumentacion.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
        gestor.cancelAll()
    }

    @Test
    fun elPushDelCambioAbreElDetalle() {
        val marca = "e2e-${UUID.randomUUID()}"
        val caso = paso("1-2 · reportar por la web") { reportarPorLaWeb(marca) }
        paso("3 · iniciar sesión en la app") { iniciarSesionEnLaApp() }
        paso("4 · primera lectura sin avisos y dispositivo registrado") { comprobarPrimeraLectura() }
        paso("5 · app en segundo plano") { dispositivo.pressHome() }
        val desde = paso("6 · cambiar a en_evaluacion en el núcleo") {
            nucleo.cambiarEstado(caso.id, "en_evaluacion")
            SystemClock.elapsedRealtime()
        }
        val titulo = paso("7 · push recibido") { esperarPush(caso, desde) }
        paso("8 · tocar la notificación y ver el detalle") {
            titulo.click()
            comprobarDetalle(caso)
        }
        paso("9 · volver a «Mis siniestros» con el atrás del sistema") { volverConAtras(caso) }
        paso("10 · notificación leída y sin repetirse") { comprobarLeida(caso) }
    }

    /** El caso como lo crea la web, con la póliza de auto vigente que el núcleo derivó para la cuenta. */
    private fun reportarPorLaWeb(marca: String): Caso {
        val cookie = web.iniciarSesion(correo, clave)
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
        return Caso(id = siniestro.getString("id"), referencia = referencia)
    }

    /** La sesión puede venir de otra prueba de la corrida: en ese caso la app ya abre en Inicio. */
    private fun iniciarSesionEnLaApp() {
        esperar("el ingreso o Inicio") { hay("ingreso-correo") || hay("ir-mis-siniestros") }
        if (hay("ingreso-correo")) {
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
        assertFalse("Inicio muestra el aviso del permiso, que ya estaba concedido", hay("aviso-notificaciones"))
    }

    /**
     * El almacén se borró antes de abrir la app: `vistas` deja de ser null solo con la primera lectura de
     * esta corrida, que marca lo que ya había sin mostrarlo. El receptor registra el dispositivo antes de
     * leer, así que el mismo token debe responder 200 «ya estaba registrado» (criterio 4 de #204).
     */
    private fun comprobarPrimeraLectura() {
        esperar("la primera lectura de la bandeja") { almacen.vistas(movil.clienteId) != null }
        assertEquals("Avisos activos tras la primera lectura", emptyList<String>(), avisosActivos().map { it.tag })
        assertEquals(
            "POST /dispositivos con el token de la instalación",
            HTTP_YA_REGISTRADO,
            movil.registrarDispositivo(almacen.tokenInstalacion()),
        )
    }

    /**
     * Primero en el proceso (`activeNotifications`), que no depende de cómo la pinte el sistema; después en
     * la bandeja del sistema. Devuelve el título en la bandeja, para tocarlo.
     */
    private fun esperarPush(caso: Caso, desde: Long): UiObject2 {
        val titulo = tituloDelPush(caso)
        val limite = desde + PUSH_MS
        val aviso = sondear(limite) { avisoDe(titulo) }
            ?: throw AssertionError("Sin el push del caso tras ${PUSH_MS / 1000} s; ${diagnostico(caso)}")
        assertEquals("Canal del push", CANAL, aviso.notification.channelId)
        assertEquals(
            "Cuerpo del push",
            CUERPO_EN_EVALUACION,
            aviso.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        assertTrue("No se abrió la bandeja del sistema", dispositivo.openNotification())
        assertTrue(
            "La bandeja del sistema no quedó abierta",
            dispositivo.wait(Until.hasObject(By.res(SYSTEMUI, "notification_stack_scroller")), BANDEJA_MS),
        )
        val enBandeja = buscarEnBandeja(titulo, maxOf(limite - SystemClock.elapsedRealtime(), BANDEJA_MS))
        checkNotNull(enBandeja) { "El push está activo en la app, pero no se ve en la bandeja del sistema" }
        assertTrue("La bandeja no muestra el cuerpo del push", dispositivo.hasObject(By.text(CUERPO_EN_EVALUACION)))
        return enBandeja
    }

    /** Si el sistema agrupó los avisos de la app, se expande el grupo y se desplaza hasta el título. */
    private fun buscarEnBandeja(titulo: String, esperaMs: Long): UiObject2? {
        dispositivo.wait(Until.findObject(By.text(titulo)), esperaMs)?.let { return it }
        dispositivo.findObjects(By.res("android", "expand_button")).forEach { it.click() }
        runCatching {
            UiScrollable(UiSelector().resourceId("$SYSTEMUI:id/notification_stack_scroller"))
                .scrollIntoView(UiSelector().text(titulo))
        }
        return dispositivo.wait(Until.findObject(By.text(titulo)), BANDEJA_MS)
    }

    /** Si el push no llegó, dice de qué lado falló: el núcleo no la creó o la app no la mostró. */
    private fun diagnostico(caso: Caso): String = runCatching {
        val enBff = movil.notificacionDe(caso.id)
        if (enBff == null) {
            "el BFF no tiene notificación del caso: el núcleo no la creó"
        } else {
            "el BFF sí la tiene (leida=${enBff.getBoolean("leida")}): la app no la mostró"
        }
    }.getOrElse { "no se pudo consultar el BFF (${it.message})" }

    /** El detalle de ese caso, con «En evaluación» en la insignia y como evento actual del historial. */
    private fun comprobarDetalle(caso: Caso) {
        esperar("pantalla-detalle-siniestro")
        val referencia = texto(R.string.seguimiento_referencia, caso.referencia)
        esperar("la referencia del caso en el encabezado") {
            fallarSiHayError()
            hay("encabezado") && referencia in textos("encabezado")
        }
        val estado = texto(R.string.estado_en_evaluacion)
        esperar("la insignia «$estado»") {
            fallarSiHayError()
            hay("insignia-estado", sinFusionar = true) && estado in textos("insignia-estado", sinFusionar = true)
        }
        compose.onNodeWithTag("insignia-estado", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("historial").performScrollTo()
        esperar("dos eventos en el historial") {
            compose.onNodeWithTag("historial").onChildren().fetchSemanticsNodes().size == EVENTOS
        }
        assertEquals("Eventos resaltados", 1, compose.onAllNodesWithTag("evento-actual").fetchSemanticsNodes().size)
        compose.onNodeWithTag("evento-actual").performScrollTo().assertIsDisplayed()
        assertTrue("El evento actual no es «$estado»", estado in textos("evento-actual"))
    }

    /** El detalle se abrió desde el push, sin la lista en la pila: el atrás la abre en vez de cerrar la app. */
    private fun volverConAtras(caso: Caso) {
        dispositivo.pressBack()
        esperar("pantalla-mis-siniestros")
        assertFalse("La app se cerró con el atrás", compose.activity.isFinishing)
        esperar("siniestro-${caso.id}")
    }

    /**
     * `setAutoCancel` la quita al tocarla; la marca de leída va en segundo plano. Luego se deja pasar al menos
     * una vuelta del receptor y la notificación no debe reaparecer.
     */
    private fun comprobarLeida(caso: Caso) {
        val titulo = tituloDelPush(caso)
        esperar("el push fuera de los avisos activos") { avisoDe(titulo) == null }
        val leida = sondear(SystemClock.elapsedRealtime() + LECTURA_MS) {
            movil.notificacionDe(caso.id)?.takeIf { it.getBoolean("leida") }
        }
        checkNotNull(leida) { "El BFF no marcó la notificación como leída tras ${LECTURA_MS / 1000} s" }
        val id = leida.getString("id")
        val desde = SystemClock.elapsedRealtime()
        val vuelta = ReceptorPush.INTERVALO.toMillis() + MARGEN_VUELTA_MS
        val reaparecio = sondear(desde + vuelta + ESPERA_MS) {
            when {
                avisoDe(titulo) != null -> true
                SystemClock.elapsedRealtime() - desde >= vuelta &&
                    almacen.vistas(movil.clienteId).orEmpty().contains(id) -> false
                else -> null
            }
        }
        checkNotNull(reaparecio) { "La app no volvió a leer la bandeja tras ${vuelta / 1000} s" }
        assertFalse("La notificación leída reapareció en la vuelta siguiente del receptor", reaparecio)
    }

    private fun tituloDelPush(caso: Caso) = "Tu siniestro ${caso.referencia} cambió"

    private fun avisosActivos(): List<StatusBarNotification> = gestor.activeNotifications.toList()

    private fun avisoDe(titulo: String): StatusBarNotification? = avisosActivos().firstOrNull {
        it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == titulo
    }

    /** Sondeo con tope: el primer valor no nulo de [consulta], o null si se vence [limite] (`elapsedRealtime`). */
    private fun <T : Any> sondear(limite: Long, consulta: () -> T?): T? {
        while (true) {
            consulta()?.let { return it }
            if (SystemClock.elapsedRealtime() >= limite) return null
            SystemClock.sleep(SONDEO_MS)
        }
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

    /** Textos y descripciones del nodo y sus descendientes. */
    private fun textos(etiqueta: String, sinFusionar: Boolean = false): List<String> =
        compose.onNodeWithTag(etiqueta, useUnmergedTree = sinFusionar).fetchSemanticsNode()
            .let(::recolectar)
            .distinct()

    private fun recolectar(nodo: SemanticsNode): List<String> =
        nodo.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            nodo.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            nodo.children.flatMap(::recolectar)

    private fun texto(@StringRes id: Int, vararg argumentos: Any): String = contexto.getString(id, *argumentos)

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

    /**
     * Antes de lanzar la actividad: sesión propia en el BFF móvil, almacén de push de la cuenta sin lecturas
     * y ningún aviso de la app, incluidos los que haya dejado `SeguimientoE2eTest`. Corre en el hilo
     * principal, el mismo del receptor: una lectura en curso no puede quedar a medias entre los dos pasos.
     */
    private inner class EstadoInicial : ExternalResource() {
        override fun before() {
            assertFalse("La prueba no corre contra el mock: compila con -PBFF_MOCK=false.", BuildConfig.BFF_MOCK)
            assertTrue("Faltan los argumentos correo y clave.", correo.isNotEmpty() && clave.isNotEmpty())
            assertTrue(
                "Las notificaciones de la app no están permitidas: hace falta POST_NOTIFICATIONS concedido y " +
                    "las notificaciones de la app encendidas en Ajustes.",
                notificacionesPermitidas(contexto),
            )
            paso("0 · sesión de la prueba en el BFF móvil") { movil.iniciarSesion(correo, clave) }
            instrumentacion.runOnMainSync {
                // Mismo archivo y clave que AlmacenPushAndroid; el token de la instalación se conserva, porque
                // el receptor de una prueba anterior pudo dejar el dispositivo registrado con él.
                contexto.getSharedPreferences(ARCHIVO_PUSH, Context.MODE_PRIVATE)
                    .edit()
                    .remove(VISTAS_PUSH + movil.clienteId)
                    .commit()
                gestor.cancelAll()
            }
        }
    }

    private data class Caso(val id: String, val referencia: String)

    private companion object {
        const val POLIZA = "POL-STG-AUTO"
        const val CANAL = "siniestros"
        const val SYSTEMUI = "com.android.systemui"

        /** Lo arma el núcleo: `TEXTOS_POR_ESTADO` en `backend/app/modulos/notificaciones/dominio/modelo.py`. */
        const val CUERPO_EN_EVALUACION = "Ya estamos evaluando tu caso."

        /** `AlmacenPushAndroid`: las preferencias `push` y la clave `vistas_<cliente>`. */
        const val ARCHIVO_PUSH = "push"
        const val VISTAS_PUSH = "vistas_"

        const val HTTP_YA_REGISTRADO = 200
        const val EVENTOS = 2
        const val ESPERA_MS = 60_000L

        /** Del cambio de estado al push visible: una vuelta del receptor (30 s) más la red, con holgura. */
        const val PUSH_MS = 90_000L
        const val BANDEJA_MS = 5_000L
        const val LECTURA_MS = 30_000L
        const val MARGEN_VUELTA_MS = 5_000L
        const val SONDEO_MS = 1_000L
        val BOGOTA: ZoneId = ZoneId.of("America/Bogota")
        val REFERENCIA = Regex("SIN-\\d{4}-\\d{6}")
    }
}
