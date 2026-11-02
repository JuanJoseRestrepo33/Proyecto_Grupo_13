package co.solventa.feature.siniestros.reporte

import co.solventa.core.network.TipoEventoSiniestro
import co.solventa.feature.siniestros.HOY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ValidadorReporteTest {
    private val completo = DatosReporte(
        numeroPoliza = "POL-1",
        tipoEvento = TipoEventoSiniestro.OTRO,
        fecha = HOY,
        descripcion = "Diez letra",
    )

    private fun problema(campo: Campo, datos: DatosReporte) = ValidadorReporte.validar(campo, datos, HOY)

    @Test
    fun `datos completos no tienen problemas y hoy es una fecha valida`() {
        Campo.entries.forEach { assertNull(it.name, problema(it, completo)) }
    }

    @Test
    fun `la descripcion se mide sin espacios al borde y entre 10 y 2000 caracteres`() {
        val vacia = completo.copy(descripcion = "   ")
        assertEquals(ProblemaCampo.DESCRIPCION_REQUERIDA, problema(Campo.DESCRIPCION, vacia))
        val corta = completo.copy(descripcion = "  Nueve...  ")
        assertEquals(ProblemaCampo.DESCRIPCION_LONGITUD, problema(Campo.DESCRIPCION, corta))
        assertNull(problema(Campo.DESCRIPCION, completo.copy(descripcion = "x".repeat(2000))))
        assertEquals(
            ProblemaCampo.DESCRIPCION_LONGITUD,
            problema(Campo.DESCRIPCION, completo.copy(descripcion = "x".repeat(2001))),
        )
    }

    @Test
    fun `la fecha es obligatoria y no puede ser posterior a hoy`() {
        assertEquals(ProblemaCampo.FECHA_REQUERIDA, problema(Campo.FECHA, completo.copy(fecha = null)))
        assertEquals(ProblemaCampo.FECHA_FUTURA, problema(Campo.FECHA, completo.copy(fecha = HOY.plusDays(1))))
    }

    @Test
    fun `el monto es opcional y, si se escribe, son pesos enteros con o sin punto de miles`() {
        assertNull(problema(Campo.MONTO, completo.copy(monto = "")))
        assertEquals(1_500_000L, ValidadorReporte.montoEnPesos("1500000"))
        assertEquals(1_500_000L, ValidadorReporte.montoEnPesos(" 1.500.000 "))
        assertEquals(999_999_999_999L, ValidadorReporte.montoEnPesos("999.999.999.999"))
    }

    @Test
    fun `un monto con decimales, mal agrupado o demasiado largo es un error y no otro monto`() {
        val invalidos = listOf(
            "1.500.000,00", "1500,5", "1.5", "15.00.000", "-200", "9".repeat(13), "1.000.000.000.000",
        )
        invalidos.forEach { monto ->
            assertEquals(monto, ProblemaCampo.MONTO_INVALIDO, problema(Campo.MONTO, completo.copy(monto = monto)))
            assertNull(monto, ValidadorReporte.montoEnPesos(monto))
        }
    }

    @Test
    fun `los campos del contrato se reconocen, tambien los anidados`() {
        assertEquals(Campo.FECHA, Campo.delContrato("ocurrido_el"))
        assertEquals(Campo.MONTO, Campo.delContrato("monto_estimado.valor"))
        assertNull(Campo.delContrato("Idempotency-Key"))
        assertEquals(PasoReporte.POLIZA, PasoReporte.de(Campo.POLIZA))
        assertEquals(PasoReporte.INFORMACION, PasoReporte.de(Campo.MONTO))
    }
}
