package co.solventa.feature.siniestros.evidencia

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Criterio 11: lo que sale de la app no lleva coordenadas en sus metadatos. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LimpiarExifTest {
    @get:Rule
    val carpeta = TemporaryFolder()

    private val jpeg16x16 = Base64.getDecoder().decode(
        "/9j/4AAQSkZJRgABAgAAAQABAAD//gAQTGF2YzYwLjMxLjEwMgD/2wBDAAgQEBMQExYWFhYWFhoYGhsbGxoaGhobGxsdHR0iIiId" +
        "HR0bGx0dICAiIiUmJSMjIiMmJigoKDAwLi44ODpFRVP/xABMAAEBAAAAAAAAAAAAAAAAAAAABgEBAQAAAAAAAAAAAAAAAAAABgcQ" +
        "AQAAAAAAAAAAAAAAAAAAAAARAQAAAAAAAAAAAAAAAAAAAAD/wAARCAAQABADASIAAhEAAxEA/9oADAMBAAIRAxEAPwCLAFF/f//Z",
    )

    private fun fotoConUbicacion(): File = File(carpeta.root, "foto.jpg").apply {
        writeBytes(jpeg16x16)
        ExifInterface(this).run {
            setLatLong(4.711, -74.0721)
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
    }

    @Test
    fun `quita las coordenadas y conserva la orientacion`() {
        val foto = fotoConUbicacion()
        assertNotNull(ExifInterface(foto).latLong)

        assertTrue(limpiarExif(foto))

        val exif = ExifInterface(foto)
        assertNull(exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertEquals(90, exif.rotationDegrees)
    }

    @Test
    fun `si no puede reescribir el archivo lo avisa para volver a codificarlo`() {
        assertFalse(limpiarExif(File(carpeta.root, "no-existe.jpg")))
    }
}
