package co.solventa.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificacionTest {
    private fun leer(datos: String) = NetworkClient.json.decodeFromString(
        Notificacion.serializer(),
        """{"id":"N-1","titulo":"t","cuerpo":"c","datos":$datos,"creada_en":null,"leida":false}""",
    )

    @Test
    fun `un cambio de estado abre su siniestro`() {
        val notificacion = leer("""{"tipo":"siniestro_estado","id_siniestro":"SIN-1","estado":"aprobado"}""")

        assertEquals("SIN-1", notificacion.idSiniestro)
        assertNull(notificacion.creadaEn)
    }

    @Test
    fun `otro tipo, sin id o con un id que no es texto no abre nada`() {
        assertNull(leer("""{"tipo":"poliza_vence","id_siniestro":"SIN-1"}""").idSiniestro)
        assertNull(leer("""{"tipo":"siniestro_estado"}""").idSiniestro)
        assertNull(leer("""{"tipo":"siniestro_estado","id_siniestro":""}""").idSiniestro)
        assertNull(leer("""{"tipo":"siniestro_estado","id_siniestro":7}""").idSiniestro)
    }

    @Test
    fun `los campos nuevos de datos no rompen la lectura`() {
        val notificacion = leer("""{"tipo":"siniestro_estado","id_siniestro":"SIN-1","extra":{"a":1}}""")

        assertEquals("SIN-1", notificacion.idSiniestro)
    }
}
