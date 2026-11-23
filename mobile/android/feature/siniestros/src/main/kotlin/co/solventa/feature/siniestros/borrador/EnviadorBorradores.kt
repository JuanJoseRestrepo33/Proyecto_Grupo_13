package co.solventa.feature.siniestros.borrador

import co.solventa.core.network.BffApi
import co.solventa.core.network.DuenioSolicitud
import co.solventa.core.network.PolizaResumen
import co.solventa.core.network.Resultado
import co.solventa.core.network.Siniestro
import co.solventa.core.network.SolicitudDeOtraCuenta
import co.solventa.core.network.SolicitudSiniestro
import co.solventa.core.network.llamarBff
import co.solventa.core.network.parteArchivo
import co.solventa.core.network.parteTexto
import co.solventa.feature.siniestros.evidencia.EstadoAdjunto
import co.solventa.feature.siniestros.evidencia.FalloCarga
import co.solventa.feature.siniestros.evidencia.falloDe
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/** Lo que Inicio muestra de cada borrador del cliente con sesión (M5–M10, M12, M14). */
sealed interface Tarjeta {
    val id: String

    /** [enCurso]: el enviador lo está procesando y no se puede descartar (M6). */
    data class DeBorrador(val borrador: Borrador, val enCurso: Boolean, val requiereIngreso: Boolean) : Tarjeta {
        override val id: String get() = borrador.id
    }

    /** [permanente]: la llave o el archivo ya no sirven y solo el cliente decide eliminarlo (M14). */
    data class Ilegible(override val id: String, val permanente: Boolean) : Tarjeta

    /** Se ve hasta que el cliente toca «Entendido» (M7). */
    data class Enviada(val registro: RegistroEnviado) : Tarjeta {
        override val id: String get() = registro.id
    }
}

/** Un archivo listo del asistente y su copia en claro en la caché, que pasa al almacén cifrado. */
data class AdjuntoNuevo(val adjunto: AdjuntoBorrador, val archivo: File)

/** El primer envío desde el asistente, que decide qué pantalla sigue. */
sealed interface ResultadoEnvio {
    /** 201/200: la confirmación de #169. La evidencia sigue subiendo con el enviador. */
    data class Reportado(val siniestro: Siniestro) : ResultadoEnvio

    /** Se sabía que no había red: «Reporte guardado» (M4a). */
    data object SinRed : ResultadoEnvio

    /** Se perdió la respuesta o el BFF falló sin código: «No pudimos confirmar el envío» (M4b). */
    data object Incierto : ResultadoEnvio

    /** Rechazo definitivo sin intento incierto: el asistente muestra el aviso y deja corregir. */
    data class Rechazado(val borrador: Borrador) : ResultadoEnvio

    /** Un intento anterior pudo registrarlo: se confirma antes de ofrecer otra cosa (M10). */
    data object PorConciliar : ResultadoEnvio

    /** 401 sin renovación: el borrador espera al ingreso (M12). */
    data object SinSesion : ResultadoEnvio

    /** No se pudo escribir el borrador: no se envió nada y los datos siguen en el asistente (M15). */
    data object NoGuardado : ResultadoEnvio
}

/** Esperas entre reintentos mientras la red sigue arriba; vuelven a empezar cuando la red regresa. */
val ESPERAS_REINTENTO: List<Duration> =
    listOf(30L, 60L, 120L, 300L, 600L).map(Duration::ofSeconds)

/**
 * Envía los borradores del cliente con sesión (MOB-F05-02), con alcance de aplicación: al volver la
 * red, al ingresar y al abrir la app. Uno a la vez y en orden de creación; un solo [Mutex] serializa el
 * envío, la subida de evidencia, el descarte y la corrección.
 *
 * Cada ejecución se ata a su dueño: cada solicitud lleva [DuenioSolicitud] e `InterceptorDuenio` la
 * aborta si la sesión cambió; además, la app llama [detener] antes de reemplazar la sesión.
 *
 * El envío depende de la sesión, que vive en memoria: si el proceso muere, se retoma al volver a
 * ingresar, desde el estado guardado y con la misma clave.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class EnviadorBorradores(
    private val api: BffApi,
    private val almacen: AlmacenBorradoresCifrado,
    private val monitor: MonitorConectividad,
    private val clienteVigente: () -> String?,
    private val alcance: CoroutineScope,
    private val reloj: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val esperas: List<Duration> = ESPERAS_REINTENTO,
) {
    private val mutex = Mutex()
    private val estado = MutableStateFlow<List<Tarjeta>>(emptyList())
    private val despertador = Channel<Senal>(Channel.CONFLATED)
    private val nivelEspera = AtomicInteger(0)

    @Volatile
    private var duenio: String? = null
    private var trabajo: Job? = null

    @Volatile
    private var requiereIngreso = false

    @Volatile
    private var enCurso: String? = null

    /** Borradores que el asistente está enviando: el bucle no los toma. */
    private val reservados = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Siniestros confirmados en este proceso, para la confirmación del asistente. */
    private val confirmados = mutableMapOf<String, Siniestro>()

    val tarjetas: StateFlow<List<Tarjeta>> = estado.asStateFlow()

    val conectado: StateFlow<Boolean> get() = monitor.conectado

    /** Al ingresar o al abrir la app con sesión: procesa los borradores de [cliente]. */
    @Synchronized
    fun activar(cliente: String) {
        requiereIngreso = false
        if (duenio == cliente && trabajo?.isActive == true) {
            despertar()
            return
        }
        detener()
        duenio = cliente
        trabajo = alcance.launch { bucle(cliente) }
    }

    /** Como [activar], pero sin despertar el bucle si ya está corriendo. */
    @Synchronized
    private fun asegurarActivo(cliente: String) {
        // Si la cuenta cambió mientras se guardaba, el borrador queda para su dueño: no se reactiva.
        if (clienteVigente() != cliente) return
        if (duenio != cliente || trabajo?.isActive != true) activar(cliente)
    }

    /** Antes de cerrar la sesión o cambiar de cuenta: nada del anterior sigue saliendo. */
    @Synchronized
    fun detener() {
        trabajo?.cancel()
        trabajo = null
        duenio = null
        enCurso = null
        estado.value = emptyList()
    }

    fun despertar() {
        despertador.trySend(Senal.AHORA)
    }

    /** Hubo un intento incierto fuera del bucle: el siguiente sale tras la espera, no ya. */
    private fun despertarTrasEspera() {
        despertador.trySend(Senal.TRAS_ESPERA)
    }

    /** Hay un reporte que quizá ya quedó registrado: Inicio no ofrece uno nuevo hasta saberlo. */
    val haySinConfirmar: Boolean
        get() = estado.value.bloqueanReporteNuevo()

    /** Pendientes del cliente con sesión: el aviso al cerrar sesión (M13). */
    val pendientes: Int
        get() = estado.value.count { it !is Tarjeta.Enviada }

    // ── Asistente ─────────────────────────────────────────────────────────────────────────────

    /**
     * «Enviar reporte»: guarda el borrador cifrado antes del primer `POST`, con o sin red, y si hay red
     * lo envía mientras el cliente espera. [idCorregir] es el borrador rechazado que se corrige: sus datos
     * salen con [clave], que es nueva. [alGuardar] corre al confirmarse el guardado, cuando los archivos de
     * la caché ya son del almacén.
     *
     * Corre en el alcance de la app: si el cliente sale del asistente, el envío no se corta a medias.
     * Mientras tanto el bucle no toma este borrador; la evidencia sigue después, en segundo plano.
     */
    suspend fun enviarDesdeAsistente(
        solicitud: SolicitudSiniestro,
        clave: String,
        adjuntos: List<AdjuntoNuevo>,
        idCorregir: String? = null,
        alGuardar: (String) -> Unit = {},
    ): ResultadoEnvio {
        val cliente = clienteVigente() ?: return ResultadoEnvio.NoGuardado
        return alcance.async {
            val id = mutex.withLock {
                val guardado = if (idCorregir == null) {
                    guardarNuevo(cliente, solicitud, clave, adjuntos)
                } else {
                    corregir(cliente, idCorregir, solicitud, clave, adjuntos)
                }
                guardado?.also { reservados += it.id }?.id
            } ?: return@async ResultadoEnvio.NoGuardado
            var resultado: ResultadoEnvio = ResultadoEnvio.Incierto
            try {
                alGuardar(id)
                asegurarActivo(cliente)
                resultado = when {
                    // La cuenta cambió mientras se guardaba: el borrador espera a su dueño.
                    clienteVigente() != cliente -> ResultadoEnvio.SinSesion
                    monitor.conectado.value -> primerEnvio(cliente, id)
                    else -> ResultadoEnvio.SinRed
                }
                resultado
            } finally {
                reservados -= id
                // Reportado: la evidencia sigue ya. Incierto o por conciliar: tras la primera espera.
                val incierto = resultado == ResultadoEnvio.Incierto || resultado == ResultadoEnvio.PorConciliar
                if (incierto) despertarTrasEspera() else despertar()
            }
        }.await()
    }

    @Suppress("ReturnCount")
    private suspend fun primerEnvio(cliente: String, id: String): ResultadoEnvio {
        val final = try {
            mutex.withLock { avanzar(cliente, id, soloReporte = true) }
        } catch (ignored: FalloAlmacen) {
            // El disco quedó en el estado anterior: el reintento usa la misma clave y no duplica.
            return ResultadoEnvio.Incierto
        } catch (ignored: Abortado) {
            return ResultadoEnvio.SinSesion
        }
        return when {
            final == null -> ResultadoEnvio.Incierto
            final.reportado -> confirmados[id]?.let { ResultadoEnvio.Reportado(it) } ?: ResultadoEnvio.Incierto
            final.estado == EstadoBorrador.REQUIERE_ATENCION -> ResultadoEnvio.Rechazado(final)
            final.estado == EstadoBorrador.POR_CONCILIAR -> ResultadoEnvio.PorConciliar
            requiereIngreso -> ResultadoEnvio.SinSesion
            else -> ResultadoEnvio.Incierto
        }
    }

    /** Se llama con el [mutex] tomado. null si no se pudo escribir: no se envía nada (M15). */
    private suspend fun guardarNuevo(
        cliente: String,
        solicitud: SolicitudSiniestro,
        clave: String,
        adjuntos: List<AdjuntoNuevo>,
    ): Borrador? {
        val borrador = Borrador(
            id = UUID.randomUUID().toString(),
            duenio = cliente,
            solicitud = solicitud,
            claveIdempotencia = clave,
            adjuntos = adjuntos.map { it.adjunto.copy(estado = EstadoAdjunto.LISTO, fallo = null) },
            creadoEn = reloj().toString(),
        )
        return try {
            withContext(io) {
                almacen.guardarNuevo(borrador, adjuntos.associate { it.adjunto.id to it.archivo })
                adjuntos.forEach { it.archivo.delete() }
            }
            borrador.also(::publicar)
        } catch (ignored: IOException) {
            null
        }
    }

    /**
     * Tras un rechazo sin intento incierto, los datos corregidos reemplazan a los anteriores. No aplica
     * mientras el borrador está por conciliar: el reporte anterior pudo quedar registrado.
     * Se llama con el [mutex] tomado.
     */
    private suspend fun corregir(
        cliente: String,
        id: String,
        solicitud: SolicitudSiniestro,
        clave: String,
        nuevos: List<AdjuntoNuevo>,
    ): Borrador? = try {
        withContext(io) {
            val actual = almacen.leer(cliente, id)
            if (actual.estado != EstadoBorrador.REQUIERE_ATENCION) return@withContext null
            val corregido = actual.copy(
                solicitud = solicitud,
                claveIdempotencia = clave,
                adjuntos = actual.adjuntos + nuevos.map { it.adjunto.copy(estado = EstadoAdjunto.LISTO, fallo = null) },
                estado = EstadoBorrador.ESPERANDO_CONEXION,
                intentoIncierto = false,
                rechazo = null,
                referenciaEnUso = null,
            )
            almacen.actualizarConArchivos(corregido, nuevos.associate { it.adjunto.id to it.archivo })
            nuevos.forEach { it.archivo.delete() }
            corregido
        }?.also(::publicar)
    } catch (ignored: IOException) {
        null
    }

    /** El borrador para corregirlo en el asistente; null si ya no está o no se puede leer. */
    suspend fun leer(id: String): Borrador? {
        val cliente = clienteVigente() ?: return null
        return mutex.withLock {
            try {
                withContext(io) { almacen.leer(cliente, id) }
            } catch (ignored: BorradorIlegible) {
                null
            }
        }
    }

    /** La cuenta con sesión ahora: quien consulta las pólizas la toma antes de pedirlas. */
    fun clienteActual(): String? = clienteVigente()

    /**
     * La última lista de pólizas que cargó bien, cifrada y por cliente (M1). [cliente] es quien hizo la
     * consulta: si la sesión cambió mientras tanto, la lista no se guarda en la carpeta de otra cuenta.
     */
    suspend fun guardarPolizas(polizas: List<PolizaResumen>, cliente: String? = clienteVigente()) {
        if (cliente == null || cliente != clienteVigente()) return
        mutex.withLock {
            try {
                withContext(io) { almacen.guardarPolizas(cliente, PolizasGuardadas(polizas, reloj().toString())) }
            } catch (ignored: IOException) {
                // Sin caché, sin conexión se explica que hace falta conexión una vez (M2).
                Unit
            }
        }
    }

    suspend fun leerPolizas(): PolizasGuardadas? {
        val cliente = clienteVigente() ?: return null
        return mutex.withLock { withContext(io) { almacen.leerPolizas(cliente) } }
    }

    // ── Acciones de Inicio ────────────────────────────────────────────────────────────────────

    /** Lo borra del teléfono con su evidencia (M11). Espera a que termine el paso en curso. */
    fun descartar(id: String) = conBorrador { cliente ->
        withContext(io) { almacen.borrar(cliente, id) }
        estado.update { lista -> lista.filterNot { it.id == id } }
    }

    fun entendido(id: String) = conBorrador { cliente ->
        withContext(io) { almacen.borrarEnviado(cliente, id) }
        estado.update { lista -> lista.filterNot { it is Tarjeta.Enviada && it.id == id } }
    }

    /** Vuelve a intentar lo que falló (M8) o la conciliación (M10). La evidencia se concilia antes. */
    fun reintentar(id: String) = conBorrador { cliente ->
        val actual = leerOculto(cliente, id) ?: return@conBorrador
        val listo = actual.copy(
            adjuntos = actual.adjuntos.map {
                if (it.reintentable) it.copy(estado = EstadoAdjunto.LISTO, fallo = null) else it
            },
        )
        guardarYPublicar(listo)
        if (monitor.conectado.value) procesarConManejo(cliente, id)
    }

    /** Quita un archivo que no se pudo adjuntar; el reporte sigue registrado (M8). */
    fun quitarAdjunto(id: String, idAdjunto: String) = conBorrador { cliente ->
        val actual = leerOculto(cliente, id) ?: return@conBorrador
        withContext(io) { almacen.borrarArchivo(cliente, id, idAdjunto) }
        guardarYPublicar(actual.copy(adjuntos = actual.adjuntos.filterNot { it.id == idAdjunto }))
        procesarConManejo(cliente, id)
    }

    private fun conBorrador(accion: suspend (String) -> Unit) {
        val cliente = duenio ?: clienteVigente() ?: return
        alcance.launch { mutex.withLock { if (activo(cliente)) accion(cliente) } }
    }

    // ── Bucle ─────────────────────────────────────────────────────────────────────────────────

    private suspend fun bucle(cliente: String) = coroutineScope {
        // Al volver la red se reinicia la espera: el siguiente intento sale ya.
        launch {
            monitor.conectado.drop(1).filter { it }.collect {
                nivelEspera.set(0)
                despertar()
            }
        }
        mutex.withLock { refrescar(cliente) }
        // Si el asistente acaba de tener un intento incierto, su señal ya está esperando: se respeta.
        var senal = despertador.tryReceive().getOrNull() ?: Senal.AHORA
        while (true) {
            senal = when {
                !monitor.conectado.value || requiereIngreso -> despertador.receive()
                senal == Senal.TRAS_ESPERA -> esperar() ?: Senal.AHORA
                procesarTodos(cliente) -> esperar() ?: Senal.AHORA
                else -> {
                    nivelEspera.set(0)
                    despertador.receive()
                }
            }
        }
    }

    /** La espera que toca (30 s, 1, 2, 5 y 10 min); una señal la corta. null si se cumplió. */
    private suspend fun esperar(): Senal? {
        val espera = esperas[minOf(nivelEspera.getAndIncrement(), esperas.lastIndex)]
        return withTimeoutOrNull(espera.toMillis()) { despertador.receive() }
    }

    /** @return true si algo quedó para reintentar solo (intento incierto, conciliación, red cortada). */
    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun procesarTodos(cliente: String): Boolean {
        val entradas = mutex.withLock { refrescar(cliente) }
        var pendiente = entradas.any { it is EntradaBorrador.Ilegible && !it.permanente }
        for (entrada in entradas.filterIsInstance<EntradaBorrador.Legible>()) {
            if (!activo(cliente) || requiereIngreso) break
            if (entrada.id in reservados) continue
            val final = try {
                mutex.withLock { avanzar(cliente, entrada.id, soloReporte = false) }
            } catch (ignored: FalloAlmacen) {
                // No se pudo escribir la transición: queda como estaba en disco y se reintenta tras la espera.
                pendiente = true
                continue
            } catch (ignored: Abortado) {
                break
            }
            if (final != null && final.reintentoAutomatico()) pendiente = true
        }
        return pendiente
    }

    private suspend fun procesarConManejo(cliente: String, id: String) {
        try {
            avanzar(cliente, id, soloReporte = false)
        } catch (ignored: FalloAlmacen) {
            despertarTrasEspera()
        } catch (ignored: Abortado) {
            Unit
        }
    }

    /**
     * Lleva el borrador tan lejos como se pueda ahora. Se llama con el [mutex] tomado.
     * @return el borrador como quedó, o null si se terminó, se borró o no se pudo leer.
     */
    @Suppress("ReturnCount", "NestedBlockDepth")
    private suspend fun avanzar(cliente: String, id: String, soloReporte: Boolean): Borrador? {
        var borrador = leerOculto(cliente, id) ?: return null
        enCurso = id
        publicar(borrador)
        try {
            while (true) {
                val siguiente = when (borrador.estado) {
                    EstadoBorrador.ESPERANDO_CONEXION, EstadoBorrador.ENVIANDO -> enviarReporte(cliente, borrador)
                    EstadoBorrador.POR_CONCILIAR -> conciliar(cliente, borrador)
                    EstadoBorrador.REPORTADO, EstadoBorrador.SUBIENDO_EVIDENCIA ->
                        if (soloReporte) null else subirEvidencia(cliente, borrador)
                    EstadoBorrador.REQUIERE_ATENCION, EstadoBorrador.ENVIADO, EstadoBorrador.ILEGIBLE -> null
                }
                if (siguiente == null || siguiente == borrador) return leerOculto(cliente, id)
                if (siguiente.estado == EstadoBorrador.ENVIADO) {
                    terminar(cliente, siguiente)
                    return null
                }
                borrador = siguiente
            }
        } finally {
            enCurso = null
            leerOculto(cliente, id)?.let(::publicar)
        }
    }

    /** `POST /siniestros` con la clave del borrador. Un `ENVIANDO` guardado es un intento que quizá llegó. */
    @Suppress("ThrowsCount")
    private suspend fun enviarReporte(cliente: String, inicial: Borrador): Borrador? {
        // Sin la sesión del dueño no se marca un intento que no va a salir.
        if (!activo(cliente)) throw Abortado()
        val enviando = inicial.copy(
            estado = EstadoBorrador.ENVIANDO,
            intentoIncierto = inicial.intentoIncierto || inicial.estado == EstadoBorrador.ENVIANDO,
        )
        guardarYPublicar(enviando)
        val resultado = llamar(cliente) { duenio ->
            api.reportarSiniestro(enviando.claveIdempotencia, enviando.solicitud, duenio)
        }
        val siguiente = when (val respuesta = clasificarReporte(resultado)) {
            is RespuestaReporte.Registrado -> {
                confirmados[enviando.id] = respuesta.siniestro
                enviando.reportadoComo(respuesta.siniestro)
            }
            RespuestaReporte.Incierta ->
                enviando.copy(estado = EstadoBorrador.ESPERANDO_CONEXION, intentoIncierto = true)
            RespuestaReporte.SinSesion -> {
                requiereIngreso = true
                enviando.copy(estado = EstadoBorrador.ESPERANDO_CONEXION, intentoIncierto = inicial.intentoIncierto)
            }
            // No se rota la clave: ya hay un reporte con ella y hay que saber si es este.
            is RespuestaReporte.ClaveEnUso -> enviando.copy(
                estado = EstadoBorrador.POR_CONCILIAR,
                referenciaEnUso = respuesta.referencia,
                rechazo = RechazoBorrador(HTTP_CONFLICTO, "clave_idempotencia_en_uso"),
            )
            // El núcleo valida antes de resolver la idempotencia: un rechazo no prueba que un intento
            // anterior no haya registrado el siniestro.
            is RespuestaReporte.Rechazada -> enviando.copy(
                estado = if (enviando.intentoIncierto) {
                    EstadoBorrador.POR_CONCILIAR
                } else {
                    EstadoBorrador.REQUIERE_ATENCION
                },
                rechazo = respuesta.rechazo,
            )
        }
        guardarYPublicar(siguiente)
        return siguiente.takeUnless { it.estado == EstadoBorrador.ESPERANDO_CONEXION }
    }

    /** Busca en `listarSiniestros` el reporte de un intento anterior. Sin respuesta, sigue por conciliar. */
    @Suppress("ReturnCount")
    private suspend fun conciliar(cliente: String, borrador: Borrador): Borrador? {
        val resultado = llamar(cliente) { duenio -> api.listarSiniestros(duenio) }
        val siguiente = when (resultado) {
            is Resultado.Exito -> {
                val registrado = buscarRegistrado(borrador, resultado.valor)
                registrado?.let { confirmados[borrador.id] = it }
                registrado?.let(borrador::reportadoComo)
                    ?: borrador.copy(estado = EstadoBorrador.REQUIERE_ATENCION, intentoIncierto = false)
            }
            is Resultado.Falla -> {
                if (resultado.http == HTTP_NO_AUTENTICADO) requiereIngreso = true
                return null
            }
            Resultado.SinConexion -> return null
        }
        guardarYPublicar(siguiente)
        return siguiente
    }

    /**
     * Sube la evidencia pendiente, uno a uno, como #137. Antes concilia con `listarEvidencias`: lo que ya
     * está no se reenvía. Un fallo no deshace el reporte.
     */
    @Suppress("ReturnCount")
    private suspend fun subirEvidencia(cliente: String, inicial: Borrador): Borrador? {
        if (inicial.adjuntos.all { it.estado == EstadoAdjunto.ENVIADO }) {
            return inicial.copy(estado = EstadoBorrador.ENVIADO)
        }
        // Queda lo que falló y espera al cliente (M8): reintentar o quitar.
        if (inicial.adjuntosPendientes.isEmpty()) {
            if (inicial.estado != EstadoBorrador.REPORTADO) {
                guardarYPublicar(inicial.copy(estado = EstadoBorrador.REPORTADO))
            }
            return null
        }
        val idSiniestro = requireNotNull(inicial.idSiniestro)
        var borrador = inicial.copy(estado = EstadoBorrador.SUBIENDO_EVIDENCIA)
        guardarYPublicar(borrador)
        val consulta = llamar(cliente) { duenio -> api.listarEvidencias(idSiniestro, duenio) }
        if (consulta !is Resultado.Exito) {
            if (consulta is Resultado.Falla && consulta.http == HTTP_NO_AUTENTICADO) requiereIngreso = true
            guardarYPublicar(borrador.copy(estado = EstadoBorrador.REPORTADO))
            return null
        }
        val llegaron = consulta.valor.mapNotNull { it.nombreOriginal }.toSet()
        borrador = borrador.copy(
            adjuntos = borrador.adjuntos.map { adjunto ->
                if (adjunto.estado != EstadoAdjunto.ENVIADO && adjunto.nombre in llegaron) {
                    withContext(io) { almacen.borrarArchivo(cliente, borrador.id, adjunto.id) }
                    adjunto.copy(estado = EstadoAdjunto.ENVIADO, fallo = null)
                } else {
                    adjunto
                }
            },
        )
        guardarYPublicar(borrador)
        for (adjunto in borrador.adjuntosPendientes) {
            borrador = subirUno(cliente, borrador, idSiniestro, adjunto)
            if (requiereIngreso) break
        }
        if (borrador.adjuntos.all { it.estado == EstadoAdjunto.ENVIADO }) {
            return borrador.copy(estado = EstadoBorrador.ENVIADO)
        }
        guardarYPublicar(borrador.copy(estado = EstadoBorrador.REPORTADO))
        return null
    }

    @Suppress("ReturnCount")
    private suspend fun subirUno(
        cliente: String,
        inicial: Borrador,
        idSiniestro: String,
        adjunto: AdjuntoBorrador,
    ): Borrador {
        var borrador = inicial
        fun cambiar(cambio: (AdjuntoBorrador) -> AdjuntoBorrador) {
            borrador = borrador.copy(adjuntos = borrador.adjuntos.map { if (it.id == adjunto.id) cambio(it) else it })
        }
        // Un archivo alterado o perdido no se puede enviar: se explica en vez de reintentarlo sin fin.
        val ilegible = try {
            withContext(io) { almacen.verificarArchivo(cliente, borrador.id, adjunto.id) }
            null
        } catch (error: BorradorIlegible) {
            error
        }
        // Temporal (Keystore ocupado): sigue listo y el bucle lo reintenta tras la espera.
        if (ilegible != null && !ilegible.permanente) return borrador
        if (ilegible != null) {
            // Permanente: se explica y se puede quitar; reintentarlo no serviría.
            cambiar { it.copy(estado = EstadoAdjunto.FALLIDO, fallo = FalloCarga.ARCHIVO_PERDIDO) }
            guardarYPublicar(borrador)
            return borrador
        }
        cambiar { it.copy(estado = EstadoAdjunto.ENVIANDO, fallo = null) }
        guardarYPublicar(borrador)
        val lugar = adjunto.lugar.trim().takeIf { it.isNotEmpty() && !adjunto.conCoordenadas }
        val cuerpo = CuerpoDescifrado(adjunto.formato.tipoContenido, adjunto.tamano) {
            almacen.abrirArchivo(cliente, inicial.id, adjunto.id)
        }
        val resultado = llamar(cliente) { duenio ->
            api.adjuntarEvidencia(
                idSiniestro,
                parteArchivo(cuerpo, adjunto.nombre),
                parteTexto(adjunto.tomadaEn),
                parteTexto(adjunto.latitud?.toString()),
                parteTexto(adjunto.longitud?.toString()),
                parteTexto(lugar),
                duenio = duenio,
            )
        }
        when (resultado) {
            is Resultado.Exito -> {
                withContext(io) { almacen.borrarArchivo(cliente, borrador.id, adjunto.id) }
                cambiar { it.copy(estado = EstadoAdjunto.ENVIADO, fallo = null) }
            }
            is Resultado.Falla -> {
                if (resultado.http == HTTP_NO_AUTENTICADO) requiereIngreso = true
                cambiar { it.copy(estado = EstadoAdjunto.FALLIDO, fallo = falloDe(resultado)) }
            }
            Resultado.SinConexion -> cambiar { it.copy(estado = EstadoAdjunto.FALLIDO, fallo = FalloCarga.SIN_RED) }
        }
        guardarYPublicar(borrador)
        return borrador
    }

    /** Se borra el borrador; queda solo un registro cifrado mínimo para la tarjeta «Enviado». */
    private suspend fun terminar(cliente: String, borrador: Borrador) {
        val registro = RegistroEnviado(borrador.id, requireNotNull(borrador.referencia), reloj().toString())
        withContext(io) {
            almacen.guardarEnviado(cliente, registro)
            almacen.borrar(cliente, borrador.id)
        }
        if (cliente == duenio) {
            estado.update { lista ->
                (lista.filterNot { it.id == borrador.id } + Tarjeta.Enviada(registro)).ordenadas()
            }
        }
    }

    // ── Apoyo ─────────────────────────────────────────────────────────────────────────────────

    private fun activo(cliente: String) = duenio == cliente && clienteVigente() == cliente

    /** Toda llamada sale con su dueño; si la sesión ya es de otra cuenta, el procesamiento se aborta. */
    @Suppress("ThrowsCount")
    private suspend fun <T> llamar(cliente: String, llamada: suspend (DuenioSolicitud) -> T): Resultado<T> {
        if (!activo(cliente)) throw Abortado()
        var deOtraCuenta = false
        val resultado = llamarBff {
            try {
                llamada(DuenioSolicitud(cliente))
            } catch (otra: SolicitudDeOtraCuenta) {
                deOtraCuenta = true
                throw otra
            }
        }
        if (deOtraCuenta || !activo(cliente)) throw Abortado()
        return resultado
    }

    /**
     * Lee sin publicar; un ilegible queda como tarjeta. null también si ya no existe (se terminó o se
     * descartó). Se llama con el [mutex] tomado.
     */
    private suspend fun leerOculto(cliente: String, id: String): Borrador? = try {
        withContext(io) { if (almacen.existe(cliente, id)) almacen.leer(cliente, id) else null }
    } catch (ilegible: BorradorIlegible) {
        if (cliente == duenio) reemplazar(Tarjeta.Ilegible(id, ilegible.permanente))
        null
    }

    /**
     * Persiste antes de seguir: cada transición queda en disco para retomarse tras la muerte del proceso.
     * Si no se puede escribir, se corta el paso y el borrador queda como estaba en disco; se reintenta.
     */
    private suspend fun guardarYPublicar(borrador: Borrador) {
        try {
            withContext(io) { almacen.actualizar(borrador) }
        } catch (error: IOException) {
            throw FalloAlmacen(error)
        }
        publicar(borrador)
    }

    /** Solo se muestra lo del dueño activo: un trabajo de otra cuenta que termina tarde no publica nada. */
    private fun publicar(borrador: Borrador) {
        if (borrador.duenio != duenio) return
        reemplazar(Tarjeta.DeBorrador(borrador, enCurso = enCurso == borrador.id, requiereIngreso = requiereIngreso))
    }

    /** Una tarjeta por borrador; la de «Enviado» de un borrador ya terminado no se toca. */
    private fun reemplazar(tarjeta: Tarjeta) = estado.update { lista ->
        (lista.filterNot { it.id == tarjeta.id && it !is Tarjeta.Enviada } + tarjeta).ordenadas()
    }

    /** Se llama con el [mutex] tomado. */
    private suspend fun refrescar(cliente: String): List<EntradaBorrador> {
        val (entradas, enviados) = withContext(io) { almacen.listar(cliente) to almacen.listarEnviados(cliente) }
        if (duenio != cliente) return emptyList()
        estado.value = entradas.map { entrada ->
            when (entrada) {
                is EntradaBorrador.Legible ->
                    Tarjeta.DeBorrador(entrada.borrador, enCurso == entrada.id, requiereIngreso)
                is EntradaBorrador.Ilegible -> Tarjeta.Ilegible(entrada.id, entrada.permanente)
            }
        } + enviados.map(Tarjeta::Enviada)
        return entradas
    }

    /** La sesión ya no es del dueño: se deja de procesar. */
    private class Abortado : Exception()

    /** No se pudo escribir una transición: el borrador sigue como estaba en disco y se reintenta. */
    private class FalloAlmacen(causa: Throwable) : Exception(causa)

    private enum class Senal { AHORA, TRAS_ESPERA }

    private companion object {
        const val HTTP_NO_AUTENTICADO = 401
        const val HTTP_CONFLICTO = 409
    }
}

/** Pendientes en orden de creación, después los ilegibles y al final los enviados. */
private fun List<Tarjeta>.ordenadas(): List<Tarjeta> = sortedWith(
    compareBy<Tarjeta>(
        {
            when (it) {
                is Tarjeta.DeBorrador -> 0
                is Tarjeta.Ilegible -> 1
                is Tarjeta.Enviada -> 2
            }
        },
        {
            when (it) {
                is Tarjeta.DeBorrador -> it.borrador.creadoEn
                is Tarjeta.Ilegible -> it.id
                is Tarjeta.Enviada -> it.registro.enviadoEn
            }
        },
    ),
)

private fun Borrador.reportadoComo(siniestro: Siniestro) = copy(
    estado = EstadoBorrador.REPORTADO,
    idSiniestro = siniestro.id,
    referencia = siniestro.referencia,
    rechazo = null,
    referenciaEnUso = null,
)

/** Lo que el bucle vuelve a intentar sin que el cliente haga nada. */
private fun Borrador.reintentoAutomatico(): Boolean = when (estado) {
    EstadoBorrador.ESPERANDO_CONEXION, EstadoBorrador.ENVIANDO, EstadoBorrador.POR_CONCILIAR -> true
    EstadoBorrador.REPORTADO, EstadoBorrador.SUBIENDO_EVIDENCIA -> adjuntosPendientes.isNotEmpty()
    EstadoBorrador.REQUIERE_ATENCION, EstadoBorrador.ENVIADO, EstadoBorrador.ILEGIBLE -> false
}

/**
 * La evidencia se descifra en flujo mientras se escribe en el socket: no queda copia en claro en disco.
 * Si OkHttp reintenta, se vuelve a abrir.
 */
private class CuerpoDescifrado(
    tipo: String,
    private val tamano: Long,
    private val abrir: () -> java.io.InputStream,
) : RequestBody() {
    private val tipoContenido = tipo.toMediaType()

    override fun contentType() = tipoContenido

    override fun contentLength() = tamano

    override fun writeTo(sink: BufferedSink) {
        abrir().source().use { sink.writeAll(it) }
    }
}

/**
 * Hay un reporte que quizá ya quedó registrado (incierto, enviando o por conciliar) o uno que no se pudo
 * abrir por un fallo pasajero y aún no se sabe qué es: Inicio no ofrece uno nuevo (criterio 8).
 */
fun List<Tarjeta>.bloqueanReporteNuevo(): Boolean = any {
    (it is Tarjeta.DeBorrador && it.borrador.registroIncierto) || (it is Tarjeta.Ilegible && !it.permanente)
}
