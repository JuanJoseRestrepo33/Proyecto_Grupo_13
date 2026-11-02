package co.solventa.feature.siniestros.reporte

import androidx.lifecycle.SavedStateHandle
import co.solventa.core.network.Dinero
import co.solventa.core.network.ErrorBff
import co.solventa.core.network.ErrorCampo
import co.solventa.core.network.TipoEventoSiniestro
import co.solventa.feature.siniestros.BffFalso
import co.solventa.feature.siniestros.Claves
import co.solventa.feature.siniestros.HOY
import co.solventa.feature.siniestros.POLIZA_VENCIDA
import co.solventa.feature.siniestros.POLIZA_VIGENTE
import co.solventa.feature.siniestros.R
import co.solventa.feature.siniestros.ReglaMain
import co.solventa.feature.siniestros.fallaBff
import co.solventa.feature.siniestros.siniestroDe
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReporteViewModelTest {
    @get:Rule
    val main = ReglaMain()

    private val api = BffFalso()
    private val claves = Claves()
    private val borrador = SavedStateHandle()

    private fun viewModel(handle: SavedStateHandle = borrador) = ReporteViewModel(api, handle, { HOY }, claves::nueva)

    private fun ReporteViewModel.completarInformacion(fecha: LocalDate = HOY.minusDays(3)) {
        actualizar(Campo.TIPO) { it.copy(tipoEvento = TipoEventoSiniestro.HURTO) }
        actualizar(Campo.FECHA) { it.copy(fecha = fecha) }
        actualizar(Campo.DESCRIPCION) { it.copy(descripcion = "  Me robaron el celular en el bus  ") }
    }

    /** Elige la póliza, llena la información y queda en la revisión. */
    private fun enRevision(poliza: String = POLIZA_VIGENTE.numero, fecha: LocalDate = HOY.minusDays(3)) =
        viewModel().apply {
            actualizar(Campo.POLIZA) { it.copy(numeroPoliza = poliza) }
            continuar()
            completarInformacion(fecha)
            continuar()
            continuar()
        }

    // ── Pasos y validación ────────────────────────────────────────────────────────────────────

    @Test
    fun `al entrar consulta las polizas y empieza en el paso de poliza`() {
        val vm = viewModel()
        assertEquals(listOf(POLIZA_VIGENTE, POLIZA_VENCIDA), vm.estado.polizas)
        assertFalse(vm.estado.cargandoPolizas)
        assertFalse(vm.estado.sinPolizas)
        assertEquals(PasoReporte.POLIZA, vm.estado.paso)
    }

    @Test
    fun `una cuenta sin polizas no muestra el formulario`() {
        api.polizas = { emptyList() }
        assertTrue(viewModel().estado.sinPolizas)
    }

    @Test
    fun `sin red al consultar las polizas se ofrece reintentar`() {
        api.polizas = { throw IOException("sin red") }
        val vm = viewModel()
        assertEquals(R.string.error_sin_conexion, vm.estado.errorCarga)
        assertFalse(vm.estado.sinPolizas)
        api.polizas = { listOf(POLIZA_VIGENTE) }
        vm.cargarPolizas()
        assertNull(vm.estado.errorCarga)
        assertEquals(listOf(POLIZA_VIGENTE), vm.estado.polizas)
    }

    @Test
    fun `un 401 al consultar las polizas se delega al flujo de sesion`() {
        api.polizas = { throw fallaBff(401, "sesion_no_vigente") }
        val vm = viewModel()
        assertTrue(vm.estado.sesionVencida)
        vm.sesionAtendida()
        assertFalse(vm.estado.sesionVencida)
    }

    @Test
    fun `sin poliza elegida no avanza e indica que falta`() {
        val vm = viewModel()
        vm.continuar()
        assertEquals(PasoReporte.POLIZA, vm.estado.paso)
        assertEquals(ProblemaCampo.POLIZA_SIN_ELEGIR, vm.estado.error(Campo.POLIZA))
        vm.actualizar(Campo.POLIZA) { it.copy(numeroPoliza = POLIZA_VIGENTE.numero) }
        assertNull(vm.estado.error(Campo.POLIZA))
        vm.continuar()
        assertEquals(PasoReporte.INFORMACION, vm.estado.paso)
    }

    @Test
    fun `sin tipo, fecha ni descripcion no avanza e indica cada campo`() {
        val vm = viewModel()
        vm.actualizar(Campo.POLIZA) { it.copy(numeroPoliza = POLIZA_VIGENTE.numero) }
        vm.continuar()
        vm.continuar()
        assertEquals(PasoReporte.INFORMACION, vm.estado.paso)
        assertEquals(ProblemaCampo.TIPO_SIN_ELEGIR, vm.estado.error(Campo.TIPO))
        assertEquals(ProblemaCampo.FECHA_REQUERIDA, vm.estado.error(Campo.FECHA))
        assertEquals(ProblemaCampo.DESCRIPCION_REQUERIDA, vm.estado.error(Campo.DESCRIPCION))
        assertNull("el monto es opcional", vm.estado.error(Campo.MONTO))
        assertNull("la clave nace al entrar a la revisión", vm.estado.claveIdempotencia)
    }

    @Test
    fun `el error de un campo aparece al salir de el y se actualiza mientras se corrige`() {
        val vm = viewModel()
        vm.actualizar(Campo.DESCRIPCION) { it.copy(descripcion = "Corta") }
        assertNull("mientras escribe no hay error", vm.estado.error(Campo.DESCRIPCION))
        vm.salirDe(Campo.DESCRIPCION)
        assertEquals(ProblemaCampo.DESCRIPCION_LONGITUD, vm.estado.error(Campo.DESCRIPCION))
        vm.actualizar(Campo.DESCRIPCION) { it.copy(descripcion = "Una descripción suficiente") }
        assertNull(vm.estado.error(Campo.DESCRIPCION))
    }

    @Test
    fun `salir de un campo vacio no muestra el error antes de intentar avanzar`() {
        val vm = viewModel()
        vm.salirDe(Campo.DESCRIPCION)
        assertNull(vm.estado.error(Campo.DESCRIPCION))
    }

    @Test
    fun `una fecha futura no deja avanzar`() {
        val vm = viewModel()
        vm.actualizar(Campo.POLIZA) { it.copy(numeroPoliza = POLIZA_VIGENTE.numero) }
        vm.continuar()
        vm.completarInformacion(fecha = HOY.plusDays(1))
        vm.continuar()
        assertEquals(PasoReporte.INFORMACION, vm.estado.paso)
        assertEquals(ProblemaCampo.FECHA_FUTURA, vm.estado.error(Campo.FECHA))
    }

    @Test
    fun `la clave de idempotencia nace al entrar a la revision y no cambia al volver y regresar`() {
        val vm = enRevision()
        assertEquals(PasoReporte.REVISION, vm.estado.paso)
        assertEquals("clave-1", vm.estado.claveIdempotencia)
        vm.irA(PasoReporte.INFORMACION)
        assertEquals(PasoReporte.INFORMACION, vm.estado.paso)
        vm.continuar()
        assertEquals("clave-1", vm.estado.claveIdempotencia)
        assertEquals(1, claves.generadas)
    }

    @Test
    fun `atras retrocede un paso y en el primero indica salir del asistente`() {
        val vm = enRevision()
        assertTrue(vm.atras())
        assertEquals(PasoReporte.EVIDENCIA, vm.estado.paso)
        assertTrue(vm.atras())
        assertEquals(PasoReporte.INFORMACION, vm.estado.paso)
        assertTrue(vm.atras())
        assertEquals(PasoReporte.POLIZA, vm.estado.paso)
        assertFalse(vm.atras())
    }

    // ── Envío ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `enviar reporta con la clave y los datos limpios y muestra la confirmacion`() {
        val vm = enRevision()
        vm.actualizar(Campo.MONTO) { it.copy(monto = "1500000") }
        vm.continuar()
        val (clave, solicitud) = api.reportes.single()
        assertEquals("clave-1", clave)
        assertEquals(POLIZA_VIGENTE.numero, solicitud.numeroPoliza)
        assertEquals(TipoEventoSiniestro.HURTO, solicitud.tipoEvento)
        assertEquals("2026-10-23", solicitud.ocurridoEl)
        assertEquals("Me robaron el celular en el bus", solicitud.descripcion)
        assertEquals(Dinero(1_500_000, "COP"), solicitud.montoEstimado)
        assertEquals("SIN-2026-000123", vm.estado.siniestro?.referencia)
    }

    @Test
    fun `sin monto estimado no se envia monto`() {
        val vm = enRevision()
        vm.continuar()
        assertNull(api.reportes.single().second.montoEstimado)
    }

    @Test
    fun `mientras se envia un segundo toque no hace otra solicitud`() {
        val respuesta = CompletableDeferred<Unit>()
        api.reporte = { _, solicitud ->
            respuesta.await()
            siniestroDe(solicitud)
        }
        val vm = enRevision()
        vm.continuar()
        assertTrue(vm.estado.enviando)
        vm.continuar()
        vm.irA(PasoReporte.POLIZA)
        assertEquals(PasoReporte.REVISION, vm.estado.paso)
        respuesta.complete(Unit)
        assertEquals(1, api.reportes.size)
        assertFalse(vm.estado.enviando)
        assertEquals("SIN-2026-000123", vm.estado.siniestro?.referencia)
    }

    @Test
    fun `sin red el reintento usa la misma clave y confirma la misma referencia`() {
        api.reporte = { _, _ -> throw IOException("tiempo agotado") }
        val vm = enRevision()
        vm.continuar()
        assertEquals(Aviso.SinConexion, vm.estado.aviso)
        assertNull(vm.estado.siniestro)
        api.reporte = { _, solicitud -> siniestroDe(solicitud) }
        vm.continuar()
        assertEquals(listOf("clave-1", "clave-1"), api.reportes.map { it.first })
        assertEquals("SIN-2026-000123", vm.estado.siniestro?.referencia)
    }

    @Test
    fun `un error del servidor sin codigo conocido permite reintentar con la misma clave`() {
        api.reporte = { _, _ -> throw fallaBff(503, "nucleo_no_disponible") }
        val vm = enRevision()
        vm.continuar()
        assertEquals(Aviso.Generico, vm.estado.aviso)
        assertEquals("clave-1", vm.estado.claveIdempotencia)
        assertTrue("el núcleo pudo registrarlo", vm.estado.envioPendiente)
    }

    @Test
    fun `con un envio sin respuesta los datos quedan fijos hasta conocer el resultado`() {
        api.reporte = { _, _ -> throw IOException("respuesta perdida") }
        val vm = enRevision()
        vm.continuar()
        assertTrue(vm.estado.envioPendiente)
        assertFalse(vm.estado.puedeCorregir)
        vm.irA(PasoReporte.INFORMACION)
        vm.actualizar(Campo.DESCRIPCION) { it.copy(descripcion = "Otra descripción del evento") }
        assertEquals(PasoReporte.REVISION, vm.estado.paso)
        assertEquals("Me robaron el celular en el bus", vm.estado.datos.descripcion.trim())
        assertFalse("Atrás sale del asistente en vez de abrir un paso para corregir", vm.atras())
        api.reporte = { _, solicitud -> siniestroDe(solicitud) }
        vm.continuar()
        assertEquals(api.reportes[0], api.reportes[1])
        assertFalse(vm.estado.envioPendiente)
    }

    @Test
    fun `un rechazo definitivo tras un envio sin respuesta vuelve a permitir corregir`() {
        api.reporte = { _, _ -> throw IOException("sin red") }
        val vm = enRevision()
        vm.continuar()
        api.reporte = { _, _ -> throw fallaBff(409, "evento_fuera_de_vigencia") }
        vm.continuar()
        assertFalse(vm.estado.envioPendiente)
        vm.irA(PasoReporte.INFORMACION)
        assertEquals(PasoReporte.INFORMACION, vm.estado.paso)
    }

    @Test
    fun `clave en uso genera una clave nueva y pide confirmar el envio otra vez`() {
        api.reporte = { _, _ -> throw fallaBff(409, "clave_idempotencia_en_uso") }
        val vm = enRevision()
        vm.continuar()
        assertEquals(Aviso.ConfirmarDeNuevo, vm.estado.aviso)
        assertEquals("clave-2", vm.estado.claveIdempotencia)
        assertEquals(PasoReporte.REVISION, vm.estado.paso)
        assertEquals("no reenvía solo", 1, api.reportes.size)
        api.reporte = { _, solicitud -> siniestroDe(solicitud) }
        vm.continuar()
        assertEquals("clave-2", api.reportes.last().first)
    }

    @Test
    fun `una clave invalida se reemplaza antes del siguiente intento`() {
        api.reporte = { _, _ -> throw fallaBff(422, "clave_idempotencia_invalida") }
        val vm = enRevision()
        vm.continuar()
        assertEquals(Aviso.Generico, vm.estado.aviso)
        assertEquals("clave-2", vm.estado.claveIdempotencia)
    }

    @Test
    fun `evento fuera de vigencia explica las fechas de la poliza y no confirma`() {
        api.reporte = { _, _ ->
            throw fallaBff(
                409,
                ErrorBff("evento_fuera_de_vigencia", "x", vigenteDesde = "2025-09-26", vigenteHasta = "2026-09-26"),
            )
        }
        val vm = enRevision(poliza = POLIZA_VENCIDA.numero, fecha = HOY.minusDays(2))
        vm.continuar()
        val aviso = Aviso.FueraDeVigencia(LocalDate.parse("2025-09-26"), LocalDate.parse("2026-09-26"))
        assertEquals(aviso, vm.estado.aviso)
        assertNull(vm.estado.siniestro)
        assertEquals(PasoReporte.REVISION, vm.estado.paso)
    }

    @Test
    fun `una poliza vencida con el evento dentro de su vigencia se reporta`() {
        val vm = enRevision(poliza = POLIZA_VENCIDA.numero, fecha = LocalDate.parse("2026-09-01"))
        vm.continuar()
        assertEquals(POLIZA_VENCIDA.numero, vm.estado.siniestro?.numeroPoliza)
    }

    @Test
    fun `poliza no encontrada vuelve al primer paso y refresca la lista`() {
        api.reporte = { _, _ -> throw fallaBff(404, "poliza_no_encontrada") }
        val vm = enRevision()
        api.polizas = { listOf(POLIZA_VENCIDA) }
        vm.continuar()
        assertEquals(PasoReporte.POLIZA, vm.estado.paso)
        assertEquals(Aviso.PolizaNoEncontrada, vm.estado.aviso)
        assertNull(vm.estado.datos.numeroPoliza)
        assertEquals(2, api.consultasPolizas)
        assertEquals(listOf(POLIZA_VENCIDA), vm.estado.polizas)
    }

    @Test
    fun `fecha futura del BFF vuelve a la informacion con el error sobre la fecha`() {
        api.reporte = { _, _ ->
            throw fallaBff(422, "fecha_futura", listOf(ErrorCampo("ocurrido_el", "fecha_futura")))
        }
        val vm = enRevision()
        vm.continuar()
        assertEquals(PasoReporte.INFORMACION, vm.estado.paso)
        assertEquals(ProblemaCampo.FECHA_FUTURA, vm.estado.error(Campo.FECHA))
        vm.actualizar(Campo.FECHA) { it.copy(fecha = HOY.minusDays(5)) }
        assertNull(vm.estado.error(Campo.FECHA))
    }

    @Test
    fun `un 422 por campo lleva al paso del campo y no deja avanzar hasta corregirlo`() {
        api.reporte = { _, _ ->
            throw fallaBff(422, "solicitud_invalida", listOf(ErrorCampo("monto_estimado.valor", "invalido")))
        }
        val vm = enRevision()
        vm.continuar()
        assertEquals(PasoReporte.INFORMACION, vm.estado.paso)
        assertEquals(ProblemaCampo.MONTO_INVALIDO, vm.estado.error(Campo.MONTO))
        vm.continuar()
        assertEquals(PasoReporte.INFORMACION, vm.estado.paso)
        vm.actualizar(Campo.MONTO) { it.copy(monto = "") }
        vm.continuar()
        vm.continuar()
        assertEquals(PasoReporte.REVISION, vm.estado.paso)
    }

    @Test
    fun `un 401 al enviar se delega al flujo de sesion`() {
        api.reporte = { _, _ -> throw fallaBff(401, "sesion_no_vigente") }
        val vm = enRevision()
        vm.continuar()
        assertTrue(vm.estado.sesionVencida)
        assertFalse(vm.estado.enviando)
    }

    // ── Rotación y segundo plano ─────────────────────────────────────────────────────────────

    @Test
    fun `al recrearse desde el borrador conserva lo ingresado, el paso y la misma clave`() {
        val vm = enRevision()
        vm.actualizar(Campo.MONTO) { it.copy(monto = "250000") }
        // Un ViewModel nuevo con el mismo SavedStateHandle: lo que queda tras la muerte del proceso.
        val restaurado = viewModel(copia())
        assertEquals(PasoReporte.REVISION, restaurado.estado.paso)
        assertEquals(vm.estado.datos, restaurado.estado.datos)
        assertEquals("clave-1", restaurado.estado.claveIdempotencia)
        restaurado.continuar()
        assertEquals("clave-1", api.reportes.single().first)
    }

    @Test
    fun `tras la muerte del proceso la confirmacion se conserva sin volver a consultar`() {
        val vm = enRevision()
        vm.continuar()
        val restaurado = viewModel(copia())
        assertEquals(vm.estado.siniestro, restaurado.estado.siniestro)
        assertEquals("solo la consulta del primer ViewModel", 1, api.consultasPolizas)
    }

    @Test
    fun `tras la muerte del proceso un envio pendiente sigue fijo y lo explica`() {
        api.reporte = { _, _ -> throw IOException("sin red") }
        enRevision().continuar()
        val restaurado = viewModel(copia())
        assertTrue(restaurado.estado.envioPendiente)
        assertEquals(Aviso.Generico, restaurado.estado.aviso)
        assertEquals("clave-1", restaurado.estado.claveIdempotencia)
    }

    private fun copia() = SavedStateHandle(borrador.keys().associateWith { borrador.get<Any>(it) })

    @Test
    fun `una poliza restaurada que ya no esta en la lista deja de estar elegida`() {
        enRevision()
        api.polizas = { listOf(POLIZA_VENCIDA) }
        val restaurado = viewModel(copia())
        assertNull(restaurado.estado.datos.numeroPoliza)
        restaurado.continuar()
        assertEquals(PasoReporte.POLIZA, restaurado.estado.paso)
        assertTrue(api.reportes.isEmpty())
    }
}
