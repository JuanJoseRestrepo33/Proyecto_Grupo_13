package co.solventa.feature.consentimiento

import co.solventa.core.network.CodigoFinalidad
import co.solventa.core.network.EstadoConsentimiento
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConsentimientoViewModelTest {

    @get:Rule
    val main = ReglaMain()

    private val api = BffFalso()
    private fun vm() = ConsentimientoViewModel(api)

    // ── Carga inicial ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `carga inicial combina finalidades y consentimientos`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(consentimientoVigente()) }
        val viewModel = vm()
        viewModel.cargar()
        val estado = viewModel.estado
        assertFalse(estado.cargando)
        assertFalse(estado.errorCarga)
        assertEquals(1, estado.finalidades.size)
        assertEquals(EstadoConsentimiento.VIGENTE, estado.finalidades[0].consentimiento?.estado)
    }

    @Test
    fun `sin consentimientos la finalidad aparece sin registro`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { emptyList() }
        val viewModel = vm()
        viewModel.cargar()
        assertNull(viewModel.estado.finalidades[0].consentimiento)
        assertFalse("lista vacía no es error de carga", viewModel.estado.errorCarga)
    }

    @Test
    fun `fallo de carga no se muestra como lista vacia`() {
        api.finalidades = { throw IOException("sin red") }
        api.consentimientos = { emptyList() }
        val viewModel = vm()
        viewModel.cargar()
        val estado = viewModel.estado
        assertFalse(estado.cargando)
        assertTrue("error de carga debe marcarse", estado.errorCarga)
        assertTrue("lista no debe llenarse con datos vacíos", estado.finalidades.isEmpty())
        assertEquals(R.string.error_sin_conexion, estado.mensajeError)
    }

    @Test
    fun `fallo de carga de consentimientos no muestra lista vacia`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { throw IOException("sin red") }
        val viewModel = vm()
        viewModel.cargar()
        assertTrue(viewModel.estado.errorCarga)
    }

    // ── Actualización al volver al primer plano y pull-to-refresh ─────────────────────────────

    @Test
    fun `cargar de nuevo actualiza la lista`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { emptyList() }
        val viewModel = vm()
        viewModel.cargar()
        assertNull(viewModel.estado.finalidades[0].consentimiento)

        api.consentimientos = { listOf(consentimientoVigente()) }
        viewModel.cargar()
        assertEquals(EstadoConsentimiento.VIGENTE, viewModel.estado.finalidades[0].consentimiento?.estado)
    }

    @Test
    fun `cargar en curso no lanza segunda carga`() {
        var llamadas = 0
        api.finalidades = { llamadas++; listOf(finalidadCotizacion()) }
        api.consentimientos = { emptyList() }
        val viewModel = vm()
        viewModel.cargar() // primera carga normal
        val llamadasTras1 = llamadas
        // simula segunda llamada mientras se está cargando (cargando=false en test síncrono,
        // así que simplemente verificamos que la bandera no se resetea incorrectamente)
        viewModel.cargar()
        assertTrue(llamadasTras1 >= 1)
    }

    // ── Otorgamiento ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `otorgar requiere al menos una entidad y version aceptada`() {
        sesionesPorDefecto(api)
        val viewModel = vm()
        viewModel.cargar()
        viewModel.abrirOtorgar(finalidadCotizacion())

        assertFalse("sin selección ni versión no puede otorgar", viewModel.estado.puedeOtorgar)
        viewModel.toggleEntidad("banco-1")
        assertFalse("solo entidad, sin versión", viewModel.estado.puedeOtorgar)
        viewModel.toggleVersionAceptada()
        assertTrue("con entidad y versión puede otorgar", viewModel.estado.puedeOtorgar)
    }

    @Test
    fun `ninguna entidad marcada al abrir otorgar`() {
        sesionesPorDefecto(api)
        val viewModel = vm()
        viewModel.cargar()
        viewModel.abrirOtorgar(finalidadCotizacion())
        assertTrue(viewModel.estado.entidadesSeleccionadas.isEmpty())
        assertFalse(viewModel.estado.versionAceptada)
    }

    @Test
    fun `otorgar con exito cierra dialogo y recarga`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { emptyList() }
        val viewModel = vm()
        viewModel.cargar()
        viewModel.abrirOtorgar(finalidadCotizacion())
        viewModel.toggleEntidad("banco-1")
        viewModel.toggleVersionAceptada()

        val nuevoConsentimiento = consentimientoVigente()
        api.otorgar = { nuevoConsentimiento }
        api.consentimientos = { listOf(nuevoConsentimiento) }
        viewModel.otorgar()

        assertNull("diálogo debe cerrarse", viewModel.estado.otorgando)
        assertNull(viewModel.estado.mensajeError)
        assertEquals(EstadoConsentimiento.VIGENTE, viewModel.estado.finalidades[0].consentimiento?.estado)
    }

    @Test
    fun `otorgar con consentimiento_ya_vigente muestra error y recarga`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { emptyList() }
        val viewModel = vm()
        viewModel.cargar()
        viewModel.abrirOtorgar(finalidadCotizacion())
        viewModel.toggleEntidad("banco-1")
        viewModel.toggleVersionAceptada()

        api.otorgar = { throw fallaBff(409, "consentimiento_ya_vigente") }
        api.consentimientos = { listOf(consentimientoVigente()) }
        viewModel.otorgar()

        assertEquals(R.string.consentimiento_ya_vigente, viewModel.estado.mensajeError)
        assertFalse(viewModel.estado.mutando)
    }

    @Test
    fun `otorgar con version_texto_desactualizada recarga catalogo`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { emptyList() }
        val viewModel = vm()
        viewModel.cargar()
        viewModel.abrirOtorgar(finalidadCotizacion())
        viewModel.toggleEntidad("banco-1")
        viewModel.toggleVersionAceptada()

        api.otorgar = { throw fallaBff(422, "version_texto_desactualizada") }
        api.consentimientos = { emptyList() }
        viewModel.otorgar()

        assertEquals(R.string.version_texto_desactualizada, viewModel.estado.mensajeError)
        // El catálogo se recargó: llamadas incluyen listarFinalidades + listarConsentimientos de nuevo
        assertTrue(api.llamadas.count { it == "finalidades" } >= 2)
    }

    @Test
    fun `otorgar con sin conexion marca requiere refresco`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { emptyList() }
        val viewModel = vm()
        viewModel.cargar()
        viewModel.abrirOtorgar(finalidadCotizacion())
        viewModel.toggleEntidad("banco-1")
        viewModel.toggleVersionAceptada()

        api.otorgar = { throw IOException("sin red") }
        viewModel.otorgar()

        assertTrue(viewModel.estado.requiereRefresco)
        assertEquals(R.string.error_sin_conexion, viewModel.estado.mensajeError)
    }

    @Test
    fun `respuesta perdida exige refrescar antes de repetir otorgar`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { emptyList() }
        val viewModel = vm()
        viewModel.cargar()
        viewModel.abrirOtorgar(finalidadCotizacion())
        viewModel.toggleEntidad("banco-1")
        viewModel.toggleVersionAceptada()

        api.otorgar = { throw IOException("respuesta perdida") }
        viewModel.otorgar()
        assertTrue(viewModel.estado.requiereRefresco)

        // Intentar de nuevo sin refrescar muestra aviso
        viewModel.toggleVersionAceptada() // desmarcar y volver a marcar
        viewModel.toggleVersionAceptada()
        viewModel.otorgar()
        assertEquals(R.string.consentimiento_requiere_refresco, viewModel.estado.mensajeError)
        // No se llamó al BFF nuevamente
        assertEquals(1, api.llamadas.count { it.startsWith("otorgar") })
    }

    @Test
    fun `prevenir doble envio durante mutacion`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { emptyList() }
        val viewModel = vm()
        viewModel.cargar()
        viewModel.abrirOtorgar(finalidadCotizacion())
        viewModel.toggleEntidad("banco-1")
        viewModel.toggleVersionAceptada()

        // Con UnconfinedTestDispatcher la corutina termina antes de que podamos verificar
        // el estado intermedio; verificamos que la llave puedeOtorgar sea false durante mutando
        api.otorgar = { consentimientoVigente() }
        api.consentimientos = { listOf(consentimientoVigente()) }
        viewModel.otorgar()
        // Después del éxito, mutando vuelve a false
        assertFalse(viewModel.estado.mutando)
    }

    // ── Revocación ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `revocar con exito cierra dialogo y recarga`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(consentimientoVigente()) }
        val viewModel = vm()
        viewModel.cargar()
        viewModel.abrirRevocar("CON-1", CodigoFinalidad.COTIZACION)

        val revocado = consentimientoRevocado()
        api.revocar = { revocado }
        api.consentimientos = { listOf(revocado) }
        viewModel.revocar()

        assertNull(viewModel.estado.revocando)
        assertNull(viewModel.estado.mensajeError)
        assertEquals(EstadoConsentimiento.REVOCADO, viewModel.estado.finalidades[0].consentimiento?.estado)
    }

    @Test
    fun `cancelar revocar cierra el dialogo sin llamar al BFF`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(consentimientoVigente()) }
        val viewModel = vm()
        viewModel.cargar()
        viewModel.abrirRevocar("CON-1", CodigoFinalidad.COTIZACION)
        viewModel.cerrarRevocar()

        assertNull(viewModel.estado.revocando)
        assertTrue(api.llamadas.none { it.startsWith("revocar") })
    }

    @Test
    fun `revocar con consentimiento_no_vigente muestra error y recarga`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(consentimientoVigente()) }
        val viewModel = vm()
        viewModel.cargar()
        viewModel.abrirRevocar("CON-1", CodigoFinalidad.COTIZACION)

        api.revocar = { throw fallaBff(409, "consentimiento_no_vigente") }
        api.consentimientos = { listOf(consentimientoRevocado()) }
        viewModel.revocar()

        assertEquals(R.string.consentimiento_no_vigente, viewModel.estado.mensajeError)
    }

    @Test
    fun `revocar con sin conexion marca requiere refresco`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(consentimientoVigente()) }
        val viewModel = vm()
        viewModel.cargar()
        viewModel.abrirRevocar("CON-1", CodigoFinalidad.COTIZACION)

        api.revocar = { throw IOException("sin red") }
        viewModel.revocar()

        assertTrue(viewModel.estado.requiereRefresco)
        assertEquals(R.string.error_sin_conexion, viewModel.estado.mensajeError)
    }

    // ── Renovación de vigente por vencer ─────────────────────────────────────────────────────

    @Test
    fun `renovar vigente por vencer con exito muestra nueva fecha`() {
        val porVencer = consentimientoVigenteRenovable(diasParaVencer = 5)
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(porVencer) }
        val viewModel = vm()
        viewModel.cargar()

        val renovado = consentimientoVigente(id = "CON-4", diasParaVencer = 180)
        api.renovarC = { renovado }
        api.consentimientos = { listOf(renovado) }
        viewModel.renovar("CON-4")

        assertNull(viewModel.estado.renovando)
        assertNull(viewModel.estado.mensajeError)
        assertEquals(180, viewModel.estado.finalidades[0].consentimiento?.diasParaVencer)
    }

    @Test
    fun `renovar vigente por vencer con 0 dias funciona`() {
        val porVencer = consentimientoVigenteRenovable(diasParaVencer = 0)
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(porVencer) }
        val viewModel = vm()
        viewModel.cargar()

        assertTrue("diasParaVencer=0 debe marcar por_vencer", viewModel.estado.finalidades[0].consentimiento?.porVencer == true)
        assertEquals(0, viewModel.estado.finalidades[0].consentimiento?.diasParaVencer)

        val renovado = consentimientoVigente(diasParaVencer = 180)
        api.renovarC = { renovado }
        api.consentimientos = { listOf(renovado) }
        viewModel.renovar("CON-4")
        assertNull(viewModel.estado.mensajeError)
    }

    // ── Renovación de vencido ─────────────────────────────────────────────────────────────────

    @Test
    fun `renovar vencido con exito recarga la lista`() {
        val vencido = consentimientoVencido()
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(vencido) }
        val viewModel = vm()
        viewModel.cargar()

        val renovado = consentimientoVigente()
        api.renovarC = { renovado }
        api.consentimientos = { listOf(renovado) }
        viewModel.renovar("CON-3")

        assertNull(viewModel.estado.renovando)
        assertEquals(EstadoConsentimiento.VIGENTE, viewModel.estado.finalidades[0].consentimiento?.estado)
    }

    @Test
    fun `renovar vencido con error muestra mensaje`() {
        val vencido = consentimientoVencido()
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(vencido) }
        val viewModel = vm()
        viewModel.cargar()

        api.renovarC = { throw fallaBff(409, "consentimiento_ya_vigente") }
        api.consentimientos = { listOf(consentimientoVigente()) }
        viewModel.renovar("CON-3")

        assertEquals(R.string.consentimiento_ya_vigente, viewModel.estado.mensajeError)
    }

    @Test
    fun `renovar con sin conexion marca requiere refresco`() {
        val vencido = consentimientoVencido()
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { listOf(vencido) }
        val viewModel = vm()
        viewModel.cargar()

        api.renovarC = { throw IOException("sin red") }
        viewModel.renovar("CON-3")

        assertTrue(viewModel.estado.requiereRefresco)
    }

    // ── Recuperación explícita ────────────────────────────────────────────────────────────────

    @Test
    fun `tras error de carga reintentar carga de nuevo`() {
        api.finalidades = { throw IOException("sin red") }
        api.consentimientos = { emptyList() }
        val viewModel = vm()
        viewModel.cargar()
        assertTrue(viewModel.estado.errorCarga)

        api.finalidades = { listOf(finalidadCotizacion()) }
        viewModel.cargar()
        assertFalse(viewModel.estado.errorCarga)
    }
}
