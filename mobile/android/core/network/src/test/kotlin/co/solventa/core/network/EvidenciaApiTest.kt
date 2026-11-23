package co.solventa.core.network

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EvidenciaApiTest {
    private val servidor = MockWebServer()

    @Before
    fun iniciar() = servidor.start()

    @After
    fun detener() = servidor.shutdown()

    private val evidencia =
        """{"id":"EVI-1","tipo_contenido":"image/jpeg","tamano":3,"nombre_original":"e.jpg","canal":"movil",""" +
            """"cargada_en":"2026-10-26T15:00:00Z","latitud":4.7,"longitud":-74.0}"""

    @Test
    fun `adjunta el archivo en multipart con su nombre, su tipo y campos sin comillas`() = runBlocking<Unit> {
        servidor.enqueue(MockResponse().setResponseCode(201).setBody(evidencia))
        val api = NetworkClient.create(servidor.url("/api/v1/movil/").toString())
        val archivo = File.createTempFile("evidencia", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val resultado = api.adjuntarEvidencia(
            "SIN-1",
            parteArchivo(archivo, "evidencia-1.jpg", "image/jpeg"),
            parteTexto("2026-10-26T10:15:00-05:00"),
            parteTexto("4.7"),
            parteTexto("-74.0"),
            parteTexto(null),
        )

        val solicitud = servidor.takeRequest()
        val cuerpo = solicitud.body.readUtf8()
        assertEquals("/api/v1/movil/siniestros/SIN-1/evidencias", solicitud.path)
        assertTrue(solicitud.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data"))
        assertTrue("name=\"archivo\"; filename=\"evidencia-1.jpg\"" in cuerpo)
        assertTrue("Content-Type: image/jpeg" in cuerpo)
        assertTrue("name=\"tomada_en\"" in cuerpo)
        assertTrue("\r\n2026-10-26T10:15:00-05:00\r\n" in cuerpo)
        assertFalse("name=\"lugar\"" in cuerpo)
        assertFalse("sin requerimiento la carga es la de siempre", "name=\"id_requerimiento\"" in cuerpo)
        assertEquals("EVI-1", resultado.id)
        assertEquals(4.7, resultado.latitud)
        archivo.delete()
    }

    @Test
    fun `lista las evidencias del siniestro`() = runBlocking<Unit> {
        servidor.enqueue(MockResponse().setBody("[$evidencia]"))
        val api = NetworkClient.create(servidor.url("/api/v1/movil/").toString())
        assertEquals("e.jpg", api.listarEvidencias("SIN-1").single().nombreOriginal)
    }

    @Test
    fun `solo la carga tiene mas tiempo que las demas operaciones`() {
        val base = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
        val fabrica = conCargas(base)
        val url = "https://bff.prueba/"
        val json = Request.Builder().url(url).post("{}".toRequestBody("application/json".toMediaType())).build()
        val carga = Request.Builder().url(url).post(
            MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("lugar", "x").build(),
        ).build()

        assertEquals(TimeUnit.SECONDS.toNanos(15), fabrica.newCall(json).timeout().timeoutNanos())
        assertEquals(TimeUnit.MINUTES.toNanos(3), fabrica.newCall(carga).timeout().timeoutNanos())
    }

    @Test
    fun `responder un requerimiento agrega la parte id_requerimiento sin comillas`() = runBlocking<Unit> {
        servidor.enqueue(MockResponse().setResponseCode(201).setBody(evidencia))
        val api = NetworkClient.create(servidor.url("/api/v1/movil/").toString())
        val archivo = File.createTempFile("evidencia", ".pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        api.adjuntarEvidencia(
            "SIN-1",
            parteArchivo(archivo, "factura.pdf", "application/pdf"),
            tomadaEn = null,
            latitud = null,
            longitud = null,
            lugar = null,
            idRequerimiento = parteTexto("REQ-1"),
        )

        val cuerpo = servidor.takeRequest().body.readUtf8()
        assertTrue("name=\"id_requerimiento\"" in cuerpo)
        assertTrue("\r\nREQ-1\r\n" in cuerpo)
        archivo.delete()
    }

    @Test
    fun `obtiene el detalle con historial y requerimientos`() = runBlocking<Unit> {
        servidor.enqueue(MockResponse().setBody(DETALLE))
        val api = NetworkClient.create(servidor.url("/api/v1/movil/").toString())

        val detalle = api.obtenerSiniestro("SIN-1")

        assertEquals("/api/v1/movil/siniestros/SIN-1", servidor.takeRequest().path)
        assertEquals(EstadoSiniestro.REQUIERE_INFORMACION, detalle.estado)
        assertEquals("2026-10-21T09:00:00Z", detalle.estadoActualizadoEn)
        assertNull(detalle.historial.first().estadoAnterior)
        assertEquals("Falta la factura", detalle.historial.last().motivo)
        val requerimiento = detalle.requerimientos.single()
        assertTrue(requerimiento.pendiente)
        assertNull(requerimiento.atendidoEn)
        assertNull(detalle.montoEstimado)
        assertEquals(detalle.estado, detalle.resumen.estado)
    }

    @Test
    fun `estado_actualizado_en se acepta ausente o null`() {
        val base = """{"id":"SIN-1","referencia":"SIN-2026-000001","numero_poliza":"POL-1","tipo_evento":"hurto",""" +
            """"ocurrido_el":"2026-10-20","descripcion":"x","monto_estimado":null,"estado":"reportado",""" +
            """"siguiente_paso":"adjuntar_evidencia","canal":"web","reportado_en":"2026-10-20T14:00:00Z""""
        val ausente = NetworkClient.json.decodeFromString(Siniestro.serializer(), "$base}")
        val nulo = NetworkClient.json.decodeFromString(Siniestro.serializer(), "$base,\"estado_actualizado_en\":null}")
        assertNull(ausente.estadoActualizadoEn)
        assertNull(nulo.estadoActualizadoEn)
    }

    private companion object {
        val DETALLE = """
            {"id":"SIN-1","referencia":"SIN-2026-000001","numero_poliza":"POL-1","tipo_evento":"dano_hogar",
             "ocurrido_el":"2026-10-19","descripcion":"Tubo roto","monto_estimado":null,
             "estado":"requiere_informacion","siguiente_paso":"adjuntar_evidencia","canal":"web",
             "reportado_en":"2026-10-20T14:00:00Z","estado_actualizado_en":"2026-10-21T09:00:00Z",
             "historial":[
               {"id":"E1","estado_anterior":null,"estado":"reportado","ocurrido_en":"2026-10-20T14:00:00Z","motivo":null},
               {"id":"E2","estado_anterior":"reportado","estado":"requiere_informacion",
                "ocurrido_en":"2026-10-21T09:00:00Z","motivo":"Falta la factura"}],
             "requerimientos":[
               {"id":"R1","descripcion":"Factura de la reparación","creado_en":"2026-10-21T09:00:00Z",
                "pendiente":true,"atendido_en":null,"id_evidencia":null}]}
        """.trimIndent()
    }
}
