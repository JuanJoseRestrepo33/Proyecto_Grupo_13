package co.solventa.feature.polizas.billetera

import co.solventa.core.almacen.ArchivoCifrado
import co.solventa.core.almacen.CifradoAesGcm
import co.solventa.core.almacen.PuertoCifrado
import co.solventa.feature.polizas.CLIENTE
import co.solventa.feature.polizas.LlaveJca
import co.solventa.feature.polizas.OTRO_CLIENTE
import co.solventa.feature.polizas.billeteraDePrueba
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AlmacenBilleteraTest {
    @get:Rule
    val carpeta = TemporaryFolder()

    private val llave = LlaveJca()
    private lateinit var archivo: File
    private lateinit var almacen: AlmacenBilletera

    @Before
    fun preparar() {
        archivo = File(File(carpeta.root, "billetera"), "billetera.bin")
        almacen = AlmacenBilletera(ArchivoCifrado(archivo, CifradoAesGcm(llave)), Dispatchers.Unconfined)
    }

    private fun leer(cliente: String = CLIENTE) = runBlocking { almacen.leer { it == cliente } }

    @Test
    fun `guarda y lee la billetera cifrada, sin datos en claro en el disco`() = runBlocking {
        val billetera = billeteraDePrueba()

        assertTrue(almacen.guardarSi(billetera) { true })

        assertEquals(billetera, leer())
        val enDisco = archivo.readBytes().toString(Charsets.ISO_8859_1)
        listOf("POL-2026", "Ana María", CLIENTE, "Responsabilidad civil").forEach { assertFalse(it in enDisco) }
        assertFalse("no queda el temporal", File(archivo.parentFile, "billetera.bin.tmp").exists())
    }

    @Test
    fun `una billetera sin polizas tambien se guarda`() = runBlocking {
        val vacia = billeteraDePrueba(polizas = emptyList(), detalles = emptyList())

        almacen.guardarSi(vacia) { true }

        assertEquals(vacia, leer())
    }

    @Test
    fun `no escribe si la condicion ya no se cumple dentro del candado`() = runBlocking {
        assertFalse(almacen.guardarSi(billeteraDePrueba()) { false })
        assertFalse(archivo.exists())
    }

    @Test
    fun `un corte al escribir deja la billetera anterior entera`() = runBlocking {
        val anterior = billeteraDePrueba()
        almacen.guardarSi(anterior) { true }
        val corte = ArchivoCifrado(archivo, CorteAlEscribir(CifradoAesGcm(llave)))
        val cortado = AlmacenBilletera(corte, Dispatchers.Unconfined)

        assertFalse(cortado.guardarSi(billeteraDePrueba(polizas = emptyList(), detalles = emptyList())) { true })

        assertEquals(anterior, leer())
        assertFalse(File(archivo.parentFile, "billetera.bin.tmp").exists())
    }

    @Test
    fun `un archivo alterado se trata como sin billetera y se borra`() = runBlocking {
        almacen.guardarSi(billeteraDePrueba()) { true }
        val bytes = archivo.readBytes()
        bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 1).toByte()
        archivo.writeBytes(bytes)

        assertNull(leer())
        assertFalse(archivo.exists())
    }

    @Test
    fun `sin la llave del Keystore la billetera es ilegible y se borra`() = runBlocking {
        almacen.guardarSi(billeteraDePrueba()) { true }
        llave.borrada = true

        assertNull(leer())
        assertFalse(archivo.exists())
    }

    @Test
    fun `un archivo truncado o basura no cierra la app`() {
        archivo.parentFile!!.mkdirs()
        archivo.writeBytes(byteArrayOf(1, 12, 3))

        assertNull(leer())
        assertFalse(archivo.exists())
    }

    @Test
    fun `hay una sola billetera por telefono, la del ultimo que sincronizo`() = runBlocking {
        almacen.guardarSi(billeteraDePrueba(cliente = CLIENTE)) { true }
        almacen.guardarSi(billeteraDePrueba(cliente = OTRO_CLIENTE)) { true }

        assertEquals(OTRO_CLIENTE, leer(OTRO_CLIENTE)?.clienteId)
        assertEquals(1, archivo.parentFile!!.listFiles()!!.size)
    }

    @Test
    fun `la billetera de otro duenio nunca se muestra y se borra`() = runBlocking {
        almacen.guardarSi(billeteraDePrueba(cliente = OTRO_CLIENTE)) { true }

        assertNull(leer(CLIENTE))
        assertFalse(archivo.exists())
    }

    @Test
    fun `al entrar otra cuenta se borra la billetera de otro duenio y se conserva la propia`() = runBlocking {
        almacen.guardarSi(billeteraDePrueba(cliente = CLIENTE)) { true }

        almacen.borrarSiNoEsDe(CLIENTE)
        assertTrue(archivo.exists())

        almacen.borrarSiNoEsDe(OTRO_CLIENTE)
        assertFalse(archivo.exists())
    }

    /** Escribe la mitad y corta, como un corte de energía a mitad de la escritura. */
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

    @Test
    fun `un fallo del Keystore al escribir deja la billetera anterior sin cerrar la app`() = runBlocking {
        val anterior = billeteraDePrueba()
        almacen.guardarSi(anterior) { true }
        val fallido = conKeystoreRoto(alCifrar = true)

        assertFalse(fallido.guardarSi(billeteraDePrueba(cliente = OTRO_CLIENTE)) { true })

        assertEquals(anterior, leer())
    }

    @Test
    fun `un fallo del Keystore al leer se trata como sin billetera y se borra`() = runBlocking {
        almacen.guardarSi(billeteraDePrueba()) { true }
        val fallido = conKeystoreRoto(alCifrar = false)

        assertNull(fallido.leer { true })

        assertFalse(archivo.exists())
    }

    private fun conKeystoreRoto(alCifrar: Boolean) =
        AlmacenBilletera(ArchivoCifrado(archivo, KeystoreRoto(CifradoAesGcm(llave), alCifrar)), Dispatchers.Unconfined)

    /** El Keystore de algunos equipos lanza ProviderException (no verificada) al usar la llave. */
    private class KeystoreRoto(private val real: PuertoCifrado, private val alCifrar: Boolean) : PuertoCifrado {
        override fun cifrar(destino: OutputStream): OutputStream =
            if (alCifrar) throw ProviderException("Keystore no disponible") else real.cifrar(destino)

        override fun descifrar(origen: InputStream): InputStream =
            if (alCifrar) real.descifrar(origen) else throw ProviderException("Keystore no disponible")
    }
}
