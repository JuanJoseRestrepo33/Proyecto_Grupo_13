package co.solventa.feature.siniestros.seguimiento

import androidx.lifecycle.SavedStateHandle
import co.solventa.core.network.EstadoSiniestro
import co.solventa.core.network.SiniestroDetalle
import co.solventa.feature.siniestros.AlmacenFalso
import co.solventa.feature.siniestros.BffFalso
import co.solventa.feature.siniestros.ReglaMain
import co.solventa.feature.siniestros.fallaBff
import co.solventa.feature.siniestros.evidencia.EstadoAdjunto
import co.solventa.feature.siniestros.evidencia.FalloCarga
import co.solventa.feature.siniestros.evidencia.FuentesEvidencia
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@Suppress("LargeClass")
class DetalleSiniestroViewModelTest {
    @get:Rule
    val main = ReglaMain()

    @get:Rule
    val carpeta = TemporaryFolder()

    private val api = BffFalso()
    private val almacen by lazy { AlmacenFalso(carpeta.root) }

    /** Lo que el núcleo tiene del caso; las pruebas lo cambian entre consultas. */
    private var caso: SiniestroDetalle = requiereInformacion()

    init {
        api.detalle = { id -> caso.takeIf { it.id == id } ?: throw fallaBff(404, "siniestro_no_encontrado") }
        // Como el BFF: la carga con `id_requerimiento` deja el requerimiento atendido.
        api.carga = { datos ->
            api.evidenciaDe(datos).also { evidencia ->
                datos.idRequerimiento?.let { req ->
                    caso = caso.copy(
                        requerimientos = caso.requerimientos.map {
                            if (it.id == req) atendido(req, "2026-10-26T15:00:00Z") else it
                        },
                    )
                    check(evidencia.nombreOriginal != null)
                }
            }
        }
    }

    private fun handle(id: String = ID_CASO) = SavedStateHandle(mapOf(DetalleSiniestroViewModel.ARGUMENTO_ID to id))

    private fun viewModel(estadoGuardado: SavedStateHandle = handle()) =
        DetalleSiniestroViewModel(api, estadoGuardado, FuentesEvidencia(almacen))

    /** Abre «Responder» y elige un archivo de la galería. */
    private fun DetalleSiniestroViewModel.conArchivo(nombre: String = "factura.pdf") = apply {
        responder(ID_REQ)
        evidencia.elegido(nombre)
    }

    // ── Detalle ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `consulta el caso del id de la ruta con su historial y requerimientos`() {
        val vm = viewModel()
        assertEquals(caso, vm.estado.detalle)
        assertEquals(EstadoSiniestro.REPORTADO, vm.estado.detalle?.historial?.first()?.estado)
        assertFalse(vm.estado.cargando)
    }

    @Test
    fun `actualizar muestra el estado nuevo que cambio en el nucleo`() {
        val vm = viewModel()
        caso = caso.copy(estado = EstadoSiniestro.EN_EVALUACION, requerimientos = emptyList())
        vm.actualizar()
        assertEquals(EstadoSiniestro.EN_EVALUACION, vm.estado.detalle?.estado)
        assertEquals(2, api.consultasDetalle)
    }

    @Test
    fun `un id que no existe o de otro cliente muestra no encontrado`() {
        val vm = viewModel(handle("SIN-ajeno"))
        assertTrue(vm.estado.noEncontrado)
        assertNull(vm.estado.detalle)
    }

    @Test
    fun `un fallo al cargar se puede reintentar`() {
        api.detalle = { throw fallaBff(500, "error_interno") }
        val vm = viewModel()
        assertEquals(FalloConsulta.GENERICO, vm.estado.error)

        api.detalle = { caso }
        vm.actualizar()
        assertNull(vm.estado.error)
        assertEquals(caso, vm.estado.detalle)
    }

    @Test
    fun `sin red al actualizar se conserva el detalle con el aviso`() {
        val vm = viewModel()
        api.detalle = { throw IOException("sin red") }
        vm.actualizar()
        assertEquals(FalloConsulta.SIN_RED, vm.estado.error)
        assertEquals(caso, vm.estado.detalle)
    }

    @Test
    fun `un 401 al consultar se delega al flujo de sesion`() {
        api.detalle = { throw fallaBff(401, "sesion_no_vigente") }
        assertTrue(viewModel().estado.sesionVencida)
    }

    @Test
    fun `tras la muerte del proceso vuelve al mismo caso, lo consulta de nuevo y conserva la respuesta`() {
        val guardado = handle()
        viewModel(guardado).conArchivo()

        val restaurado = viewModel(guardado)

        assertEquals(ID_REQ, restaurado.estado.respondiendo)
        assertEquals("factura.pdf", restaurado.evidencia.estado.listos.single().nombre)
        assertEquals(2, api.consultasDetalle)
        // Del siniestro no queda nada en el estado guardado: solo el id, lo que se responde y el adjunto.
        assertEquals(setOf("id", "respondiendo", "evidencia", "evidencia_agregando"), guardado.keys())
    }

    // ── Responder un requerimiento ───────────────────────────────────────────────────────────

    @Test
    fun `responder envia un solo archivo con id_requerimiento y muestra el requerimiento atendido`() {
        val vm = viewModel().conArchivo()

        vm.enviarRespuesta()

        val carga = api.cargas.single()
        assertEquals(ID_REQ, carga.idRequerimiento)
        assertEquals("factura.pdf", carga.nombre)
        assertEquals(2, api.consultasDetalle)
        assertEquals("2026-10-26T15:00:00Z", vm.estado.detalle?.requerimientos?.single()?.atendidoEn)
        assertEquals(AvisoSeguimiento.RESPUESTA_ENVIADA, vm.estado.aviso)
        assertNull(vm.estado.respondiendo)
        assertTrue(vm.evidencia.estado.adjuntos.isEmpty())
    }

    @Test
    fun `elegir otro archivo reemplaza al anterior y se envia uno solo`() {
        val vm = viewModel().conArchivo("primera.jpg")
        vm.evidencia.elegido("segunda.jpg")

        assertEquals(listOf("segunda.jpg"), vm.evidencia.estado.adjuntos.map { it.nombre })
        assertTrue(almacen.borrados.any { it.endsWith("primera.jpg") })
        vm.enviarRespuesta()
        assertEquals(listOf("segunda.jpg"), api.cargas.map { it.nombre })
    }

    @Test
    fun `solo un requerimiento pendiente abre la respuesta`() {
        caso = requiereInformacion(atendido())
        val vm = viewModel()
        vm.responder(ID_REQ)
        assertNull(vm.estado.respondiendo)
    }

    @Test
    fun `respuesta perdida no carga la evidencia dos veces y solo consulta el detalle`() {
        val vm = viewModel().conArchivo()
        val normal = api.carga
        api.carga = { datos ->
            normal(datos)
            throw IOException("tiempo agotado")
        }

        vm.enviarRespuesta()
        val adjunto = vm.evidencia.estado.adjuntos.single()
        assertEquals(FalloCarga.SIN_RED, adjunto.fallo)
        assertEquals(ID_REQ, vm.estado.respondiendo)

        api.carga = normal
        vm.enviarRespuesta()

        assertEquals("la evidencia llegó: no se vuelve a cargar", 1, api.cargas.size)
        assertEquals(1, api.consultasEvidencia)
        assertEquals(AvisoSeguimiento.RESPUESTA_ENVIADA, vm.estado.aviso)
        assertEquals(EstadoRequerimiento.ATENDIDO, vm.estado.detalle?.requerimientos?.single()?.estadoMostrado)
    }

    @Test
    fun `sin red la carga que no llego se reenvia tras conciliar`() {
        val vm = viewModel().conArchivo()
        api.carga = { throw IOException("sin red") }
        vm.enviarRespuesta()
        assertTrue(vm.evidencia.estado.adjuntos.single().reintentable)

        api.carga = { api.evidenciaDe(it) }
        vm.enviarRespuesta()

        assertEquals(1, api.consultasEvidencia)
        assertEquals(2, api.cargas.size)
        assertEquals(listOf(ID_REQ, ID_REQ), api.cargas.map { it.idRequerimiento })
        assertNull(vm.estado.respondiendo)
    }

    @Test
    fun `si no se puede conciliar no se reenvia nada`() {
        val vm = viewModel().conArchivo()
        api.carga = { throw IOException("sin red") }
        vm.enviarRespuesta()
        api.consulta = { throw IOException("sin red") }

        vm.enviarRespuesta()

        assertEquals(1, api.cargas.size)
        assertTrue(vm.evidencia.estado.errorConsulta)
        assertEquals(ID_REQ, vm.estado.respondiendo)
    }

    @Test
    fun `si la carga funciona pero falla la consulta, reintentar solo consulta`() {
        val vm = viewModel().conArchivo()
        val consulta = api.detalle
        api.detalle = { throw IOException("sin red") }

        vm.enviarRespuesta()

        assertTrue(vm.estado.respuestaSinConsulta)
        assertNull(vm.estado.respondiendo)
        api.detalle = consulta
        vm.actualizar()
        assertFalse(vm.estado.respuestaSinConsulta)
        assertEquals(1, api.cargas.size)
        assertEquals(EstadoRequerimiento.ATENDIDO, vm.estado.detalle?.requerimientos?.single()?.estadoMostrado)
    }

    // ── Errores del BFF al responder: una prueba por fila de la tabla ────────────────────────

    @Test
    fun `409 requerimiento_ya_atendido avisa y actualiza el caso sin salir`() {
        val vm = viewModel().conArchivo()
        api.carga = {
            caso = requiereInformacion(atendido())
            throw fallaBff(409, "requerimiento_ya_atendido")
        }
        vm.enviarRespuesta()

        assertEquals(AvisoSeguimiento.YA_ATENDIDO, vm.estado.aviso)
        assertEquals(EstadoRequerimiento.ATENDIDO, vm.estado.detalle?.requerimientos?.single()?.estadoMostrado)
        assertNull(vm.estado.respondiendo)
        assertFalse(vm.estado.noEncontrado)
    }

    @Test
    fun `404 requerimiento_no_encontrado avisa y actualiza el caso sin salir`() {
        val vm = viewModel().conArchivo()
        api.carga = {
            caso = caso.copy(requerimientos = emptyList())
            throw fallaBff(404, "requerimiento_no_encontrado")
        }
        vm.enviarRespuesta()

        assertEquals(AvisoSeguimiento.REQUERIMIENTO_NO_ENCONTRADO, vm.estado.aviso)
        assertFalse("no es el caso el que no existe", vm.estado.noEncontrado)
        assertEquals(2, api.consultasDetalle)
    }

    @Test
    fun `404 siniestro_no_encontrado muestra no encontrado`() {
        val vm = viewModel().conArchivo()
        api.carga = { throw fallaBff(404, "siniestro_no_encontrado") }
        vm.enviarRespuesta()
        assertTrue(vm.estado.noEncontrado)
        assertNull(vm.estado.respondiendo)
    }

    @Test
    fun `409 siniestro_cerrado explica que no recibe evidencia y actualiza el caso`() {
        val vm = viewModel().conArchivo()
        api.carga = {
            caso = caso.copy(estado = EstadoSiniestro.RECHAZADO, requerimientos = listOf(cerradoSinRespuesta()))
            throw fallaBff(409, "siniestro_cerrado")
        }
        vm.enviarRespuesta()

        assertEquals(AvisoSeguimiento.CASO_CERRADO, vm.estado.aviso)
        assertEquals(EstadoSiniestro.RECHAZADO, vm.estado.detalle?.estado)
    }

    @Test
    fun `413 y 422 muestran el mensaje del paso de evidencia y piden otro archivo`() {
        listOf(
            fallaBff(413, "archivo_demasiado_grande") to FalloCarga.TAMANO,
            fallaBff(422, "formato_no_admitido") to FalloCarga.FORMATO,
            fallaBff(422, "archivo_vacio") to FalloCarga.VACIO,
        ).forEach { (falla, fallo) ->
            val vm = viewModel(SavedStateHandle(mapOf("id" to ID_CASO))).conArchivo()
            api.carga = { throw falla }
            vm.enviarRespuesta()

            val adjunto = vm.evidencia.estado.adjuntos.single()
            assertEquals(fallo, adjunto.fallo)
            assertFalse(adjunto.reintentable)
            assertEquals(ID_REQ, vm.estado.respondiendo)
        }
    }

    @Test
    fun `503 almacenamiento_no_disponible se reintenta sin volver a elegir el archivo`() {
        val vm = viewModel().conArchivo()
        api.carga = { throw fallaBff(503, "almacenamiento_no_disponible") }
        vm.enviarRespuesta()
        val adjunto = vm.evidencia.estado.adjuntos.single()
        assertEquals(FalloCarga.ALMACENAMIENTO, adjunto.fallo)
        assertEquals(EstadoAdjunto.FALLIDO, adjunto.estado)

        api.carga = { api.evidenciaDe(it) }
        vm.enviarRespuesta()

        assertEquals(listOf("factura.pdf", "factura.pdf"), api.cargas.map { it.nombre })
        assertEquals("503 no deja dudas: no hace falta conciliar", 0, api.consultasEvidencia)
        assertEquals(AvisoSeguimiento.RESPUESTA_ENVIADA, vm.estado.aviso)
    }

    @Test
    fun `401 al responder se delega al flujo de sesion y conserva el adjunto`() {
        val vm = viewModel().conArchivo()
        api.carga = { throw fallaBff(401, "sesion_no_vigente") }
        vm.enviarRespuesta()
        assertTrue(vm.estado.sesionVencida)
        assertEquals(ID_REQ, vm.estado.respondiendo)
        assertEquals(1, vm.evidencia.estado.adjuntos.size)
    }

    @Test
    fun `401 al conciliar una carga incierta se delega al flujo de sesion sin reenviar`() {
        val vm = viewModel().conArchivo()
        api.carga = { throw IOException("sin red") }
        vm.enviarRespuesta()
        api.consulta = { throw fallaBff(401, "sesion_no_vigente") }

        vm.enviarRespuesta()

        assertTrue(vm.estado.sesionVencida)
        assertEquals(1, api.cargas.size)
        assertEquals(ID_REQ, vm.estado.respondiendo)
        assertEquals(1, vm.evidencia.estado.adjuntos.size)
    }

    @Test
    fun `cancelar cierra la respuesta y borra el archivo elegido`() {
        val vm = viewModel().conArchivo()
        vm.cancelarRespuesta()
        assertNull(vm.estado.respondiendo)
        assertTrue(vm.evidencia.estado.adjuntos.isEmpty())
        assertTrue(api.cargas.isEmpty())
    }
}
