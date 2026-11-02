package co.solventa.mobile.mock

import co.solventa.core.network.ErrorBff
import co.solventa.core.network.Evidencia
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.Sesion
import co.solventa.core.network.Siniestro
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockEvidenciasTest {
    private val mock = MockBffMovil()
    private val json = NetworkClient.json
    private val token = "Bearer " + json.decodeFromString(
        Sesion.serializer(),
        mock.responder("POST", "sesiones", CREDENCIALES, null).cuerpo,
    ).tokenAcceso
    private val siniestro: Siniestro = run {
        val hoy = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        val polizas = mock.responder("GET", "polizas", "", token).cuerpo
        val numero = Regex("POL-2026-\\d+").find(polizas)!!.value
        val cuerpo = """{"numero_poliza":"$numero","tipo_evento":"hurto","ocurrido_el":"$hoy",""" +
            """"descripcion":"Me robaron el celular"}"""
        val respuesta = mock.responder("POST", "siniestros", cuerpo, token, java.util.UUID.randomUUID().toString())
        json.decodeFromString(Siniestro.serializer(), respuesta.cuerpo)
    }
    private val ruta = "siniestros/${siniestro.id}/evidencias"

    private fun carga(lugar: String? = null, contenido: ByteArray = ByteArray(3), tipo: String = "image/jpeg") =
        mock.responderCarga(ruta, token, CargaMock("e.jpg", tipo, contenido, lugar = lugar))

    private fun lista() = json.decodeFromString(
        ListSerializer(Evidencia.serializer()),
        mock.responder("GET", ruta, "", token).cuerpo,
    )

    private fun codigo(respuesta: RespuestaMock) = json.decodeFromString(ErrorBff.serializer(), respuesta.cuerpo).codigo

    @Test
    fun `la carga correcta queda en el siniestro con canal movil`() {
        assertEquals(201, carga().codigo)
        assertEquals("movil", lista().single().canal)
    }

    @Test
    fun `responde cada rechazo del contrato`() {
        assertEquals("archivo_demasiado_grande", codigo(carga(contenido = ByteArray(10 * 1024 * 1024 + 1))))
        assertEquals("archivo_demasiado_grande", codigo(carga(MARCA_EVIDENCIA_413)))
        assertEquals("archivo_vacio", codigo(carga(contenido = ByteArray(0))))
        assertEquals("formato_no_admitido", codigo(carga(tipo = "image/gif")))
        assertEquals("formato_no_admitido", codigo(carga(MARCA_EVIDENCIA_422)))
        assertEquals("siniestro_cerrado", codigo(carga(MARCA_EVIDENCIA_409)))
        assertEquals("almacenamiento_no_disponible", codigo(carga(MARCA_EVIDENCIA_503)))
        assertTrue(lista().isEmpty())
    }

    @Test
    fun `una respuesta perdida guarda la evidencia una sola vez`() {
        assertTrue(carga(MARCA_EVIDENCIA_PERDIDA).perdida)
        assertEquals("e.jpg", lista().single().nombreOriginal)
    }

    @Test
    fun `un siniestro ajeno o inexistente responde 404 y sin sesion 401`() {
        val carga = CargaMock("e.jpg", "image/jpeg", ByteArray(3))
        assertEquals(404, mock.responderCarga("siniestros/otro/evidencias", token, carga).codigo)
        assertEquals(401, mock.responderCarga(ruta, null, carga).codigo)
        assertFalse(mock.responder("GET", ruta, "", token).perdida)
    }

    private companion object {
        const val CREDENCIALES = """{"correo":"$CORREO_DEMO","contrasena":"$CONTRASENA_DEMO"}"""
    }
}
