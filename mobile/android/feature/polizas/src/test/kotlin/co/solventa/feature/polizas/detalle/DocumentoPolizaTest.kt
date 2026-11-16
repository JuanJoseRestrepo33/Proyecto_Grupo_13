package co.solventa.feature.polizas.detalle

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentoPolizaTest {
    private val contexto: android.app.Application = ApplicationProvider.getApplicationContext()
    private val carpeta = File(contexto.cacheDir, "polizas")

    @Test
    fun `el PDF queda en la cache de la app con el numero como nombre`() {
        val ruta = DocumentosPolizaAndroid(contexto).guardar("POL-2026-001101", ByteArrayInputStream(PDF))

        assertEquals(File(carpeta, "POL-2026-001101.pdf").path, ruta)
        assertArrayEquals(PDF, File(ruta).readBytes())
        assertEquals("sin archivos parciales", listOf("POL-2026-001101.pdf"), carpeta.list()?.toList())
    }

    @Test
    fun `una descarga cortada no deja un PDF incompleto ni reemplaza el anterior`() {
        val documentos = DocumentosPolizaAndroid(contexto)
        documentos.guardar("POL-2026-001101", ByteArrayInputStream(PDF))
        val cortado = object : InputStream() {
            private var leidos = 0

            override fun read(): Int = if (leidos++ < 3) 'x'.code else throw IOException("se cortó")
        }

        assertThrows(IOException::class.java) { documentos.guardar("POL-2026-001101", cortado) }

        assertArrayEquals(PDF, File(carpeta, "POL-2026-001101.pdf").readBytes())
        assertEquals(listOf("POL-2026-001101.pdf"), carpeta.list()?.toList())
    }

    @Test
    fun `el nombre del archivo no admite rutas ni caracteres raros`() {
        assertEquals("POL-2026-001101.pdf", nombreArchivo("POL-2026-001101"))
        assertEquals("______x.pdf", nombreArchivo("../../x"))
    }

    /**
     * Una sola prueba con FileProvider: guarda la carpeta raíz en una caché estática y Robolectric cambia
     * la carpeta de datos en cada prueba.
     */
    @Test
    fun `el visor abre el PDF por FileProvider con permiso de lectura temporal y sin visor responde false`() {
        val ruta = DocumentosPolizaAndroid(contexto).guardar("POL-2026-001101", ByteArrayInputStream(PDF))

        assertTrue(VisorPdfAndroid(contexto).abrir(ruta))

        val intento = shadowOf(contexto).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intento.action)
        assertEquals("application/pdf", intento.type)
        assertEquals("content", intento.data?.scheme)
        assertEquals("${contexto.packageName}.polizas.documentos", intento.data?.authority)
        assertTrue(intento.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        shadowOf(contexto).checkActivities(true)

        assertFalse(VisorPdfAndroid(contexto).abrir(ruta))
    }

    private companion object {
        val PDF = "%PDF-1.4 prueba".toByteArray()
    }
}
