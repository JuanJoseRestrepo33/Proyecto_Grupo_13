package co.solventa.feature.siniestros.seguimiento

import co.solventa.core.network.EstadoSiniestro
import co.solventa.feature.siniestros.BffFalso
import co.solventa.feature.siniestros.ReglaMain
import co.solventa.feature.siniestros.fallaBff
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MisSiniestrosViewModelTest {
    @get:Rule
    val main = ReglaMain()

    private val api = BffFalso()

    @Test
    fun `lista los siniestros de cualquier canal del mas reciente al mas antiguo`() {
        val web = siniestro(id = "A", canal = "web", reportadoEn = "2026-10-01T10:00:00Z")
        val app = siniestro(id = "B", canal = "movil", reportadoEn = "2026-10-20T10:00:00-05:00")
        val medio = siniestro(id = "C", canal = "web", reportadoEn = "2026-10-10T10:00:00Z")
        api.siniestros = { listOf(web, app, medio) }

        val vm = MisSiniestrosViewModel(api)

        assertEquals(listOf("B", "C", "A"), vm.estado.siniestros?.map { it.id })
        assertFalse(vm.estado.cargando)
        assertNull(vm.estado.error)
    }

    @Test
    fun `sin siniestros la lista queda vacia`() {
        val vm = MisSiniestrosViewModel(api)
        assertTrue(vm.estado.vacia)
    }

    @Test
    fun `actualizar muestra el estado nuevo que cambio en el nucleo`() {
        var estado = EstadoSiniestro.REPORTADO
        api.siniestros = { listOf(siniestro(estado = estado)) }
        val vm = MisSiniestrosViewModel(api)

        estado = EstadoSiniestro.EN_EVALUACION
        vm.actualizar()

        assertEquals(EstadoSiniestro.EN_EVALUACION, vm.estado.siniestros?.single()?.estado)
        assertEquals(2, api.consultasSiniestros)
    }

    @Test
    fun `un fallo del bff se puede reintentar`() {
        api.siniestros = { throw fallaBff(500, "error_interno") }
        val vm = MisSiniestrosViewModel(api)
        assertEquals(FalloConsulta.GENERICO, vm.estado.error)
        assertNull(vm.estado.siniestros)

        api.siniestros = { listOf(siniestro()) }
        vm.actualizar()

        assertNull(vm.estado.error)
        assertEquals(1, vm.estado.siniestros?.size)
    }

    @Test
    fun `sin red se dice distinto y al actualizar se conserva la lista anterior`() {
        val vm = MisSiniestrosViewModel(api.apply { siniestros = { listOf(siniestro()) } })
        api.siniestros = { throw IOException("sin red") }

        vm.actualizar()

        assertEquals(FalloConsulta.SIN_RED, vm.estado.error)
        assertEquals(1, vm.estado.siniestros?.size)
    }

    @Test
    fun `un 401 que no se pudo renovar se delega al flujo de sesion`() {
        api.siniestros = { throw fallaBff(401, "sesion_no_vigente") }
        val vm = MisSiniestrosViewModel(api)
        assertTrue(vm.estado.sesionVencida)
        vm.sesionAtendida()
        assertFalse(vm.estado.sesionVencida)
    }

    @Test
    fun `los datos viven en el viewmodel y leerlos otra vez no consulta al bff`() {
        api.siniestros = { listOf(siniestro()) }
        val vm = MisSiniestrosViewModel(api)
        repeat(3) { vm.estado.siniestros }
        assertEquals(1, api.consultasSiniestros)
    }
}
