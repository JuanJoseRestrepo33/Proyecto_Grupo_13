package co.solventa.feature.polizas.comun

import co.solventa.core.network.Dinero
import co.solventa.core.network.EstadoPoliza
import co.solventa.feature.polizas.POR_INICIAR
import co.solventa.feature.polizas.VENCIDA
import co.solventa.feature.polizas.VIGENTE
import co.solventa.feature.polizas.SINCRONIZADA
import co.solventa.feature.polizas.ZONA
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReglasPolizaTest {
    @Test
    fun `las pestanas filtran por el valor del contrato como la web`() {
        assertTrue(Pestana.ACTIVAS.incluye(VIGENTE))
        assertFalse(Pestana.ACTIVAS.incluye(VENCIDA))
        assertFalse(Pestana.ACTIVAS.incluye(POR_INICIAR))
        assertTrue(Pestana.VENCIDAS.incluye(VENCIDA))
        assertFalse(Pestana.VENCIDAS.incluye(VIGENTE))
        assertFalse(Pestana.VENCIDAS.incluye(POR_INICIAR))
        listOf(VIGENTE, VENCIDA, POR_INICIAR).forEach { assertTrue(Pestana.TODAS.incluye(it)) }
    }

    @Test
    fun `vigente es verde, vencida gris y por iniciar indigo`() {
        assertEquals(TonoInsignia.VERDE, EstadoPoliza.VIGENTE.tono)
        assertEquals(TonoInsignia.GRIS, EstadoPoliza.VENCIDA.tono)
        assertEquals(TonoInsignia.INDIGO, EstadoPoliza.POR_INICIAR.tono)
    }

    @Test
    fun `un dia del contrato se muestra corto y en espanol sin correrse por la zona`() {
        assertEquals("15 oct. 2026", formatoDia("2026-10-15"))
        assertEquals("1 ene. 2025", formatoDia("2025-01-01"))
        assertEquals("no-es-fecha", formatoDia("no-es-fecha"))
    }

    @Test
    fun `la emision se muestra en el dia de la zona del dispositivo`() {
        val bogota = ZoneId.of("America/Bogota")
        assertEquals("15 oct. 2026", formatoDiaDe("2026-10-15T17:00:00Z", bogota))
        // Las 2:00 UTC del 16 todavía son el 15 en Bogotá.
        assertEquals("15 oct. 2026", formatoDiaDe("2026-10-16T02:00:00Z", bogota))
        assertEquals("ayer", formatoDiaDe("ayer", bogota))
    }

    @Test
    fun `la prima va en pesos con punto de miles y otra moneda con su codigo`() {
        assertEquals("$185.000", formatoDinero(Dinero.pesos(185_000)))
        assertEquals("$1.250.000", formatoDinero(Dinero.pesos(1_250_000)))
        assertEquals("USD 120", formatoDinero(Dinero(120, "USD")))
    }

    // ── Vigencia sin conexión (MOB-F03, §5): una prueba por fila, con los bordes ─────────────

    private val guardada = VIGENTE.copy(inicio = "2026-10-15", fin = "2027-10-14")

    @Test
    fun `antes de inicio se muestra por iniciar`() {
        assertEquals(EstadoPoliza.POR_INICIAR, estadoSinConexion(guardada, LocalDate.parse("2026-10-14")))
    }

    @Test
    fun `entre inicio y fin incluidos una vigente sigue vigente`() {
        assertEquals(EstadoPoliza.VIGENTE, estadoSinConexion(guardada, LocalDate.parse("2026-10-15")))
        assertEquals(EstadoPoliza.VIGENTE, estadoSinConexion(guardada, LocalDate.parse("2027-01-01")))
        assertEquals(EstadoPoliza.VIGENTE, estadoSinConexion(guardada, LocalDate.parse("2027-10-14")))
    }

    @Test
    fun `entre inicio y fin una por iniciar que ya empezo se muestra vigente`() {
        val porIniciar = guardada.copy(estado = EstadoPoliza.POR_INICIAR)
        assertEquals(EstadoPoliza.VIGENTE, estadoSinConexion(porIniciar, LocalDate.parse("2026-10-15")))
    }

    @Test
    fun `entre inicio y fin una guardada como vencida sigue vencida`() {
        val vencida = guardada.copy(estado = EstadoPoliza.VENCIDA)
        assertEquals(EstadoPoliza.VENCIDA, estadoSinConexion(vencida, LocalDate.parse("2027-01-01")))
    }

    @Test
    fun `despues de fin se muestra vencida aunque se guardo vigente`() {
        assertEquals(EstadoPoliza.VENCIDA, estadoSinConexion(guardada, LocalDate.parse("2027-10-15")))
    }

    @Test
    fun `con fechas ilegibles queda el estado guardado`() {
        val ilegible = guardada.copy(fin = "mañana")
        assertEquals(EstadoPoliza.VIGENTE, estadoSinConexion(ilegible, LocalDate.parse("2030-01-01")))
    }

    @Test
    fun `la fecha de sincronizacion se muestra en la zona del telefono`() {
        assertTrue(formatoSincronizacion(SINCRONIZADA, ZONA).startsWith("18 nov"))
        assertTrue(formatoSincronizacion(SINCRONIZADA, ZONA).endsWith("08:14"))
        assertTrue(formatoSincronizacionCompleta(SINCRONIZADA, ZONA).contains("2026"))
        assertEquals("08:14", formatoHora(SINCRONIZADA, ZONA))
        assertTrue(esHoy(SINCRONIZADA, LocalDate.parse("2026-11-18"), ZONA))
        assertFalse(esHoy(Instant.parse("2026-11-19T03:00:00Z"), LocalDate.parse("2026-11-19"), ZONA))
    }
}
