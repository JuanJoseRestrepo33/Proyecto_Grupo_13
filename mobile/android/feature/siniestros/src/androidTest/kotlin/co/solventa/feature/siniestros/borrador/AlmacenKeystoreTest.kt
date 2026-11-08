package co.solventa.feature.siniestros.borrador

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.core.network.BffApi
import co.solventa.core.network.SolicitudSiniestro
import co.solventa.core.network.TipoEventoSiniestro
import co.solventa.feature.siniestros.evidencia.FormatoEvidencia
import java.io.File
import java.lang.reflect.Proxy
import java.security.KeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El almacén con la llave real del Android Keystore (MOB-F05-02). Correr en emulador con API 26 y 34:
 * `./gradlew :feature:siniestros:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class AlmacenKeystoreTest {
    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "solventa.borradores.prueba"
    private lateinit var raiz: File
    private lateinit var almacen: AlmacenBorradoresCifrado

    private val borrador = Borrador(
        id = "B-1",
        duenio = "CLI-2026-000001",
        solicitud = SolicitudSiniestro(
            "POL-2026-001101",
            TipoEventoSiniestro.HURTO,
            "2026-10-23",
            "Me robaron el celular en la calle 72",
        ),
        claveIdempotencia = "6f1c2b0e-8a4d-4f7e-9b1a-2c3d4e5f6a7b",
        adjuntos = listOf(
            AdjuntoBorrador(
                "ADJ-1",
                "evidencia-1.jpg",
                FormatoEvidencia.JPEG,
                tamano = 18,
                latitud = 4.6573,
                longitud = -74.0558,
            ),
        ),
        creadoEn = "2026-10-26T15:00:00Z",
    )
    private val contenido = "contenido de la foto".toByteArray()

    @Before
    fun preparar() {
        raiz = File(contexto.noBackupFilesDir, "borradores-prueba").apply { deleteRecursively() }
        almacen = AlmacenBorradoresCifrado(raiz, CifradoAesGcm(LlaveKeystore(alias)))
    }

    @After
    fun limpiar() {
        raiz.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        ejecutar("input keyevent KEYCODE_WAKEUP")
    }

    private fun guardar() {
        val cache = File(File(contexto.cacheDir, "evidencias").apply { mkdirs() }, "evidencia-1.jpg")
        cache.writeBytes(contenido)
        almacen.guardarNuevo(borrador, mapOf("ADJ-1" to cache))
        cache.delete()
    }

    private fun ejecutar(comando: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(comando).close()
    }

    @Test
    fun cifraYDescifraConLaLlaveDelKeystore() {
        guardar()
        assertEquals(borrador, almacen.leer(borrador.duenio, borrador.id))
        val leido = almacen.abrirArchivo(borrador.duenio, borrador.id, "ADJ-1").use { it.readBytes() }
        assertArrayEquals(contenido, leido)
        val enDisco = raiz.walkTopDown().filter(File::isFile).map { it.readBytes().toString(Charsets.UTF_8) }
        val secretos = listOf("calle 72", "POL-2026", "4.6573", "contenido de la foto")
        assertTrue(enDisco.none { texto -> secretos.any { it in texto } })
    }

    @Test
    fun unArchivoAlteradoNoSeDescifra() {
        guardar()
        val manifiesto = File(raiz, "${borrador.duenio}/${borrador.id}/borrador.bin")
        val bytes = manifiesto.readBytes()
        bytes[bytes.size - 4] = (bytes[bytes.size - 4].toInt() xor 1).toByte()
        manifiesto.writeBytes(bytes)
        try {
            almacen.leer(borrador.duenio, borrador.id)
            fail("Se descifró un archivo alterado")
        } catch (ilegible: BorradorIlegible) {
            assertTrue(ilegible.permanente)
        }
    }

    @Test
    fun descifraConLaPantallaBloqueada() {
        guardar()
        ejecutar("input keyevent KEYCODE_SLEEP")
        Thread.sleep(PAUSA_BLOQUEO_MS)
        assertEquals(borrador, almacen.leer(borrador.duenio, borrador.id))
    }

    @Test
    fun alGuardarNoQuedanArchivosEnClaroEnLaCache() = runBlocking {
        val cache = File(File(contexto.cacheDir, "evidencias").apply { mkdirs() }, "evidencia-1.jpg")
        cache.writeBytes(contenido)
        val sinRed = object : MonitorConectividad {
            override val conectado = MutableStateFlow(false)
        }
        // Sin red el enviador no llama al BFF: un proxy que falla si lo intenta.
        val api = Proxy.newProxyInstance(BffApi::class.java.classLoader, arrayOf(BffApi::class.java)) { _, _, _ ->
            error("No debe llamar al BFF sin red")
        } as BffApi
        val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val enviador = EnviadorBorradores(api, almacen, sinRed, { borrador.duenio }, alcance)
        val resultado = enviador.enviarDesdeAsistente(
            borrador.solicitud,
            borrador.claveIdempotencia,
            listOf(AdjuntoNuevo(borrador.adjuntos.single(), cache)),
        )
        alcance.cancel()
        assertEquals(ResultadoEnvio.SinRed, resultado)
        assertFalse(cache.exists())
        val enCache = contexto.cacheDir.walkTopDown().filter(File::isFile)
        assertTrue(enCache.none { it.readBytes().contentEquals(contenido) })
    }

    private companion object {
        const val PAUSA_BLOQUEO_MS = 1500L
    }
}
