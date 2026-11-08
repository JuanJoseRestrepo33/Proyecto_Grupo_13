package co.solventa.feature.siniestros.borrador

import co.solventa.core.network.SolicitudSiniestro
import co.solventa.core.network.TipoEventoSiniestro
import co.solventa.feature.siniestros.CLIENTE
import co.solventa.feature.siniestros.Entorno
import co.solventa.feature.siniestros.LlaveJca
import co.solventa.feature.siniestros.OTRO_CLIENTE
import co.solventa.feature.siniestros.POLIZA_VIGENTE
import co.solventa.feature.siniestros.ReglaMain
import co.solventa.feature.siniestros.fallaBff
import co.solventa.feature.siniestros.siniestroDe
import co.solventa.feature.siniestros.evidencia.EstadoAdjunto
import co.solventa.feature.siniestros.evidencia.FalloCarga
import co.solventa.feature.siniestros.evidencia.FormatoEvidencia
import co.solventa.core.network.ErrorBff
import java.io.File
import java.io.IOException
import java.security.KeyStoreException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class EnviadorBorradoresTest {
    @get:Rule
    val main = ReglaMain()

    @get:Rule
    val carpeta = TemporaryFolder()

    private val entornos = mutableListOf<Entorno>()

    private fun entorno(conectado: Boolean = true, llave: LlaveJca = LlaveJca()): Entorno =
        Entorno(carpeta.root, llave = llave).also {
            it.monitor.conectado.value = conectado
            entornos += it
        }

    @After
    fun cerrar() = entornos.forEach { it.alcance.cancel() }

    private val solicitud = SolicitudSiniestro(
        POLIZA_VIGENTE.numero,
        TipoEventoSiniestro.HURTO,
        "2026-10-23",
        "Me robaron el celular en el bus",
    )

    private var adjuntos = 0

    private fun adjunto(contenido: String = "foto ${adjuntos + 1}"): AdjuntoNuevo {
        adjuntos += 1
        val archivo = File(carpeta.root, "cache-$adjuntos.jpg").apply { writeText(contenido) }
        return AdjuntoNuevo(
            AdjuntoBorrador("ADJ-$adjuntos", "evidencia-$adjuntos.jpg", FormatoEvidencia.JPEG, archivo.length()),
            archivo,
        )
    }

    /** El bucle reagenda sus esperas sin fin: se cancela antes de que `runTest` avance hasta quedar ocioso. */
    private fun test(cuerpo: suspend TestScope.() -> Unit) = runTest(main.dispatcher) {
        try {
            cuerpo()
        } finally {
            entornos.forEach { it.alcance.cancel() }
        }
    }

    private suspend fun Entorno.enviar(vararg archivos: AdjuntoNuevo, clave: String = "clave-1") =
        enviador.enviarDesdeAsistente(solicitud, clave, archivos.toList())

    private fun Entorno.unico(): Borrador = borradores().single()

    private fun alterar(archivo: File) {
        val bytes = archivo.readBytes()
        bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 1).toByte()
        archivo.writeBytes(bytes)
    }

    private fun Entorno.enviadas() = enviador.tarjetas.value.filterIsInstance<Tarjeta.Enviada>()

    // ── Guardado ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `guarda el borrador cifrado antes del primer POST y suelta la cache`() = test {
        val e = entorno()
        val espera = CompletableDeferred<Unit>()
        e.api.reporte = { _, s ->
            espera.await()
            siniestroDe(s)
        }
        val foto = adjunto()
        val resultado = async { e.enviar(foto) }
        runCurrent()
        val enCurso = e.unico()
        assertEquals("clave-1", enCurso.claveIdempotencia)
        assertEquals(EstadoBorrador.ENVIANDO, enCurso.estado)
        assertEquals("evidencia-1.jpg", enCurso.adjuntos.single().nombre)
        assertFalse("la copia en claro de la caché se borró", foto.archivo.exists())
        espera.complete(Unit)
        assertTrue(resultado.await() is ResultadoEnvio.Reportado)
    }

    @Test
    fun `si no se puede escribir el borrador no envia nada y conserva el archivo`() = test {
        val e = entorno()
        e.cifrado.bytes = 4
        val foto = adjunto()
        assertEquals(ResultadoEnvio.NoGuardado, e.enviar(foto))
        assertTrue(e.api.reportes.isEmpty())
        assertTrue(e.borradores().isEmpty())
        assertTrue(foto.archivo.exists())
    }

    // ── Envío y reintentos ────────────────────────────────────────────────────────────────────

    @Test
    fun `sin red queda esperando y al volver la red se envia solo con su evidencia`() = test {
        val e = entorno(conectado = false)
        val foto = adjunto("contenido de la foto")
        assertEquals(ResultadoEnvio.SinRed, e.enviar(foto))
        assertTrue(e.api.reportes.isEmpty())
        assertEquals(EstadoBorrador.ESPERANDO_CONEXION, e.unico().estado)

        e.monitor.conectado.value = true
        runCurrent()
        assertEquals(listOf("clave-1" to solicitud), e.api.reportes)
        assertArrayEquals("contenido de la foto".toByteArray(), e.api.cargas.single().contenido)
        assertEquals("SIN-2026-000123", e.enviadas().single().registro.referencia)
        assertTrue("el borrador se borra al terminar", e.borradores().isEmpty())
        assertEquals(listOf(CLIENTE, CLIENTE, CLIENTE), e.api.duenios)
    }

    @Test
    fun `una respuesta perdida se reintenta con la misma clave y los mismos datos`() = test {
        val e = entorno()
        var intentos = 0
        e.api.reporte = { _, s ->
            intentos += 1
            if (intentos == 1) throw IOException("tiempo agotado") else siniestroDe(s)
        }
        assertEquals(ResultadoEnvio.Incierto, e.enviar())
        assertTrue(e.unico().intentoIncierto)
        advanceTimeBy(29_000)
        assertEquals(1, e.api.reportes.size)
        advanceTimeBy(2_000)
        assertEquals(listOf("clave-1" to solicitud, "clave-1" to solicitud), e.api.reportes)
        assertEquals(1, e.enviadas().size)
    }

    @Test
    fun `las esperas crecen mientras la red sigue y se reinician al volver`() = test {
        val e = entorno()
        e.api.reporte = { _, _ -> throw fallaBff(503, ErrorBff("error", "caído")) }
        e.enviar()
        advanceTimeBy(30_001)
        assertEquals(2, e.api.reportes.size)
        advanceTimeBy(59_000)
        assertEquals(2, e.api.reportes.size)
        advanceTimeBy(2_000)
        assertEquals(3, e.api.reportes.size)
        // Se cae y vuelve la red: el siguiente intento sale ya, sin esperar los 2 minutos.
        e.monitor.conectado.value = false
        e.monitor.conectado.value = true
        runCurrent()
        assertEquals(4, e.api.reportes.size)
        assertEquals(setOf("clave-1"), e.api.reportes.map { it.first }.toSet())
    }

    @Test
    fun `tras la muerte del proceso en ENVIANDO se reintenta como incierto con su clave`() = test {
        val primero = entorno(conectado = false)
        primero.enviar(adjunto())
        primero.almacen.actualizar(primero.unico().copy(estado = EstadoBorrador.ENVIANDO))
        primero.alcance.cancel()

        // Proceso nuevo: el BFF rechaza, pero el intento anterior pudo registrar el siniestro.
        val nuevo = entorno(llave = primero.llave)
        nuevo.api.reporte = { _, _ -> throw fallaBff(404, "poliza_no_encontrada") }
        nuevo.api.siniestros = { listOf(siniestroDe(solicitud)) }
        nuevo.enviador.activar(CLIENTE)
        runCurrent()
        assertEquals(listOf("clave-1" to solicitud), nuevo.api.reportes)
        assertEquals(1, nuevo.api.consultasSiniestros)
        assertEquals("SIN-2026-000123", nuevo.enviadas().single().registro.referencia)
        assertEquals(1, nuevo.api.cargas.size)
    }

    @Test
    fun `tras la muerte del proceso subiendo evidencia concilia y no reenvia lo que llego`() = test {
        val primero = entorno(conectado = false)
        primero.enviar(adjunto(), adjunto())
        val b = primero.unico()
        primero.almacen.actualizar(
            b.copy(
                estado = EstadoBorrador.SUBIENDO_EVIDENCIA,
                idSiniestro = "SIN-1",
                referencia = "SIN-2026-000123",
                adjuntos = b.adjuntos.map { it.copy(estado = EstadoAdjunto.ENVIANDO) },
            ),
        )
        primero.alcance.cancel()

        val nuevo = entorno(llave = primero.llave)
        nuevo.api.evidenciaDe(
            co.solventa.feature.siniestros.CargaFalsa(
                "SIN-1",
                "evidencia-1.jpg",
                "image/jpeg",
                ByteArray(0),
                null,
                null,
                null,
                null,
            ),
        )
        nuevo.enviador.activar(CLIENTE)
        runCurrent()
        assertTrue("el reporte no se vuelve a enviar", nuevo.api.reportes.isEmpty())
        assertEquals(listOf("evidencia-2.jpg"), nuevo.api.cargas.map { it.nombre })
        assertEquals(1, nuevo.enviadas().size)
    }

    // ── Rechazos y conciliación ───────────────────────────────────────────────────────────────

    @Test
    fun `un rechazo sin intento incierto queda para que el cliente corrija o descarte`() = test {
        val e = entorno()
        val vigencia = ErrorBff("evento_fuera_de_vigencia", "x", vigenteDesde = "2026-01-01")
        e.api.reporte = { _, _ -> throw fallaBff(409, vigencia) }
        val resultado = e.enviar()
        assertTrue(resultado is ResultadoEnvio.Rechazado)
        val b = e.unico()
        assertEquals(EstadoBorrador.REQUIERE_ATENCION, b.estado)
        assertEquals("evento_fuera_de_vigencia", b.rechazo?.codigo)
        assertEquals(0, e.api.consultasSiniestros)
        advanceTimeBy(600_000)
        assertEquals("no se reintenta solo", 1, e.api.reportes.size)
    }

    @Test
    fun `corregir envia los datos nuevos con una clave nueva`() = test {
        val e = entorno()
        e.api.reporte = { _, _ -> throw fallaBff(404, "poliza_no_encontrada") }
        val rechazado = (e.enviar() as ResultadoEnvio.Rechazado).borrador
        e.api.reporte = { _, s -> siniestroDe(s) }
        val corregida = solicitud.copy(descripcion = "Me robaron el celular en el bus 2")
        val resultado = e.enviador.enviarDesdeAsistente(corregida, "clave-2", emptyList(), rechazado.id)
        assertTrue(resultado is ResultadoEnvio.Reportado)
        assertEquals("clave-2" to corregida, e.api.reportes.last())
    }

    @Test
    fun `con un intento incierto un rechazo se concilia y si lo encuentra sigue con esa referencia`() = test {
        val e = entorno()
        var intentos = 0
        e.api.reporte = { _, _ ->
            intentos += 1
            if (intentos == 1) throw IOException("sin respuesta") else throw fallaBff(404, "poliza_no_encontrada")
        }
        e.api.siniestros = { listOf(siniestroDe(solicitud, "SIN-2026-000777")) }
        e.enviar(adjunto())
        advanceTimeBy(30_001)
        assertEquals(1, e.api.consultasSiniestros)
        assertEquals("SIN-2026-000777", e.enviadas().single().registro.referencia)
        assertEquals(1, e.api.cargas.size)
    }

    @Test
    fun `si la conciliacion no lo encuentra pasa a requerir atencion`() = test {
        val e = entorno()
        var intentos = 0
        e.api.reporte = { _, _ ->
            intentos += 1
            if (intentos == 1) throw IOException("sin respuesta") else throw fallaBff(422, "solicitud_invalida")
        }
        e.enviar()
        advanceTimeBy(30_001)
        val b = e.unico()
        assertEquals(EstadoBorrador.REQUIERE_ATENCION, b.estado)
        assertFalse(b.intentoIncierto)
        assertEquals("clave-1", b.claveIdempotencia)
    }

    @Test
    fun `si no puede consultar sigue por conciliar y no deja corregir`() = test {
        val e = entorno()
        var intentos = 0
        e.api.reporte = { _, _ ->
            intentos += 1
            if (intentos == 1) throw IOException("sin respuesta") else throw fallaBff(404, "poliza_no_encontrada")
        }
        e.api.siniestros = { throw IOException("sin red") }
        e.enviar()
        advanceTimeBy(30_001)
        val b = e.unico()
        assertEquals(EstadoBorrador.POR_CONCILIAR, b.estado)
        val otra = solicitud.copy(descripcion = "Otra descripción")
        val corregir = e.enviador.enviarDesdeAsistente(otra, "clave-2", emptyList(), b.id)
        assertEquals(ResultadoEnvio.NoGuardado, corregir)
        assertEquals("clave-1", e.unico().claveIdempotencia)
        // Más tarde vuelve a intentar la conciliación.
        e.api.siniestros = { listOf(siniestroDe(solicitud)) }
        advanceTimeBy(60_001)
        assertEquals(1, e.enviadas().size)
    }

    @Test
    fun `clave en uso no rota la clave y concilia`() = test {
        val e = entorno()
        val enUso = ErrorBff("clave_idempotencia_en_uso", "x", referencia = "SIN-2026-000555")
        e.api.reporte = { _, _ -> throw fallaBff(409, enUso) }
        e.api.siniestros = { throw IOException("sin red") }
        assertEquals(ResultadoEnvio.PorConciliar, e.enviar())
        val b = e.unico()
        assertEquals("clave-1", b.claveIdempotencia)
        assertEquals("SIN-2026-000555", b.referenciaEnUso)
        assertEquals(1, e.api.consultasSiniestros)
    }

    // ── Exclusión mutua, orden y sesión ──────────────────────────────────────────────────────

    @Test
    fun `procesa los borradores de uno en uno y en orden de creacion`() = test {
        var instante = java.time.Instant.parse("2026-10-26T15:00:00Z")
        val e = Entorno(carpeta.root, reloj = { instante.also { instante = it.plusSeconds(1) } })
        entornos += e
        e.monitor.conectado.value = false
        e.enviador.enviarDesdeAsistente(solicitud.copy(descripcion = "Primero, en el bus"), "clave-1", emptyList())
        e.enviador.enviarDesdeAsistente(solicitud.copy(descripcion = "Segundo, en la casa"), "clave-2", emptyList())
        var enVuelo = 0
        var maximo = 0
        val espera = CompletableDeferred<Unit>()
        e.api.reporte = { _, s ->
            enVuelo += 1
            maximo = maxOf(maximo, enVuelo)
            espera.await()
            enVuelo -= 1
            siniestroDe(s)
        }
        e.monitor.conectado.value = true
        runCurrent()
        espera.complete(Unit)
        runCurrent()
        assertEquals(listOf("clave-1", "clave-2"), e.api.reportes.map { it.first })
        assertEquals(1, maximo)
    }

    @Test
    fun `descartar espera a que termine el paso en curso`() = test {
        val e = entorno()
        val espera = CompletableDeferred<Unit>()
        e.api.reporte = { _, s ->
            espera.await()
            siniestroDe(s)
        }
        val envio = launch { e.enviar() }
        runCurrent()
        val id = e.unico().id
        e.enviador.descartar(id)
        runCurrent()
        assertEquals("sigue mientras el POST está en vuelo", 1, e.borradores().size)
        espera.complete(Unit)
        envio.join()
        runCurrent()
        assertTrue(e.borradores().isEmpty())
    }

    @Test
    fun `ninguna solicitud del borrador sale con la sesion de otra cuenta`() = test {
        val e = entorno()
        val espera = CompletableDeferred<Unit>()
        e.api.reporte = { _, s ->
            espera.await()
            siniestroDe(s)
        }
        launch { e.enviar(adjunto()) }
        runCurrent()
        // Ingresa otra cuenta mientras el POST está en vuelo: la app detiene el enviador antes.
        e.enviador.detener()
        e.cliente = OTRO_CLIENTE
        e.enviador.activar(OTRO_CLIENTE)
        espera.complete(Unit)
        runCurrent()
        assertTrue("el borrador no se muestra a la otra cuenta", e.enviador.tarjetas.value.isEmpty())
        assertTrue("su evidencia no salió", e.api.cargas.isEmpty())
        assertTrue(e.api.duenios.none { it == OTRO_CLIENTE })

        // Vuelve su dueño: se retoma desde su estado y termina.
        e.enviador.detener()
        e.cliente = CLIENTE
        e.enviador.activar(CLIENTE)
        runCurrent()
        assertEquals(1, e.api.cargas.size)
        assertEquals(1, e.enviadas().size)
    }

    @Test
    fun `si la cuenta cambia entre la seleccion y el envio no sale nada`() = test {
        val e = entorno(conectado = false)
        e.enviar()
        e.cliente = OTRO_CLIENTE
        e.monitor.conectado.value = true
        runCurrent()
        assertTrue(e.api.reportes.isEmpty())
        assertEquals(EstadoBorrador.ESPERANDO_CONEXION, e.borradores(CLIENTE).single().estado)
    }

    @Test
    fun `si la cuenta cambia mientras se guarda no se reactiva al dueno anterior ni se muestran sus tarjetas`() = test {
        val e = entorno()
        e.enviador.activar(CLIENTE)
        val resultado = e.enviador.enviarDesdeAsistente(solicitud, "clave-1", emptyList()) {
            // Ingresa otra cuenta justo después de escribir el borrador.
            e.enviador.detener()
            e.cliente = OTRO_CLIENTE
            e.enviador.activar(OTRO_CLIENTE)
        }
        runCurrent()
        assertEquals(ResultadoEnvio.SinSesion, resultado)
        assertTrue(e.api.reportes.isEmpty())
        assertTrue("la cuenta nueva no ve el borrador", e.enviador.tarjetas.value.isEmpty())
        assertEquals(EstadoBorrador.ESPERANDO_CONEXION, e.borradores(CLIENTE).single().estado)
    }

    @Test
    fun `un reporte incierto o por conciliar bloquea uno nuevo hasta saber si quedo registrado`() = test {
        val e = entorno()
        e.api.reporte = { _, _ -> throw IOException("sin respuesta") }
        e.enviar()
        assertTrue(e.enviador.haySinConfirmar)
        e.api.reporte = { _, s -> siniestroDe(s) }
        advanceTimeBy(30_001)
        assertFalse(e.enviador.haySinConfirmar)
    }

    @Test
    fun `un borrador que no se pudo abrir por un fallo pasajero tambien bloquea uno nuevo`() = test {
        val e = entorno(conectado = false)
        e.enviar()
        e.llave.fallo = KeyStoreException("ocupado")
        e.monitor.conectado.value = true
        runCurrent()
        assertTrue(e.enviador.haySinConfirmar)
    }

    @Test
    fun `si no se puede escribir una transicion se reintenta sin quedar detenido`() = test {
        val e = entorno(conectado = false)
        e.enviar()
        e.cifrado.bytes = 4
        e.monitor.conectado.value = true
        runCurrent()
        assertTrue("no salió: no se pudo marcar ENVIANDO", e.api.reportes.isEmpty())
        e.cifrado.bytes = null
        advanceTimeBy(30_001)
        assertEquals(1, e.api.reportes.size)
        assertEquals(1, e.enviadas().size)
    }

    @Test
    fun `un 401 deja el borrador pendiente y pide ingresar`() = test {
        val e = entorno()
        e.api.reporte = { _, _ -> throw fallaBff(401, "sesion_no_vigente") }
        assertEquals(ResultadoEnvio.SinSesion, e.enviar())
        val tarjeta = e.enviador.tarjetas.value.single() as Tarjeta.DeBorrador
        assertTrue(tarjeta.requiereIngreso)
        assertFalse(tarjeta.borrador.intentoIncierto)
        e.api.reporte = { _, s -> siniestroDe(s) }
        e.enviador.activar(CLIENTE)
        runCurrent()
        assertEquals(1, e.enviadas().size)
    }

    // ── Evidencia ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `si falla un archivo el siniestro sigue reportado y se puede reintentar o quitar`() = test {
        val e = entorno()
        e.api.carga = { throw fallaBff(503, "almacenamiento_no_disponible") }
        val resultado = e.enviar(adjunto(), adjunto())
        assertTrue(resultado is ResultadoEnvio.Reportado)
        runCurrent()
        val b = e.unico()
        assertEquals(EstadoBorrador.REPORTADO, b.estado)
        assertEquals(listOf(FalloCarga.ALMACENAMIENTO, FalloCarga.ALMACENAMIENTO), b.adjuntos.map { it.fallo })

        e.api.carga = { e.api.evidenciaDe(it) }
        e.enviador.reintentar(b.id)
        runCurrent()
        assertEquals(2, e.api.evidencias.size)
        assertEquals(1, e.enviadas().size)
    }

    @Test
    fun `quitar el archivo que fallo termina el envio`() = test {
        val e = entorno()
        e.api.carga = { throw fallaBff(413, "archivo_demasiado_grande") }
        e.enviar(adjunto())
        runCurrent()
        val b = e.unico()
        e.enviador.quitarAdjunto(b.id, b.adjuntos.single().id)
        runCurrent()
        assertTrue(e.borradores().isEmpty())
        assertEquals(1, e.enviadas().size)
        e.enviador.entendido(b.id)
        runCurrent()
        assertTrue(e.enviador.tarjetas.value.isEmpty())
    }

    @Test
    fun `si se corta la red subiendo evidencia se retoma sola al volver`() = test {
        val e = entorno()
        e.api.carga = { throw IOException("se cortó") }
        e.enviar(adjunto())
        runCurrent()
        assertEquals(FalloCarga.SIN_RED, e.unico().adjuntos.single().fallo)
        e.api.carga = { e.api.evidenciaDe(it) }
        e.monitor.conectado.value = false
        e.monitor.conectado.value = true
        runCurrent()
        assertEquals(1, e.enviadas().size)
    }

    // ── Ilegibles ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `un borrador alterado queda ilegible y solo se elimina si el cliente lo decide`() = test {
        val e = entorno(conectado = false)
        e.enviar()
        val id = e.unico().id
        val manifiesto = File(e.raiz, "$CLIENTE/$id/borrador.bin")
        alterar(manifiesto)
        e.monitor.conectado.value = true
        runCurrent()
        assertEquals(listOf(Tarjeta.Ilegible(id, permanente = true)), e.enviador.tarjetas.value)
        assertTrue(manifiesto.exists())
        e.enviador.descartar(id)
        runCurrent()
        assertFalse(manifiesto.exists())
    }

    @Test
    fun `un fallo temporal al descifrar conserva el borrador y lo reintenta`() = test {
        val e = entorno(conectado = false)
        e.enviar()
        e.llave.fallo = KeyStoreException("ocupado")
        e.monitor.conectado.value = true
        runCurrent()
        assertFalse((e.enviador.tarjetas.value.single() as Tarjeta.Ilegible).permanente)
        assertTrue(e.api.reportes.isEmpty())
        e.llave.fallo = null
        advanceTimeBy(30_001)
        assertEquals(1, e.enviadas().size)
    }

    @Test
    fun `la cache de polizas es del cliente con sesion`() = test {
        val e = entorno()
        e.enviador.guardarPolizas(listOf(POLIZA_VIGENTE))
        assertEquals(listOf(POLIZA_VIGENTE), e.enviador.leerPolizas()?.polizas)
        e.cliente = OTRO_CLIENTE
        assertNull(e.enviador.leerPolizas())
    }

    @Test
    fun `las polizas consultadas por otra cuenta no se guardan en la carpeta de la vigente`() = test {
        val e = entorno()
        val quienConsulto = e.enviador.clienteActual()
        e.cliente = OTRO_CLIENTE
        e.enviador.guardarPolizas(listOf(POLIZA_VIGENTE), quienConsulto)
        assertNull(e.enviador.leerPolizas())
        e.cliente = CLIENTE
        assertNull(e.enviador.leerPolizas())
    }

    @Test
    fun `un fallo temporal al leer la evidencia se reintenta solo`() = test {
        val e = entorno(conectado = false)
        e.enviar(adjunto())
        val b = e.unico()
        val archivo = File(e.raiz, "$CLIENTE/${b.id}/${b.adjuntos.single().id}.bin")
        archivo.setReadable(false)
        e.monitor.conectado.value = true
        runCurrent()
        val pendiente = e.unico()
        assertTrue(pendiente.reportado)
        assertEquals("sigue lista, no fallida", EstadoAdjunto.LISTO, pendiente.adjuntos.single().estado)
        assertTrue(e.api.cargas.isEmpty())
        archivo.setReadable(true)
        advanceTimeBy(30_001)
        assertEquals(1, e.enviadas().size)
    }
}
