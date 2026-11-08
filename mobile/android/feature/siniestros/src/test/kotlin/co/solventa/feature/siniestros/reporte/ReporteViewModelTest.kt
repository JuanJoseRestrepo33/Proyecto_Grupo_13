package co.solventa.feature.siniestros.reporte

import androidx.lifecycle.SavedStateHandle
import co.solventa.core.network.Dinero
import co.solventa.core.network.ErrorBff
import co.solventa.core.network.ErrorCampo
import co.solventa.core.network.TipoEventoSiniestro
import co.solventa.feature.siniestros.BffFalso
import co.solventa.feature.siniestros.Claves
import co.solventa.feature.siniestros.Entorno
import co.solventa.feature.siniestros.borrador.EstadoBorrador
import co.solventa.feature.siniestros.HOY
import co.solventa.feature.siniestros.POLIZA_VENCIDA
import co.solventa.feature.siniestros.POLIZA_VIGENTE
import co.solventa.feature.siniestros.ReglaMain
import co.solventa.feature.siniestros.fallaBff
import co.solventa.feature.siniestros.siniestroDe
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReporteViewModelTest {
    @get:Rule
    val main = ReglaMain()

    @get:Rule
    val carpeta = TemporaryFolder()

    private val api = BffFalso()
    private val claves = Claves()
    private val borrador = SavedStateHandle()
    private val entorno by lazy { Entorno(carpeta.root, api) }

    @After
    fun cerrar() = entorno.alcance.cancel()

    private fun viewModel(handle: SavedStateHandle = borrador, idCorregir: String? = null) =
        ReporteViewModel(api, handle, { HOY }, claves::nueva, enviador = entorno.enviador, idCorregir = idCorregir)

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
        assertTrue("sin lista guardada hace falta conexión una vez (M2)", vm.estado.necesitaConexion)
        assertFalse(vm.estado.sinPolizas)
        api.polizas = { listOf(POLIZA_VIGENTE) }
        vm.cargarPolizas()
        assertFalse(vm.estado.necesitaConexion)
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
    fun `sin red guarda el reporte cifrado y lo explica sin decir que se envio`() {
        entorno.monitor.conectado.value = false
        val vm = enRevision()
        assertTrue(vm.estado.sinConexion)
        vm.continuar()
        assertEquals(ReporteGuardado.SIN_CONEXION, vm.estado.guardado)
        assertTrue(api.reportes.isEmpty())
        assertEquals("clave-1", entorno.borradores().single().claveIdempotencia)
        // Vuelve la red: se envía solo, con la misma clave.
        entorno.monitor.conectado.value = true
        assertEquals(listOf("clave-1"), api.reportes.map { it.first })
    }

    @Test
    fun `una respuesta perdida deja el reporte guardado y el reintento usa la misma clave`() {
        api.reporte = { _, _ -> throw IOException("tiempo agotado") }
        val vm = enRevision()
        vm.continuar()
        assertEquals(ReporteGuardado.SIN_CONFIRMAR, vm.estado.guardado)
        assertNull(vm.estado.siniestro)
        assertTrue(entorno.borradores().single().intentoIncierto)
        api.reporte = { _, solicitud -> siniestroDe(solicitud) }
        entorno.monitor.conectado.value = false
        entorno.monitor.conectado.value = true
        assertEquals(listOf("clave-1", "clave-1"), api.reportes.map { it.first })
        assertEquals(api.reportes[0].second, api.reportes[1].second)
    }

    @Test
    fun `un error del servidor sin codigo conocido se trata como incierto`() {
        api.reporte = { _, _ -> throw fallaBff(503, "nucleo_no_disponible") }
        val vm = enRevision()
        vm.continuar()
        assertEquals(ReporteGuardado.SIN_CONFIRMAR, vm.estado.guardado)
        assertTrue("el núcleo pudo registrarlo", entorno.borradores().single().intentoIncierto)
    }

    @Test
    fun `si no se puede guardar no envia, no dice guardado y conserva los datos`() {
        entorno.cifrado.bytes = 4
        val vm = enRevision()
        vm.continuar()
        assertEquals(Aviso.NoPudimosGuardar, vm.estado.aviso)
        assertNull(vm.estado.guardado)
        assertNull(vm.estado.idBorrador)
        assertTrue(api.reportes.isEmpty())
        assertEquals(PasoReporte.REVISION, vm.estado.paso)
        assertEquals("Me robaron el celular en el bus", vm.estado.datos.descripcion.trim())
        assertFalse(vm.estado.enviando)
    }

    @Test
    fun `una vez guardado el estado del asistente no conserva los datos en claro`() {
        entorno.monitor.conectado.value = false
        val vm = enRevision()
        vm.actualizar(Campo.MONTO) { it.copy(monto = "250000") }
        vm.continuar()
        val textos = borrador.keys().mapNotNull { borrador.get<Any>(it)?.toString() }
        textos.forEach { texto ->
            assertFalse(texto, "celular" in texto || POLIZA_VIGENTE.numero in texto || "250000" in texto)
        }
        assertEquals(vm.estado.idBorrador, borrador.get<String>("borrador"))
    }

    @Test
    fun `clave en uso no rota la clave y deja el reporte por confirmar`() {
        api.reporte = { _, _ -> throw fallaBff(409, "clave_idempotencia_en_uso") }
        api.siniestros = { throw IOException("sin red") }
        val vm = enRevision()
        vm.continuar()
        assertEquals(ReporteGuardado.SIN_CONFIRMAR, vm.estado.guardado)
        assertEquals("clave-1", entorno.borradores().single().claveIdempotencia)
        assertEquals("no reenvía con otra clave", 1, api.reportes.size)
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
    fun `un rechazo definitivo se corrige en el asistente y sale con una clave nueva`() {
        api.reporte = { _, _ -> throw fallaBff(409, "evento_fuera_de_vigencia") }
        val vm = enRevision()
        vm.continuar()
        assertTrue(vm.estado.corrigiendo)
        assertEquals(EstadoBorrador.REQUIERE_ATENCION, entorno.borradores().single().estado)
        vm.irA(PasoReporte.INFORMACION)
        vm.actualizar(Campo.FECHA) { it.copy(fecha = HOY.minusDays(1)) }
        vm.continuar()
        vm.continuar()
        api.reporte = { _, solicitud -> siniestroDe(solicitud) }
        vm.continuar()
        assertEquals(listOf("clave-1", "clave-2"), api.reportes.map { it.first })
        assertEquals("2026-10-25", api.reportes.last().second.ocurridoEl)
        assertEquals("SIN-2026-000123", vm.estado.siniestro?.referencia)
        assertEquals("se corrigió el mismo borrador", 1, entorno.enviador.tarjetas.value.size)
    }

    @Test
    fun `al corregir los datos tampoco quedan en claro en el estado guardado`() {
        api.reporte = { _, _ -> throw fallaBff(409, "evento_fuera_de_vigencia") }
        val vm = enRevision()
        vm.continuar()
        assertTrue(vm.estado.corrigiendo)
        val textos = borrador.keys().mapNotNull { borrador.get<Any>(it)?.toString() }
        textos.forEach { texto -> assertFalse(texto, "celular" in texto || POLIZA_VIGENTE.numero in texto) }
        // Tras la muerte del proceso, los datos se vuelven a leer del borrador cifrado.
        val restaurado = viewModel(copia())
        assertTrue(restaurado.estado.corrigiendo)
        assertEquals("Me robaron el celular en el bus", restaurado.estado.datos.descripcion)
    }

    @Test
    fun `revisar desde Inicio abre el asistente con el aviso en el paso afectado`() {
        api.reporte = { _, _ ->
            throw fallaBff(422, "solicitud_invalida", listOf(ErrorCampo("descripcion", "longitud_invalida")))
        }
        enRevision().continuar()
        val id = entorno.borradores().single().id
        val vm = viewModel(SavedStateHandle(), idCorregir = id)
        assertTrue(vm.estado.corrigiendo)
        assertEquals(PasoReporte.INFORMACION, vm.estado.paso)
        assertEquals(ProblemaCampo.DESCRIPCION_LONGITUD, vm.estado.error(Campo.DESCRIPCION))
        assertEquals("Me robaron el celular en el bus", vm.estado.datos.descripcion)
    }

    // ── Pólizas sin conexión ─────────────────────────────────────────────────────────────────

    @Test
    fun `sin red el paso 1 usa la ultima lista de polizas con su fecha`() {
        viewModel()
        api.polizas = { throw IOException("sin red") }
        val vm = viewModel(SavedStateHandle())
        assertEquals(listOf(POLIZA_VIGENTE, POLIZA_VENCIDA), vm.estado.polizas)
        assertEquals("2026-10-26T15:00:00Z", vm.estado.polizasDel)
        assertFalse(vm.estado.necesitaConexion)
    }

    @Test
    fun `sin red y sin polizas guardadas explica que hace falta conexion una vez`() {
        api.polizas = { throw IOException("sin red") }
        val vm = viewModel()
        assertTrue(vm.estado.necesitaConexion)
        assertFalse(vm.estado.sinPolizas)
        assertNull(vm.estado.errorCarga)
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
        assertEquals(vm.estado.siniestro?.referencia, restaurado.estado.siniestro?.referencia)
        assertEquals(vm.estado.siniestro?.siguientePaso, restaurado.estado.siniestro?.siguientePaso)
        assertEquals("solo la consulta del primer ViewModel", 1, api.consultasPolizas)
    }

    @Test
    fun `tras la muerte del proceso reporte guardado se conserva y no vuelve a enviar`() {
        api.reporte = { _, _ -> throw IOException("sin red") }
        enRevision().continuar()
        val restaurado = viewModel(copia())
        assertEquals(ReporteGuardado.SIN_CONFIRMAR, restaurado.estado.guardado)
        assertEquals(1, api.reportes.size)
        assertEquals("clave-1", entorno.borradores().single().claveIdempotencia)
    }

    @Test
    fun `si el proceso muere entre el guardado y la respuesta no se abre un asistente vacio`() {
        val handle = SavedStateHandle(mapOf("borrador" to "B-1", "corrigiendo" to false))
        val restaurado = viewModel(handle)
        assertEquals(ReporteGuardado.SIN_CONFIRMAR, restaurado.estado.guardado)
        assertEquals(0, api.consultasPolizas)
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
