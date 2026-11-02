package co.solventa.feature.siniestros.reporte

import androidx.compose.material3.ExperimentalMaterial3Api
import co.solventa.feature.siniestros.HOY
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class)
class CalendarioTest {
    private fun milis(dias: Long) = HOY.plusDays(dias).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun `el calendario deja elegir hasta hoy y ningun dia futuro`() {
        val dias = hastaHoy(HOY)
        assertTrue(dias.isSelectableDate(milis(-400)))
        assertTrue(dias.isSelectableDate(milis(0)))
        assertFalse(dias.isSelectableDate(milis(1)))
        assertTrue(dias.isSelectableYear(HOY.year))
        assertFalse(dias.isSelectableYear(HOY.year + 1))
    }

    @Test
    fun `los milisegundos del calendario se leen como el dia UTC`() {
        assertEquals(HOY, fechaDeMilisUtc(milis(0)))
    }
}
