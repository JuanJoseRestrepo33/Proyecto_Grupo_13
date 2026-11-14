package co.solventa.feature.siniestros.evidencia

import androidx.lifecycle.SavedStateHandle
import co.solventa.feature.siniestros.AlmacenFalso
import co.solventa.feature.siniestros.BffFalso
import co.solventa.feature.siniestros.ReglaMain
import co.solventa.feature.siniestros.fallaBff
import java.io.IOException
import java.time.OffsetDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

class GestorEvidenciaTest {
    @get:Rule
    val main = ReglaMain()

    @get:Rule
    val carpeta = TemporaryFolder()

    private val api = BffFalso()
    private val almacen by lazy { AlmacenFalso(carpeta.root) }
    private val borrador = SavedStateHandle()
    private var coordenadas: Coordenadas? = Coordenadas(4.711, -74.0721)
    private val ahora = OffsetDateTime.parse("2026-10-26T10:15:00-05:00")

    private fun gestor(handle: SavedStateHandle = borrador) = GestorEvidencia(
        api,
        FuentesEvidencia(almacen, { coordenadas }, { ahora }),
        CoroutineScope(Dispatchers.Main),
        handle,
    )

    private fun GestorEvidencia.conFoto(nombre: String = "a.jpg") = apply {
        permisos(EstadoPermiso.CONCEDIDO, EstadoPermiso.CONCEDIDO)
        fotoTomada(nombre)
    }

    // ── Adjuntar ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `la foto con permisos lleva fecha con zona y coordenadas`() {
        val g = gestor().conFoto()
        val adjunto = g.estado.adjuntos.single()
        assertEquals("2026-10-26T10:15:00-05:00", adjunto.tomadaEn)
        assertEquals(4.711, adjunto.latitud)
        assertEquals(-74.0721, adjunto.longitud)
    }

    @Test
    fun `sin permiso de ubicacion no se lee y la foto queda sin coordenadas`() {
        val g = gestor()
        g.permisos(EstadoPermiso.CONCEDIDO, EstadoPermiso.NEGADO)
        g.fotoTomada("a.jpg")
        assertFalse(g.estado.adjuntos.single().conCoordenadas)
    }

    @Test
    fun `una lectura sin senal deja la foto sin coordenadas y se puede escribir el lugar`() {
        coordenadas = null
        val g = gestor().conFoto()
        val adjunto = g.estado.adjuntos.single()
        assertFalse(adjunto.conCoordenadas)
        g.cambiarLugar(adjunto.id, "x".repeat(LARGO_MAXIMO_LUGAR + 5))
        assertEquals(LARGO_MAXIMO_LUGAR, g.estado.adjuntos.single().lugar.length)
    }

    @Test
    fun `lo de la galeria no lleva la ubicacion actual ni fecha de captura`() {
        val g = gestor()
        g.permisos(EstadoPermiso.CONCEDIDO, EstadoPermiso.CONCEDIDO)
        g.elegido("a.jpg")
        val adjunto = g.estado.adjuntos.single()
        assertFalse(adjunto.conCoordenadas)
        assertNull(adjunto.tomadaEn)
    }

    @Test
    fun `un archivo vacio o de otro formato se explica y no se agrega`() {
        almacen.resultado = { Preparado.Rechazado(MotivoRechazo.FORMATO) }
        val g = gestor()
        g.elegido("a.gif")
        assertEquals(MotivoRechazo.FORMATO, g.estado.rechazo)
        assertTrue(g.estado.adjuntos.isEmpty())
    }

    @Test
    fun `lo que no cupo se explica, se ofrece quitar y nunca se envia`() {
        almacen.resultado = { Preparado.Rechazado(MotivoRechazo.NO_CABE, FormatoEvidencia.MP4, TAMANO_MAXIMO * 3) }
        val g = gestor()
        g.elegido("largo.mp4")
        assertEquals(MotivoRechazo.NO_CABE, g.estado.rechazo)
        assertEquals(EstadoAdjunto.NO_CABE, g.estado.porEnviar.single().estado)
        g.subir("SIN-1")
        assertTrue(api.cargas.isEmpty())
        assertTrue(g.estado.adjuntos.isEmpty())
    }

    @Test
    fun `quitar borra el temporal`() {
        val g = gestor().conFoto()
        val adjunto = g.estado.adjuntos.single()
        g.quitar(adjunto.id)
        assertTrue(g.estado.adjuntos.isEmpty())
        assertEquals(listOf(adjunto.ruta), almacen.borrados)
    }

    // ── Envío ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `envia uno a uno con los campos del contrato, borra el temporal y consulta el siniestro`() {
        val g = gestor().conFoto("a.jpg").conFoto("b.jpg")
        g.subir("SIN-1")
        assertEquals(listOf("a.jpg", "b.jpg"), api.cargas.map { it.nombre })
        val carga = api.cargas.first()
        assertEquals("SIN-1", carga.idSiniestro)
        assertEquals("image/jpeg", carga.tipo)
        assertEquals("2026-10-26T10:15:00-05:00", carga.tomadaEn)
        assertEquals("4.711", carga.latitud)
        assertEquals("-74.0721", carga.longitud)
        assertNull(carga.lugar)
        // WEB-F06: la evidencia del reporte y la posterior no responden un requerimiento.
        assertTrue(api.cargas.all { it.idRequerimiento == null })
        assertTrue(g.estado.adjuntos.all { it.estado == EstadoAdjunto.ENVIADO })
        assertEquals(2, almacen.borrados.size)
        assertEquals(2, g.estado.delSiniestro.size)
        assertFalse(g.estado.enviando)
    }

    @Test
    fun `el lugar escrito viaja solo cuando no hay coordenadas`() {
        coordenadas = null
        val g = gestor().conFoto()
        g.cambiarLugar(g.estado.adjuntos.single().id, "  Calle 100, Bogotá ")
        g.subir("SIN-1")
        assertEquals("Calle 100, Bogotá", api.cargas.single().lugar)
        assertNull(api.cargas.single().latitud)
    }

    @Test
    fun `cada rechazo del bff se traduce y solo lo pasajero se puede reintentar`() {
        val casos = listOf(
            fallaBff(413, "archivo_demasiado_grande") to FalloCarga.TAMANO,
            fallaBff(422, "formato_no_admitido") to FalloCarga.FORMATO,
            fallaBff(422, "archivo_vacio") to FalloCarga.VACIO,
            fallaBff(422, "ubicacion_invalida") to FalloCarga.UBICACION,
            fallaBff(422, "fecha_de_captura_invalida") to FalloCarga.FECHA,
            fallaBff(409, "siniestro_cerrado") to FalloCarga.CERRADO,
            fallaBff(404, "siniestro_no_encontrado") to FalloCarga.NO_ENCONTRADO,
            fallaBff(503, "almacenamiento_no_disponible") to FalloCarga.ALMACENAMIENTO,
            fallaBff(500, "interno") to FalloCarga.GENERICO,
        )
        casos.forEach { (error, esperado) ->
            api.carga = { throw error }
            val g = gestor(SavedStateHandle()).conFoto()
            g.subir("SIN-1")
            val adjunto = g.estado.adjuntos.single()
            assertEquals(EstadoAdjunto.FALLIDO, adjunto.estado)
            assertEquals(esperado, adjunto.fallo)
            assertEquals(esperado.reintentable, g.estado.puedeReintentar)
        }
    }

    @Test
    fun `un archivo que falla no impide enviar los demas`() {
        api.carga = { datos ->
            if (datos.nombre == "a.jpg") throw fallaBff(503, "almacenamiento_no_disponible")
            api.evidenciaDe(datos)
        }
        val g = gestor().conFoto("a.jpg").conFoto("b.jpg")
        g.subir("SIN-1")
        assertEquals(listOf(EstadoAdjunto.FALLIDO, EstadoAdjunto.ENVIADO), g.estado.adjuntos.map { it.estado })
    }

    @Test
    fun `si el sistema borro el temporal lo explica sin ofrecer reintentar`() {
        val g = gestor().conFoto()
        java.io.File(g.estado.adjuntos.single().ruta).delete()
        g.subir("SIN-1")
        assertEquals(FalloCarga.ARCHIVO_PERDIDO, g.estado.adjuntos.single().fallo)
        assertFalse(g.estado.puedeReintentar)
        assertTrue(api.cargas.isEmpty())
    }

    @Test
    fun `un 401 delega al flujo de sesion`() {
        api.carga = { throw fallaBff(401, "sesion_no_vigente") }
        val g = gestor().conFoto()
        g.subir("SIN-1")
        assertTrue(g.estado.sesionVencida)
    }

    // ── Reintentos sin duplicar ───────────────────────────────────────────────────────────────

    @Test
    fun `si la respuesta se perdio, el reintento consulta y no reenvia`() {
        api.carga = { datos ->
            api.evidenciaDe(datos)
            throw IOException("respuesta perdida")
        }
        val g = gestor().conFoto()
        g.subir("SIN-1")
        assertEquals(FalloCarga.SIN_RED, g.estado.adjuntos.single().fallo)

        g.reintentar("SIN-1")

        assertEquals(1, api.cargas.size)
        assertEquals(1, api.evidencias.size)
        assertEquals(EstadoAdjunto.ENVIADO, g.estado.adjuntos.single().estado)
    }

    @Test
    fun `si la carga no llego, el reintento la reenvia con el mismo nombre`() {
        api.carga = { throw IOException("sin red") }
        val g = gestor().conFoto()
        g.subir("SIN-1")
        api.carga = { api.evidenciaDe(it) }
        g.reintentar("SIN-1")
        assertEquals(listOf("a.jpg", "a.jpg"), api.cargas.map { it.nombre })
        assertEquals(1, api.evidencias.size)
        assertEquals(EstadoAdjunto.ENVIADO, g.estado.adjuntos.single().estado)
    }

    @Test
    fun `sin poder consultar no se reenvia nada`() {
        api.carga = { throw IOException("sin red") }
        val g = gestor().conFoto()
        g.subir("SIN-1")
        api.consulta = { throw IOException("sin red") }
        g.reintentar("SIN-1")
        assertEquals(1, api.cargas.size)
        assertTrue(g.estado.errorConsulta)
        assertTrue(g.estado.puedeReintentar)
    }

    // ── Agregar después, borrador y limpieza ──────────────────────────────────────────────────

    @Test
    fun `agregar despues envia solo lo nuevo al mismo siniestro`() {
        val g = gestor().conFoto("a.jpg")
        g.subir("SIN-1")
        g.agregarMas()
        assertTrue(g.estado.porEnviar.isEmpty())
        g.conFoto("b.jpg")
        g.subir("SIN-1")
        assertEquals(listOf("a.jpg", "b.jpg"), api.cargas.map { it.nombre })
        assertFalse(g.estado.agregando)
    }

    @Test
    fun `cancelar agregar borra lo que no se envio y conserva lo enviado`() {
        val g = gestor().conFoto("a.jpg")
        g.subir("SIN-1")
        g.agregarMas()
        g.conFoto("b.jpg")
        g.cancelarAgregar()
        assertEquals(listOf("a.jpg"), g.estado.adjuntos.map { it.nombre })
        assertFalse(g.estado.agregando)
    }

    @Test
    fun `cancelar agregar mientras se prepara no deja entrar ni enviar ese archivo`() {
        val g = gestor().conFoto("a.jpg")
        g.subir("SIN-1")
        g.agregarMas()
        val espera = CompletableDeferred<Unit>().also { almacen.espera = it }
        g.conFoto("b.jpg")
        assertTrue(g.estado.preparando)

        g.cancelarAgregar()
        espera.complete(Unit)

        assertFalse(g.estado.preparando)
        assertEquals(listOf("a.jpg"), g.estado.adjuntos.map { it.nombre })
        // Lo que la preparación cancelada haya escrito no está entre lo que se conserva.
        assertEquals(g.estado.adjuntos.map { it.ruta }.toSet(), almacen.conservados)
        almacen.espera = null
        g.agregarMas()
        g.conFoto("c.jpg")
        g.subir("SIN-1")
        assertEquals(listOf("a.jpg", "c.jpg"), api.cargas.map { it.nombre })
    }

    @Test
    fun `una consulta que termina durante la carga no habilita salir`() {
        val carga = CompletableDeferred<Unit>()
        api.carga = { datos -> carga.await().let { api.evidenciaDe(datos) } }
        val g = gestor().conFoto()
        g.subir("SIN-1")
        assertTrue(g.estado.enviando)

        g.consultar("SIN-1")
        assertTrue(g.estado.enviando)

        carga.complete(Unit)
        assertFalse(g.estado.enviando)
        assertEquals(EstadoAdjunto.ENVIADO, g.estado.adjuntos.single().estado)
    }

    @Test
    fun `los adjuntos y el lugar sobreviven a la muerte del proceso`() {
        coordenadas = null
        val g = gestor().conFoto()
        g.cambiarLugar(g.estado.adjuntos.single().id, "Calle 100")
        val restaurado = gestor()
        assertEquals("Calle 100", restaurado.estado.adjuntos.single().lugar)
        assertEquals(setOf(restaurado.estado.adjuntos.single().ruta), almacen.conservados)
    }

    @Test
    fun `lo que salia al morir el proceso queda incierto y se reconcilia al reintentar`() {
        val handle = SavedStateHandle()
        var restaurado: GestorEvidencia? = null
        api.carga = { datos ->
            api.evidenciaDe(datos)
            restaurado = gestor(handle)
            throw IOException("el proceso murió")
        }
        gestor(handle).conFoto().subir("SIN-1")
        val g = requireNotNull(restaurado)
        assertEquals(EstadoAdjunto.FALLIDO, g.estado.adjuntos.single().estado)
        g.reintentar("SIN-1")
        assertEquals(1, api.cargas.size)
    }

    @Test
    fun `reanudar sube lo que no alcanzo a salir`() {
        val handle = SavedStateHandle()
        gestor(handle).conFoto()
        gestor(handle).reanudar("SIN-1")
        assertEquals(1, api.cargas.size)
    }

    @Test
    fun `salir del reporte no deja temporales`() {
        val g = gestor().conFoto("a.jpg").conFoto("b.jpg")
        val rutas = g.estado.adjuntos.map { it.ruta }
        g.descartar()
        assertEquals(rutas, almacen.borrados)
        assertTrue(g.estado.adjuntos.isEmpty())
    }

    // ── Respuesta a un requerimiento (WEB-F06) ───────────────────────────────────────────────

    private fun gestorUnico() = GestorEvidencia(
        api,
        FuentesEvidencia(almacen, { coordenadas }, { ahora }),
        CoroutineScope(Dispatchers.Main),
        borrador,
        unico = true,
    )

    @Test
    fun `sin modo unico agregar otro archivo los conserva todos, como en el reporte`() {
        val g = gestor()
        g.elegido("a.jpg")
        g.elegido("b.jpg")
        assertEquals(listOf("a.jpg", "b.jpg"), g.estado.adjuntos.map { it.nombre })
    }

    @Test
    fun `en modo unico un archivo con envio incierto no se reemplaza porque quiza ya llego`() = runBlocking {
        val g = gestorUnico()
        g.elegido("a.jpg")
        api.carga = { throw IOException("tiempo agotado") }
        assertEquals(RespuestaRequerimiento.SinRespuesta, g.responder("SIN-1", "REQ-1"))

        g.elegido("b.jpg")

        assertEquals(listOf("a.jpg", "b.jpg"), g.estado.adjuntos.map { it.nombre })
    }

    @Test
    fun `responder sin archivo listo no envia nada`() = runBlocking {
        assertEquals(RespuestaRequerimiento.SinArchivo, gestorUnico().responder("SIN-1", "REQ-1"))
        assertTrue(api.cargas.isEmpty())
    }
}
