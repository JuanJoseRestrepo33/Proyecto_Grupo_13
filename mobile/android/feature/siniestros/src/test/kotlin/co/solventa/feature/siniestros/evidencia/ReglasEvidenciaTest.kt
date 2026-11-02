package co.solventa.feature.siniestros.evidencia

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReglasEvidenciaTest {
    private fun ftyp(marca: String) = byteArrayOf(0, 0, 0, 32) + "ftyp$marca".toByteArray(Charsets.ISO_8859_1)

    private fun caja(tipo: String, contenido: ByteArray): ByteArray {
        val salida = ByteArrayOutputStream()
        DataOutputStream(salida).apply {
            writeInt(contenido.size + 8)
            write(tipo.toByteArray(Charsets.ISO_8859_1))
            write(contenido)
        }
        return salida.toByteArray()
    }

    @Test
    fun `reconoce el formato por el contenido`() {
        val firma = FormatoEvidencia::desdeFirma
        assertEquals(FormatoEvidencia.JPEG, firma(byteArrayOf(-1, -40, -1, 0)))
        assertEquals(FormatoEvidencia.PNG, firma(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
        assertEquals(FormatoEvidencia.PDF, firma("%PDF-1.7".toByteArray()))
        assertEquals(FormatoEvidencia.MP4, firma(ftyp("isom")))
        assertEquals(FormatoEvidencia.HEIC, firma(ftyp("heic")))
        assertEquals(FormatoEvidencia.HEIC, firma(ftyp("mif1")))
    }

    @Test
    fun `rechaza lo que no es un formato del contrato aunque se disfrace`() {
        val firma = FormatoEvidencia::desdeFirma
        assertNull(firma("GIF89a0000000".toByteArray()))
        assertNull(firma("MZ ejecutable".toByteArray()))
        assertNull(firma(ftyp("avif")))
        assertNull(firma(ftyp("M4A ")))
        assertNull(firma(ByteArray(0)))
    }

    @Test
    fun `el presupuesto de video deja caber el archivo y descarta lo que no seria legible`() {
        val bits = requireNotNull(PresupuestoVideo.bitsPorSegundo(60_000))
        assertTrue((bits + 128_000L) * 60 / 8 < TAMANO_MAXIMO)
        assertEquals(4_000_000, PresupuestoVideo.bitsPorSegundo(1_000))
        assertNull(PresupuestoVideo.bitsPorSegundo(3_600_000))
        assertNull(PresupuestoVideo.bitsPorSegundo(0))
    }

    @Test
    fun `quita la ubicacion del mp4 sin mover el video`() {
        val xyz = caja("©xyz", "+04.7110-074.0721/".toByteArray())
        val mp4 = ftyp("isom") + ByteArray(20) +
            caja("moov", caja("mvhd", ByteArray(4)) + caja("udta", xyz) + caja("trak", caja("meta", xyz))) +
            caja("mdat", byteArrayOf(1, 2, 3))
        val archivo = File.createTempFile("video", ".mp4").apply { writeBytes(mp4) }

        LimpiadorMp4.limpiar(archivo)

        val limpio = archivo.readBytes()
        val texto = String(limpio, Charsets.ISO_8859_1)
        assertEquals(mp4.size, limpio.size)
        assertFalse("04.7110" in texto)
        assertFalse("©xyz" in texto)
        assertTrue("mvhd" in texto)
        assertArrayEquals(byteArrayOf(1, 2, 3), limpio.copyOfRange(limpio.size - 3, limpio.size))
        archivo.delete()
    }

    @Test
    fun `quita los metadatos anidados en cualquier contenedor`() {
        val xmp = caja("uuid", "lat=4.7110;lon=-74.0721".toByteArray())
        val stbl = caja("stbl", caja("stsd", ByteArray(8)) + xmp)
        val mp4 = ftyp("isom") + ByteArray(20) +
            caja("moov", caja("trak", caja("mdia", caja("minf", stbl) + caja("udta", xmp)))) +
            caja("moof", caja("traf", xmp))
        val archivo = File.createTempFile("video", ".mp4").apply { writeBytes(mp4) }

        LimpiadorMp4.limpiar(archivo)

        val texto = String(archivo.readBytes(), Charsets.ISO_8859_1)
        assertFalse("4.7110" in texto)
        assertFalse("uuid" in texto)
        assertTrue("stsd" in texto)
        archivo.delete()
    }

    @Test
    fun `un mp4 con cajas invalidas no se da por limpio`() {
        val roto = caja("moov", ByteArray(4)).also { it[3] = 100 }
        val archivo = File.createTempFile("roto", ".mp4").apply { writeBytes(roto) }
        assertNotNull(assertThrows(IOException::class.java) { LimpiadorMp4.limpiar(archivo) })
        archivo.delete()
    }

    @Test
    fun `clasifica el permiso, incluida la denegacion permanente`() {
        assertEquals(EstadoPermiso.CONCEDIDO, clasificarPermiso(true, false, true))
        assertEquals(EstadoPermiso.SIN_PEDIR, clasificarPermiso(false, false, false))
        assertEquals(EstadoPermiso.NEGADO, clasificarPermiso(false, true, true))
        assertEquals(EstadoPermiso.PERMANENTE, clasificarPermiso(false, false, true))
    }
}
