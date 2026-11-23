package co.solventa.feature.siniestros.seguimiento

import co.solventa.core.network.EstadoSiniestro
import co.solventa.core.network.SiguientePaso
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReglasSeguimientoTest {
    // ── Siguiente paso: una prueba por fila de la regla, en su orden ──────────────────────────────

    @Test
    fun `rechazado cierra el caso con el motivo del ultimo evento aunque el contrato diga esperar`() {
        val caso = detalle(
            estado = EstadoSiniestro.RECHAZADO,
            siguiente = SiguientePaso.ESPERAR_EVALUACION,
            historial = listOf(
                evento(EstadoSiniestro.REPORTADO, null, "2026-10-20T14:00:00Z"),
                evento(EstadoSiniestro.RECHAZADO, EstadoSiniestro.REPORTADO, "2026-10-22T14:00:00Z", "No cubierto", 2),
            ),
        )
        assertEquals(PasoSeguimiento.Rechazado("No cubierto"), siguientePaso(caso))
    }

    @Test
    fun `rechazado sin motivo cierra el caso sin motivo`() {
        val caso = detalle(estado = EstadoSiniestro.RECHAZADO, siguiente = SiguientePaso.ESPERAR_EVALUACION)
        assertEquals(PasoSeguimiento.Rechazado(null), siguientePaso(caso))
    }

    @Test
    fun `pagado cierra el caso aunque el contrato diga esperar evaluacion`() {
        val caso = detalle(estado = EstadoSiniestro.PAGADO, siguiente = SiguientePaso.ESPERAR_EVALUACION)
        assertEquals(PasoSeguimiento.Pagado, siguientePaso(caso))
    }

    @Test
    fun `requiere informacion con adjuntar evidencia pide responder los requerimientos`() {
        assertEquals(PasoSeguimiento.ResponderRequerimientos, siguientePaso(requiereInformacion()))
    }

    @Test
    fun `adjuntar evidencia en otro estado ofrece adjuntar`() {
        val caso = detalle(estado = EstadoSiniestro.REPORTADO, siguiente = SiguientePaso.ADJUNTAR_EVIDENCIA)
        assertEquals(PasoSeguimiento.AdjuntarEvidencia, siguientePaso(caso))
    }

    @Test
    fun `esperar evaluacion en otro estado avisa que se notificara el cambio`() {
        listOf(EstadoSiniestro.EN_EVALUACION, EstadoSiniestro.APROBADO, EstadoSiniestro.REQUIERE_INFORMACION)
            .forEach { estado ->
                val caso = detalle(estado = estado, siguiente = SiguientePaso.ESPERAR_EVALUACION)
                assertEquals(estado.name, PasoSeguimiento.EsperarEvaluacion, siguientePaso(caso))
            }
    }

    // ── Requerimientos ───────────────────────────────────────────────────────────────────────

    @Test
    fun `los tres estados de un requerimiento y solo el pendiente se responde`() {
        assertEquals(EstadoRequerimiento.PENDIENTE, pendiente().estadoMostrado)
        assertEquals(EstadoRequerimiento.ATENDIDO, atendido().estadoMostrado)
        assertEquals(EstadoRequerimiento.CERRADO_SIN_RESPUESTA, cerradoSinRespuesta().estadoMostrado)
        assertTrue(pendiente().respondible)
        assertFalse(atendido().respondible)
        assertFalse(cerradoSinRespuesta().respondible)
    }

    // ── Catálogo de estados ──────────────────────────────────────────────────────────────────

    @Test
    fun `insignias y fin del caso siguen el catalogo de estados de los dos canales`() {
        val esperado = mapOf(
            EstadoSiniestro.REPORTADO to (TonoInsignia.AMBAR to false),
            EstadoSiniestro.EN_EVALUACION to (TonoInsignia.AMBAR to false),
            EstadoSiniestro.REQUIERE_INFORMACION to (TonoInsignia.AMBAR to false),
            EstadoSiniestro.APROBADO to (TonoInsignia.VERDE to false),
            EstadoSiniestro.RECHAZADO to (TonoInsignia.ROJO to true),
            EstadoSiniestro.PAGADO to (TonoInsignia.VERDE to true),
        )
        EstadoSiniestro.entries.forEach { estado ->
            assertEquals(estado.name, esperado.getValue(estado), estado.tono to estado.terminaElCaso)
        }
    }
}
