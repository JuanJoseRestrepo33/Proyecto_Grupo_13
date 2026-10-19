package co.solventa.feature.onboarding.identidad

import co.solventa.core.network.EstadoIdentidad
import co.solventa.core.network.EstadoVerificacion
import co.solventa.core.network.MotivoRechazo
import co.solventa.core.network.VerificacionIdentidad
import co.solventa.feature.onboarding.BffFalso
import co.solventa.feature.onboarding.ReglaMain
import co.solventa.feature.onboarding.fallaBff
import co.solventa.feature.onboarding.sesionPrueba
import co.solventa.feature.onboarding.sesion.GestorSesion
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class IdentidadViewModelTest {
    @get:Rule
    val main = ReglaMain()

    private val api = BffFalso()
    private val gestor = GestorSesion().apply { sesion = sesionPrueba() }

    private fun verificacion(estado: EstadoVerificacion, motivo: MotivoRechazo? = null) =
        VerificacionIdentidad("KYC-1", estado, motivo, motivo?.let { true }, "2026-10-17T10:00:00Z")

    private fun resultado(viewModel: IdentidadViewModel) =
        (viewModel.estado as EstadoIdentidadUi.Listo).verificacion.estado

    @Test
    fun `sin verificacion previa la solicita y una aprobacion verifica la identidad`() {
        api.consulta = { throw fallaBff(404, "verificacion_no_encontrada") }
        api.solicitud = { verificacion(EstadoVerificacion.APROBADA) }
        val viewModel = IdentidadViewModel(api, gestor)
        assertEquals(EstadoVerificacion.APROBADA, resultado(viewModel))
        assertEquals(listOf("consulta", "solicitud"), api.llamadas)
        assertEquals(EstadoIdentidad.VERIFICADA, gestor.sesion?.cliente?.estadoIdentidad)
    }

    @Test
    fun `una verificacion existente se muestra sin volver a solicitarla`() {
        api.consulta = { verificacion(EstadoVerificacion.EN_REVISION) }
        val viewModel = IdentidadViewModel(api, gestor)
        assertEquals(EstadoVerificacion.EN_REVISION, resultado(viewModel))
        assertEquals(listOf("consulta"), api.llamadas)
        assertEquals(EstadoIdentidad.EN_REVISION, gestor.sesion?.cliente?.estadoIdentidad)
    }

    @Test
    fun `si la identidad ya estaba verificada lee el resultado vigente`() {
        var consultas = 0
        api.consulta = {
            consultas += 1
            if (consultas == 1) throw fallaBff(404, "verificacion_no_encontrada")
            verificacion(EstadoVerificacion.APROBADA)
        }
        api.solicitud = { throw fallaBff(409, "identidad_ya_verificada") }
        val viewModel = IdentidadViewModel(api, gestor)
        assertEquals(EstadoVerificacion.APROBADA, resultado(viewModel))
    }

    @Test
    fun `un rechazo permite reintentar la verificacion`() {
        api.consulta = { verificacion(EstadoVerificacion.RECHAZADA, MotivoRechazo.DATOS_NO_COINCIDEN) }
        api.solicitud = { verificacion(EstadoVerificacion.EN_PROCESO) }
        val viewModel = IdentidadViewModel(api, gestor)
        assertEquals(EstadoIdentidad.RECHAZADA, gestor.sesion?.cliente?.estadoIdentidad)
        viewModel.reintentar()
        assertEquals(EstadoVerificacion.EN_PROCESO, resultado(viewModel))
        assertEquals(EstadoIdentidad.PENDIENTE_VERIFICACION, gestor.sesion?.cliente?.estadoIdentidad)
    }

    @Test
    fun `un fallo de red deja un estado de error recuperable`() {
        api.consulta = { throw IOException("sin red") }
        val viewModel = IdentidadViewModel(api, gestor)
        assertEquals(EstadoIdentidadUi.Error, viewModel.estado)
        api.consulta = { verificacion(EstadoVerificacion.APROBADA) }
        viewModel.consultar()
        assertEquals(EstadoVerificacion.APROBADA, resultado(viewModel))
    }
}
