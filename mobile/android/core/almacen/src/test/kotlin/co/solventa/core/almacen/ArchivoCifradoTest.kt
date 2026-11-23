package co.solventa.core.almacen

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchivoCifradoTest {
    @get:Rule
    val carpeta = TemporaryFolder()

    private val llave = LlaveJca()
    private lateinit var destino: File
    private lateinit var archivo: ArchivoCifrado

    @Before
    fun preparar() {
        destino = File(File(carpeta.root, "billetera"), "billetera.bin")
        archivo = ArchivoCifrado(destino, CifradoAesGcm(llave))
    }

    private val contenido = "Seguro de Auto — POL-2026-001101".toByteArray()

    @Test
    fun `escribe cifrado, crea la carpeta y lee el contenido en claro`() {
        archivo.escribir(contenido)

        assertTrue(archivo.existe)
        assertArrayEquals(contenido, archivo.leer())
        assertFalse("POL-2026" in destino.readBytes().toString(Charsets.ISO_8859_1))
    }

    @Test
    fun `sin archivo no hay contenido`() {
        assertNull(archivo.leer())
        assertFalse(archivo.existe)
    }

    @Test
    fun `reemplazar deja solo el contenido nuevo y ningun temporal`() {
        archivo.escribir(contenido)
        archivo.escribir("otro".toByteArray())

        assertArrayEquals("otro".toByteArray(), archivo.leer())
        assertEquals(listOf("billetera.bin"), destino.parentFile!!.list()!!.toList())
    }

    @Test
    fun `un corte al escribir deja el contenido anterior`() {
        archivo.escribir(contenido)
        val cortado = ArchivoCifrado(destino, CorteAlEscribir(CifradoAesGcm(llave)))

        try {
            cortado.escribir("nuevo".toByteArray())
            fail("El corte debía propagarse")
        } catch (esperado: IOException) {
            assertEquals("corte", esperado.message)
        }

        assertArrayEquals(contenido, archivo.leer())
        assertFalse(File(destino.parentFile, "billetera.bin.tmp").exists())
    }

    @Test
    fun `un archivo alterado es ilegible de forma permanente`() {
        archivo.escribir(contenido)
        val bytes = destino.readBytes()
        bytes[bytes.size - 2] = (bytes[bytes.size - 2].toInt() xor 1).toByte()
        destino.writeBytes(bytes)

        assertIlegible(permanente = true) { archivo.leer() }
    }

    @Test
    fun `una version desconocida o un encabezado truncado son ilegibles`() {
        destino.parentFile!!.mkdirs()
        destino.writeBytes(byteArrayOf(9, 12))
        assertIlegible(permanente = true) { archivo.leer() }

        destino.writeBytes(byteArrayOf(1, 12, 1, 2))
        assertIlegible(permanente = true) { archivo.leer() }

        destino.writeBytes(byteArrayOf(1, 0))
        assertIlegible(permanente = true) { archivo.leer() }
    }

    @Test
    fun `sin la llave es ilegible de forma permanente y un error del Keystore es temporal`() {
        archivo.escribir(contenido)

        llave.fallo = KeyStoreException("Keystore ocupado")
        assertIlegible(permanente = false) { archivo.leer() }

        llave.fallo = null
        llave.borrada = true
        assertIlegible(permanente = true) { archivo.leer() }
    }

    @Test
    fun `borrar quita el archivo y un temporal que haya dejado un corte`() {
        archivo.escribir(contenido)
        File(destino.parentFile, "billetera.bin.tmp").writeText("a medias")

        archivo.borrar()

        assertFalse(destino.exists())
        assertFalse(File(destino.parentFile, "billetera.bin.tmp").exists())
    }

    @Test
    fun `el cifrado en flujo no cierra el destino`() {
        val destino = object : ByteArrayOutputStream() {
            var cerrado = false

            override fun close() {
                cerrado = true
            }
        }
        CifradoAesGcm(llave).cifrar(destino).use { it.write(contenido) }

        assertFalse(destino.cerrado)
        val leido = CifradoAesGcm(llave)
            .descifrar(ByteArrayInputStream(destino.toByteArray()))
            .use(InputStream::readBytes)
        assertArrayEquals(contenido, leido)
    }

    @Test
    fun `clasificar reconoce la causa permanente aunque venga envuelta`() {
        assertTrue(clasificar(IOException(LlaveInexistente())).permanente)
        assertFalse(clasificar(IOException("disco")).permanente)
        val ya = ArchivoIlegible(permanente = true)
        assertEquals(ya, clasificar(ya))
    }

    @Test
    fun `el monitor siempre conectado sirve para pruebas y vistas previas`() {
        assertTrue(MonitorConectividad.Siempre.conectado.value)
    }

    private fun assertIlegible(permanente: Boolean, leer: () -> Unit) {
        try {
            leer()
            fail("Se leyó un archivo ilegible")
        } catch (ilegible: ArchivoIlegible) {
            assertEquals(permanente, ilegible.permanente)
        }
    }

    private class LlaveJca : FuenteLlave {
        private val llave: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var borrada = false
        var fallo: Exception? = null

        override fun paraCifrar(): SecretKey = llave

        override fun paraDescifrar(): SecretKey {
            fallo?.let { throw it }
            return if (borrada) throw LlaveInexistente() else llave
        }
    }

    private class CorteAlEscribir(private val real: PuertoCifrado) : PuertoCifrado {
        override fun cifrar(destino: OutputStream): OutputStream {
            val cifrado = real.cifrar(destino)
            return object : OutputStream() {
                override fun write(b: Int) = throw IOException("corte")

                override fun write(b: ByteArray, off: Int, len: Int) {
                    cifrado.write(b, off, len / 2)
                    throw IOException("corte")
                }
            }
        }

        override fun descifrar(origen: InputStream): InputStream = real.descifrar(origen)
    }
}
