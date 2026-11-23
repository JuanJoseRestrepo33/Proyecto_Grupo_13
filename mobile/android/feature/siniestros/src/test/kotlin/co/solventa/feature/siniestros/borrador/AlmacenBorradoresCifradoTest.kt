package co.solventa.feature.siniestros.borrador

import co.solventa.core.network.Dinero
import co.solventa.core.network.SolicitudSiniestro
import co.solventa.core.network.TipoEventoSiniestro
import co.solventa.feature.siniestros.CLIENTE
import co.solventa.feature.siniestros.CifradoQueFalla
import co.solventa.feature.siniestros.LlaveJca
import co.solventa.feature.siniestros.OTRO_CLIENTE
import co.solventa.feature.siniestros.POLIZA_VIGENTE
import co.solventa.feature.siniestros.evidencia.FormatoEvidencia
import java.io.File
import java.io.IOException
import java.security.KeyStoreException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AlmacenBorradoresCifradoTest {
    @get:Rule
    val carpeta = TemporaryFolder()

    private val llave = LlaveJca()
    private val cifrado = CifradoQueFalla(CifradoAesGcm(llave))
    private lateinit var raiz: File
    private lateinit var almacen: AlmacenBorradoresCifrado

    private val solicitud = SolicitudSiniestro(
        numeroPoliza = POLIZA_VIGENTE.numero,
        tipoEvento = TipoEventoSiniestro.HURTO,
        ocurridoEl = "2026-10-23",
        descripcion = "Me robaron el celular en la calle 72",
        montoEstimado = Dinero.pesos(1_500_000),
    )
    private val adjunto = AdjuntoBorrador(
        id = "ADJ-1",
        nombre = "evidencia-1.jpg",
        formato = FormatoEvidencia.JPEG,
        tamano = 22,
        tomadaEn = "2026-10-23T10:00:00-05:00",
        latitud = 4.6573,
        longitud = -74.0558,
        lugar = "Calle 72 con séptima",
    )
    private val contenido = "JPEG con datos privados".toByteArray()

    private fun borrador(id: String = "B-1", duenio: String = CLIENTE, creadoEn: String = "2026-10-26T15:00:00Z") =
        Borrador(id, duenio, solicitud, "clave-1", listOf(adjunto), creadoEn = creadoEn)

    private fun enClaro(): File = carpeta.newFile().apply { writeBytes(contenido) }

    @Before
    fun preparar() {
        raiz = carpeta.newFolder("borradores")
        almacen = AlmacenBorradoresCifrado(raiz, cifrado)
    }

    @Test
    fun `guarda y lee el borrador con su evidencia`() {
        almacen.guardarNuevo(borrador(), mapOf("ADJ-1" to enClaro()))
        assertEquals(borrador(), almacen.leer(CLIENTE, "B-1"))
        val leido = almacen.abrirArchivo(CLIENTE, "B-1", "ADJ-1").use { it.readBytes() }
        assertArrayEquals(contenido, leido)
    }

    @Test
    fun `en disco no aparece en claro la descripcion, las coordenadas, el lugar, la poliza ni el archivo`() {
        almacen.guardarNuevo(borrador(), mapOf("ADJ-1" to enClaro()))
        almacen.guardarPolizas(CLIENTE, PolizasGuardadas(listOf(POLIZA_VIGENTE), "2026-10-26T15:00:00Z"))
        val secretos = listOf(
            "calle 72",
            "4.6573",
            "-74.0558",
            "séptima",
            "POL-2026-000101",
            "datos privados",
            "clave-1",
        )
        raiz.walkTopDown().filter(File::isFile).forEach { archivo ->
            val texto = archivo.readBytes().toString(Charsets.UTF_8).lowercase()
            secretos.forEach { secreto ->
                assertFalse("${archivo.name} contiene «$secreto»", secreto.lowercase() in texto)
            }
        }
    }

    @Test
    fun `cada archivo lleva su propio IV`() {
        almacen.guardarNuevo(borrador(), mapOf("ADJ-1" to enClaro()))
        almacen.guardarNuevo(borrador(id = "B-2"), mapOf("ADJ-1" to enClaro()))
        val uno = File(raiz, "$CLIENTE/B-1/ADJ-1.bin").readBytes()
        val dos = File(raiz, "$CLIENTE/B-2/ADJ-1.bin").readBytes()
        assertFalse(uno.contentEquals(dos))
    }

    @Test
    fun `un archivo alterado no se descifra y el fallo es permanente`() {
        almacen.guardarNuevo(borrador(), mapOf("ADJ-1" to enClaro()))
        val manifiesto = File(raiz, "$CLIENTE/B-1/borrador.bin")
        val bytes = manifiesto.readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 0x01).toByte()
        manifiesto.writeBytes(bytes)
        val error = assertThrows(BorradorIlegible::class.java) { almacen.leer(CLIENTE, "B-1") }
        assertTrue(error.permanente)
        assertEquals(listOf(EntradaBorrador.Ilegible("B-1", permanente = true)), almacen.listar(CLIENTE))
        // Nunca se descarta solo.
        assertTrue(manifiesto.exists())

        val evidencia = File(raiz, "$CLIENTE/B-1/ADJ-1.bin")
        evidencia.writeBytes(evidencia.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        val enArchivo = assertThrows(BorradorIlegible::class.java) { almacen.verificarArchivo(CLIENTE, "B-1", "ADJ-1") }
        assertTrue(enArchivo.permanente)
    }

    @Test
    fun `sin la llave el borrador queda ilegible de forma permanente`() {
        almacen.guardarNuevo(borrador(), emptyMap())
        llave.llave = null
        assertTrue(assertThrows(BorradorIlegible::class.java) { almacen.leer(CLIENTE, "B-1") }.permanente)
    }

    @Test
    fun `un error del Keystore es temporal y el borrador se conserva`() {
        almacen.guardarNuevo(borrador(), emptyMap())
        llave.fallo = KeyStoreException("Keystore ocupado")
        assertEquals(listOf(EntradaBorrador.Ilegible("B-1", permanente = false)), almacen.listar(CLIENTE))
        llave.fallo = null
        assertEquals(borrador(), almacen.leer(CLIENTE, "B-1"))
    }

    @Test
    fun `si falla la escritura no queda borrador y el archivo en claro sigue donde estaba`() {
        val original = enClaro()
        cifrado.bytes = 10
        assertThrows(IOException::class.java) { almacen.guardarNuevo(borrador(), mapOf("ADJ-1" to original)) }
        assertTrue(almacen.listar(CLIENTE).isEmpty())
        assertFalse(File(raiz, "$CLIENTE/B-1").exists())
        assertTrue(original.exists())
    }

    @Test
    fun `una actualizacion que falla deja la version anterior`() {
        almacen.guardarNuevo(borrador(), emptyMap())
        cifrado.bytes = 10
        assertThrows(IOException::class.java) {
            almacen.actualizar(borrador().copy(estado = EstadoBorrador.ENVIANDO))
        }
        cifrado.bytes = null
        assertEquals(EstadoBorrador.ESPERANDO_CONEXION, almacen.leer(CLIENTE, "B-1").estado)
        assertFalse(File(raiz, "$CLIENTE/B-1/borrador.bin.tmp").exists())
    }

    @Test
    fun `lista en orden de creacion y separado por cliente`() {
        almacen.guardarNuevo(borrador(id = "B-2", creadoEn = "2026-10-26T16:00:00Z"), emptyMap())
        almacen.guardarNuevo(borrador(id = "B-1", creadoEn = "2026-10-26T15:00:00Z"), emptyMap())
        almacen.guardarNuevo(borrador(id = "B-3", duenio = OTRO_CLIENTE), emptyMap())
        assertEquals(listOf("B-1", "B-2"), almacen.listar(CLIENTE).map { it.id })
        assertEquals(listOf("B-3"), almacen.listar(OTRO_CLIENTE).map { it.id })
        assertThrows(BorradorIlegible::class.java) { almacen.leer(OTRO_CLIENTE, "B-1") }
    }

    @Test
    fun `un guardado cortado antes del manifiesto no deja restos`() {
        File(raiz, "$CLIENTE/B-9").mkdirs()
        File(raiz, "$CLIENTE/B-9/ADJ-1.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(almacen.listar(CLIENTE).isEmpty())
        assertFalse(File(raiz, "$CLIENTE/B-9").exists())
    }

    @Test
    fun `la cache de polizas y los registros enviados son de cada cliente`() {
        almacen.guardarPolizas(CLIENTE, PolizasGuardadas(listOf(POLIZA_VIGENTE), "2026-10-26T15:00:00Z"))
        assertEquals(listOf(POLIZA_VIGENTE), almacen.leerPolizas(CLIENTE)?.polizas)
        assertNull(almacen.leerPolizas(OTRO_CLIENTE))
        almacen.guardarEnviado(CLIENTE, RegistroEnviado("B-1", "SIN-2026-000123", "2026-10-26T15:00:00Z"))
        assertEquals("SIN-2026-000123", almacen.listarEnviados(CLIENTE).single().referencia)
        assertTrue(almacen.listarEnviados(OTRO_CLIENTE).isEmpty())
        almacen.borrarEnviado(CLIENTE, "B-1")
        assertTrue(almacen.listarEnviados(CLIENTE).isEmpty())
    }

    @Test
    fun `un identificador con ruta no sale de la carpeta`() {
        assertThrows(IllegalArgumentException::class.java) { almacen.borrar(CLIENTE, "../otro") }
    }
}
