package co.solventa.feature.siniestros.borrador

import co.solventa.core.network.Dinero
import co.solventa.core.network.SolicitudSiniestro
import co.solventa.core.network.TipoEventoSiniestro
import co.solventa.feature.siniestros.CLIENTE
import co.solventa.feature.siniestros.POLIZA_VIGENTE
import co.solventa.feature.siniestros.siniestroDe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConciliacionTest {
    private val solicitud = SolicitudSiniestro(
        POLIZA_VIGENTE.numero,
        TipoEventoSiniestro.HURTO,
        "2026-10-23",
        "Me robaron el celular",
        Dinero.pesos(500_000),
    )
    private val borrador = Borrador("B-1", CLIENTE, solicitud, "clave-1", creadoEn = "2026-10-26T15:00:00Z")

    @Test
    fun `encuentra el siniestro con los mismos datos, canal movil y reportado desde el borrador`() {
        val propio = siniestroDe(solicitud).copy(reportadoEn = "2026-10-26T15:00:05Z")
        val otraDescripcion = siniestroDe(solicitud.copy(descripcion = "Otra cosa"), "SIN-2026-000124")
        assertEquals(propio, buscarRegistrado(borrador, listOf(otraDescripcion, propio)))
    }

    @Test
    fun `no toma uno de la web, de otro monto ni muy anterior al borrador`() {
        val web = siniestroDe(solicitud).copy(canal = "web")
        val otroMonto = siniestroDe(solicitud.copy(montoEstimado = Dinero.pesos(1)))
        val viejo = siniestroDe(solicitud).copy(reportadoEn = "2026-10-20T15:00:00Z")
        assertNull(buscarRegistrado(borrador, listOf(web, otroMonto, viejo)))
    }

    @Test
    fun `tolera unos minutos de diferencia entre relojes`() {
        val antes = siniestroDe(solicitud).copy(reportadoEn = "2026-10-26T14:57:00Z")
        assertEquals(antes, buscarRegistrado(borrador, listOf(antes)))
    }

    @Test
    fun `si hay uno posterior a la creacion no toma uno identico de unos minutos antes`() {
        val anterior = siniestroDe(solicitud, "SIN-2026-000100").copy(reportadoEn = "2026-10-26T14:58:00Z")
        val propio = siniestroDe(solicitud, "SIN-2026-000101").copy(reportadoEn = "2026-10-26T15:00:03Z")
        assertEquals(propio, buscarRegistrado(borrador, listOf(anterior, propio)))
    }

    @Test
    fun `no toma un reporte identico anterior a la creacion del borrador`() {
        val anterior = siniestroDe(solicitud, "SIN-2026-000100").copy(reportadoEn = "2026-10-26T14:00:00Z")
        val propio = siniestroDe(solicitud, "SIN-2026-000101").copy(reportadoEn = "2026-10-26T15:00:03Z")
        assertEquals(propio, buscarRegistrado(borrador, listOf(propio, anterior)))
        assertNull(buscarRegistrado(borrador, listOf(anterior)))
    }
}
