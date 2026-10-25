package co.solventa.feature.consentimiento

import co.solventa.core.network.CodigoFinalidad
import co.solventa.core.network.Consentimiento
import co.solventa.core.network.EstadoConsentimiento
import co.solventa.core.network.FinalidadConsentimiento
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConsentimientoViewModelTest {

    @get:Rule
    val main = ReglaMain()

    private val api = BffFalso()

    private fun cargado(
        finalidades: List<FinalidadConsentimiento> = listOf(finalidadCotizacion()),
        consentimientos: List<Consentimiento> = emptyList(),
    ): ConsentimientoViewModel {
        api.finalidades = { finalidades }
        api.consentimientos = { consentimientos }
        return ConsentimientoViewModel(api).also { it.cargar() }
    }

    private fun ConsentimientoViewModel.formularioListo(finalidad: FinalidadConsentimiento = finalidadCotizacion()) {
        abrirOtorgar(finalidad)
        toggleEntidad(ENTIDAD_BANCO.id)
        toggleVersionAceptada()
    }

    private fun mutaciones() = api.llamadas.count { llamada ->
        listOf("otorgar", "revocar", "renovar").any { llamada.startsWith(it) }
    }

    // ── Carga inicial ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `carga inicial asocia el vigente y si no hay el mas reciente`() {
        val antiguo = consentimientoRevocado(id = "A").copy(otorgadoEn = "2024-01-01T00:00:00Z")
        val reciente = consentimientoVencido(id = "B").copy(otorgadoEn = "2025-03-01T00:00:00Z")
        val viewModel = cargado(
            finalidades = listOf(finalidadCotizacion(), finalidadSuscripcion()),
            consentimientos = listOf(
                antiguo,
                reciente,
                consentimientoVigente(id = "C", finalidad = CodigoFinalidad.SUSCRIPCION),
            ),
        )

        val estado = viewModel.estado
        assertFalse(estado.cargando)
        assertFalse(estado.errorCarga)
        assertEquals("B", estado.finalidades[0].consentimiento?.id)
        assertEquals("C", estado.finalidades[1].consentimiento?.id)
    }

    @Test
    fun `sin registros la finalidad queda sin consentimiento y no es error`() {
        val viewModel = cargado()
        assertNull(viewModel.estado.finalidades[0].consentimiento)
        assertFalse(viewModel.estado.errorCarga)
    }

    @Test
    fun `fallo de carga no se muestra como lista vacia y reintentar recupera`() {
        api.finalidades = { throw IOException("sin red") }
        api.consentimientos = { emptyList() }
        val viewModel = ConsentimientoViewModel(api)
        viewModel.cargar()
        assertTrue(viewModel.estado.errorCarga)
        assertTrue(viewModel.estado.finalidades.isEmpty())
        assertEquals(R.string.error_sin_conexion, viewModel.estado.mensajeCarga)

        api.finalidades = { listOf(finalidadCotizacion()) }
        viewModel.cargar()
        assertFalse(viewModel.estado.errorCarga)
        assertNull(viewModel.estado.mensajeCarga)
        assertEquals(1, viewModel.estado.finalidades.size)
    }

    @Test
    fun `fallo del servidor en consentimientos tambien es error de carga`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { throw fallaBff(503, "no_disponible") }
        val viewModel = ConsentimientoViewModel(api)
        viewModel.cargar()
        assertTrue(viewModel.estado.errorCarga)
        assertEquals(R.string.error_generico, viewModel.estado.mensajeCarga)
    }

    @Test
    fun `401 al consultar delega al flujo de sesion`() {
        api.finalidades = { listOf(finalidadCotizacion()) }
        api.consentimientos = { throw fallaBff(401, "no_autenticado") }
        val viewModel = ConsentimientoViewModel(api)
        viewModel.cargar()
        assertTrue(viewModel.estado.sesionVencida)
        assertTrue(viewModel.estado.errorCarga)
    }

    @Test
    fun `fallo al refrescar conserva la lista y avisa`() {
        val viewModel = cargado(consentimientos = listOf(consentimientoVigente()))
        api.consentimientos = { throw IOException("sin red") }
        viewModel.cargar()
        assertFalse(viewModel.estado.errorCarga)
        assertEquals(R.string.error_sin_conexion, viewModel.estado.mensajeCarga)
        assertEquals("CON-1", viewModel.estado.finalidades[0].consentimiento?.id)
    }

    // ── Actualización al volver a primer plano / pull-to-refresh ──────────────────────────────

    @Test
    fun `volver a consultar trae cambios hechos desde otro canal`() {
        val viewModel = cargado()
        api.consentimientos = { listOf(consentimientoVigente()) }
        viewModel.cargar()
        assertEquals(EstadoConsentimiento.VIGENTE, viewModel.estado.finalidades[0].consentimiento?.estado)
    }

    @Test
    fun `no hay acciones mientras la carga esta en curso`() {
        val viewModel = cargado(consentimientos = listOf(consentimientoVigente()))
        val pendiente = CompletableDeferred<List<Consentimiento>>()
        api.consentimientos = { pendiente.await() }
        viewModel.cargar()

        assertTrue(viewModel.estado.cargando)
        assertFalse(viewModel.estado.accionesHabilitadas)
        viewModel.abrirRevocar("CON-1", CodigoFinalidad.COTIZACION)
        assertNull(viewModel.estado.revocando)

        pendiente.complete(listOf(consentimientoVigente()))
        assertTrue(viewModel.estado.accionesHabilitadas)
    }

    // ── Otorgamiento ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `otorgar exige entidad y aceptacion, ninguna marcada al abrir`() {
        val viewModel = cargado()
        viewModel.abrirOtorgar(finalidadCotizacion())
        assertTrue(viewModel.estado.entidadesSeleccionadas.isEmpty())
        assertFalse(viewModel.estado.versionAceptada)
        assertFalse(viewModel.estado.puedeOtorgar)

        viewModel.toggleEntidad(ENTIDAD_BANCO.id)
        assertFalse(viewModel.estado.puedeOtorgar)
        viewModel.toggleVersionAceptada()
        assertTrue(viewModel.estado.puedeOtorgar)
        viewModel.toggleEntidad(ENTIDAD_BANCO.id)
        assertFalse(viewModel.estado.puedeOtorgar)
    }

    @Test
    fun `otorgar envia ids y version sin alterar y confirma solo tras la respuesta`() {
        val viewModel = cargado()
        viewModel.formularioListo()
        viewModel.toggleEntidad(ENTIDAD_CAJA.id)
        val respuesta = CompletableDeferred<Consentimiento>()
        var enviada: co.solventa.core.network.SolicitudConsentimiento? = null
        api.otorgar = { enviada = it; respuesta.await() }
        api.consentimientos = { listOf(consentimientoVigente()) }

        viewModel.otorgar()
        assertTrue(viewModel.estado.mutando)
        assertNull("sin éxito optimista", viewModel.estado.mensajeExito)
        assertNull(viewModel.estado.finalidades[0].consentimiento)

        respuesta.complete(consentimientoVigente())
        assertEquals(CodigoFinalidad.COTIZACION, enviada?.finalidad)
        assertEquals(setOf(ENTIDAD_BANCO.id, ENTIDAD_CAJA.id), enviada?.entidadesOrigen?.toSet())
        assertEquals(finalidadCotizacion().versionTexto, enviada?.versionTexto)
        assertNull(viewModel.estado.otorgando)
        assertEquals(R.string.otorgar_confirmado, viewModel.estado.mensajeExito)
        assertEquals(EstadoConsentimiento.VIGENTE, viewModel.estado.finalidades[0].consentimiento?.estado)
        assertEquals(2, api.llamadas.count { it == "consentimientos" })
    }

    @Test
    fun `revocado se vuelve a otorgar, no se renueva`() {
        val viewModel = cargado(consentimientos = listOf(consentimientoRevocado()))
        viewModel.formularioListo()
        api.otorgar = { consentimientoVigente() }
        api.consentimientos = { listOf(consentimientoRevocado(), consentimientoVigente()) }
        viewModel.otorgar()

        assertEquals(1, api.llamadas.count { it.startsWith("otorgar") })
        assertEquals(0, api.llamadas.count { it.startsWith("renovar") })
        assertEquals("CON-1", viewModel.estado.finalidades[0].consentimiento?.id)
    }

    @Test
    fun `prevenir doble envio mientras la mutacion esta en curso`() {
        val viewModel = cargado()
        viewModel.formularioListo()
        val respuesta = CompletableDeferred<Consentimiento>()
        api.otorgar = { respuesta.await() }

        viewModel.otorgar()
        viewModel.otorgar()
        assertFalse(viewModel.estado.puedeOtorgar)
        assertEquals(1, mutaciones())
        respuesta.complete(consentimientoVigente())
    }

    @Test
    fun `consentimiento_ya_vigente avisa, cierra el formulario y refresca`() {
        val viewModel = cargado()
        viewModel.formularioListo()
        api.otorgar = { throw fallaBff(409, "consentimiento_ya_vigente") }
        api.consentimientos = { listOf(consentimientoVigente()) }
        viewModel.otorgar()

        assertEquals(R.string.consentimiento_ya_vigente, viewModel.estado.mensajeError)
        assertNull(viewModel.estado.otorgando)
        assertEquals(EstadoConsentimiento.VIGENTE, viewModel.estado.finalidades[0].consentimiento?.estado)
    }

    @Test
    fun `version_texto_desactualizada trae la version vigente y exige aceptarla de nuevo`() {
        val viewModel = cargado()
        viewModel.formularioListo()
        val nueva = finalidadCotizacion().copy(versionTexto = "cotizacion-2026.2")
        api.otorgar = { throw fallaBff(422, "version_texto_desactualizada") }
        api.finalidades = { listOf(nueva) }
        viewModel.otorgar()

        val estado = viewModel.estado
        assertEquals(R.string.version_texto_desactualizada, estado.mensajeError)
        assertEquals("cotizacion-2026.2", estado.otorgando?.versionTexto)
        assertFalse(estado.versionAceptada)
        assertFalse(estado.puedeOtorgar)

        var enviada: String? = null
        api.otorgar = { enviada = it.versionTexto; consentimientoVigente() }
        viewModel.toggleVersionAceptada()
        viewModel.otorgar()
        assertEquals("cotizacion-2026.2", enviada)
    }

    @Test
    fun `entidades_invalidas recarga el catalogo y quita las entidades que ya no existen`() {
        val viewModel = cargado()
        viewModel.formularioListo()
        viewModel.toggleEntidad(ENTIDAD_CAJA.id)
        api.otorgar = { throw fallaBff(422, "entidades_invalidas") }
        api.finalidades = { listOf(finalidadCotizacion().copy(entidadesOrigen = listOf(ENTIDAD_CAJA))) }
        viewModel.otorgar()

        assertEquals(R.string.entidades_invalidas, viewModel.estado.mensajeError)
        assertEquals(setOf(ENTIDAD_CAJA.id), viewModel.estado.entidadesSeleccionadas)
        assertNotNull(viewModel.estado.otorgando)
    }

    @Test
    fun `respuesta perdida obliga a consultar antes de repetir`() {
        val viewModel = cargado()
        viewModel.formularioListo()
        api.otorgar = { throw IOException("respuesta perdida") }
        viewModel.otorgar()

        val estado = viewModel.estado
        assertTrue(estado.requiereRefresco)
        assertEquals(R.string.error_sin_conexion, estado.mensajeError)
        assertNull("el formulario se cierra para consultar el estado", estado.otorgando)
        viewModel.abrirOtorgar(finalidadCotizacion())
        assertNull(viewModel.estado.otorgando)
        assertEquals(1, mutaciones())

        api.consentimientos = { listOf(consentimientoVigente()) }
        viewModel.cargar()
        assertFalse(viewModel.estado.requiereRefresco)
        val actual = viewModel.estado.finalidades[0].consentimiento?.estado
        assertEquals("el servidor sí lo aplicó", EstadoConsentimiento.VIGENTE, actual)
    }

    @Test
    fun `una consulta iniciada antes de la respuesta perdida no levanta el bloqueo`() {
        val viewModel = cargado()
        viewModel.formularioListo()
        val mutacion = CompletableDeferred<Consentimiento>()
        api.otorgar = { mutacion.await() }
        viewModel.otorgar()

        val consultaVieja = CompletableDeferred<List<Consentimiento>>()
        api.consentimientos = { consultaVieja.await() }
        viewModel.cargar()
        mutacion.completeExceptionally(IOException("respuesta perdida"))
        consultaVieja.complete(emptyList())

        assertTrue(viewModel.estado.requiereRefresco)
        assertFalse(viewModel.estado.accionesHabilitadas)
    }

    @Test
    fun `fallo del servidor sin codigo deja el resultado incierto`() {
        val viewModel = cargado()
        viewModel.formularioListo()
        api.otorgar = { throw fallaSinCodigo(502) }
        viewModel.otorgar()
        assertTrue(viewModel.estado.requiereRefresco)
        assertEquals(R.string.error_generico, viewModel.estado.mensajeError)
    }

    @Test
    fun `401 delega al flujo de sesion sin reenviar`() {
        val viewModel = cargado()
        viewModel.formularioListo()
        api.otorgar = { throw fallaBff(401, "no_autenticado") }
        viewModel.otorgar()

        assertTrue(viewModel.estado.sesionVencida)
        assertEquals(R.string.error_sesion, viewModel.estado.mensajeError)
        assertEquals(1, mutaciones())
        viewModel.sesionAtendida()
        assertFalse(viewModel.estado.sesionVencida)
    }

    // ── Revocación ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `revocar con exito confirma tras la respuesta y refresca`() {
        val viewModel = cargado(consentimientos = listOf(consentimientoVigente()))
        viewModel.abrirRevocar("CON-1", CodigoFinalidad.COTIZACION)
        val revocado = consentimientoRevocado(id = "CON-1")
        api.revocar = { revocado }
        api.consentimientos = { listOf(revocado) }
        viewModel.revocar()

        assertNull(viewModel.estado.revocando)
        assertEquals(R.string.revocar_confirmado, viewModel.estado.mensajeExito)
        assertEquals(EstadoConsentimiento.REVOCADO, viewModel.estado.finalidades[0].consentimiento?.estado)
        assertEquals(listOf("revocar:CON-1"), api.llamadas.filter { it.startsWith("revocar") })
    }

    @Test
    fun `cancelar revocar no llama al BFF`() {
        val viewModel = cargado(consentimientos = listOf(consentimientoVigente()))
        viewModel.abrirRevocar("CON-1", CodigoFinalidad.COTIZACION)
        viewModel.cerrarRevocar()
        assertNull(viewModel.estado.revocando)
        assertEquals(0, mutaciones())
    }

    @Test
    fun `revocar no vigente avisa, refresca y ofrece las acciones del estado actual`() {
        val viewModel = cargado(consentimientos = listOf(consentimientoVigente()))
        viewModel.abrirRevocar("CON-1", CodigoFinalidad.COTIZACION)
        api.revocar = { throw fallaBff(409, "consentimiento_no_vigente") }
        api.consentimientos = { listOf(consentimientoVencido(id = "CON-1")) }
        viewModel.revocar()

        val mensaje = viewModel.estado.mensajeError
        assertEquals("el mensaje sobrevive a la recarga", R.string.consentimiento_no_vigente, mensaje)
        assertNull(viewModel.estado.revocando)
        assertEquals(EstadoConsentimiento.VENCIDO, viewModel.estado.finalidades[0].consentimiento?.estado)
        assertTrue(viewModel.estado.accionesHabilitadas)
    }

    @Test
    fun `revocar 404 avisa que no esta disponible y refresca`() {
        val viewModel = cargado(consentimientos = listOf(consentimientoVigente()))
        viewModel.abrirRevocar("CON-1", CodigoFinalidad.COTIZACION)
        api.revocar = { throw fallaBff(404, "consentimiento_no_encontrado") }
        api.consentimientos = { emptyList() }
        viewModel.revocar()

        assertEquals(R.string.consentimiento_no_encontrado, viewModel.estado.mensajeError)
        assertNull(viewModel.estado.finalidades[0].consentimiento)
        assertEquals(2, api.llamadas.count { it == "consentimientos" })
    }

    @Test
    fun `revocar sin conexion exige consultar antes de repetir`() {
        val viewModel = cargado(consentimientos = listOf(consentimientoVigente()))
        viewModel.abrirRevocar("CON-1", CodigoFinalidad.COTIZACION)
        api.revocar = { throw IOException("sin red") }
        viewModel.revocar()

        assertTrue(viewModel.estado.requiereRefresco)
        assertNull(viewModel.estado.revocando)
        viewModel.abrirRevocar("CON-1", CodigoFinalidad.COTIZACION)
        viewModel.revocar()
        assertEquals(1, mutaciones())
    }

    // ── Renovación ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `renovar vigente por vencer muestra la nueva vence_en del servidor`() {
        val viewModel = cargado(consentimientos = listOf(consentimientoVigenteRenovable(diasParaVencer = 0)))
        val con = viewModel.estado.finalidades[0].consentimiento
        assertEquals(true, con?.porVencer)
        assertEquals(0, con?.diasParaVencer)

        val renovado = consentimientoVigente(id = "CON-4", diasParaVencer = 180).copy(venceEn = "2027-01-01T10:00:00Z")
        api.renovarC = { renovado }
        api.consentimientos = { listOf(renovado) }
        viewModel.renovar("CON-4")

        assertNull(viewModel.estado.renovando)
        assertEquals(R.string.renovar_confirmado, viewModel.estado.mensajeExito)
        assertEquals("2027-01-01T10:00:00Z", viewModel.estado.nuevaVenceEn)
        assertEquals(180, viewModel.estado.finalidades[0].consentimiento?.diasParaVencer)
    }

    @Test
    fun `renovar vencido conserva el consentimiento y lo deja vigente`() {
        val viewModel = cargado(consentimientos = listOf(consentimientoVencido()))
        val renovado = consentimientoVigente(id = "CON-3")
        api.renovarC = { renovado }
        api.consentimientos = { listOf(renovado) }
        viewModel.renovar("CON-3")

        assertEquals(listOf("renovar:CON-3"), api.llamadas.filter { it.startsWith("renovar") })
        assertEquals(EstadoConsentimiento.VIGENTE, viewModel.estado.finalidades[0].consentimiento?.estado)
        assertEquals(renovado.venceEn, viewModel.estado.nuevaVenceEn)
    }

    @Test
    fun `renovar revocado desde otro canal avisa y refresca`() {
        val viewModel = cargado(consentimientos = listOf(consentimientoVencido()))
        api.renovarC = { throw fallaBff(409, "consentimiento_revocado") }
        api.consentimientos = { listOf(consentimientoRevocado(id = "CON-3")) }
        viewModel.renovar("CON-3")

        assertEquals(R.string.consentimiento_revocado, viewModel.estado.mensajeError)
        assertNull(viewModel.estado.nuevaVenceEn)
        assertEquals(EstadoConsentimiento.REVOCADO, viewModel.estado.finalidades[0].consentimiento?.estado)
    }

    @Test
    fun `renovar con error del servidor avisa y exige consultar`() {
        val viewModel = cargado(consentimientos = listOf(consentimientoVigenteRenovable(diasParaVencer = 3)))
        api.renovarC = { throw fallaBff(500, "error_interno") }
        viewModel.renovar("CON-4")

        assertEquals(R.string.error_generico, viewModel.estado.mensajeError)
        assertTrue(viewModel.estado.requiereRefresco)
        assertNull(viewModel.estado.renovando)

        viewModel.cargar()
        assertFalse(viewModel.estado.requiereRefresco)
        assertTrue(viewModel.estado.accionesHabilitadas)
    }
}
