package co.solventa.feature.siniestros.evidencia

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import co.solventa.core.network.BffApi
import co.solventa.core.network.Evidencia
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.Resultado
import co.solventa.core.network.llamarBff
import co.solventa.core.network.parteArchivo
import co.solventa.core.network.parteTexto
import co.solventa.feature.siniestros.borrador.AdjuntoBorrador
import co.solventa.feature.siniestros.borrador.AdjuntoNuevo
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

const val LARGO_MAXIMO_LUGAR = 200

@Serializable
enum class EstadoAdjunto { LISTO, NO_CABE, ENVIANDO, ENVIADO, FALLIDO }

/** Por qué no se adjuntó un archivo. [reintentable]: el envío pudo no llegar o el fallo es pasajero. */
@Serializable
enum class FalloCarga(val reintentable: Boolean = false) {
    TAMANO,
    FORMATO,
    VACIO,
    UBICACION,
    FECHA,
    CERRADO,
    NO_ENCONTRADO,
    ARCHIVO_PERDIDO,
    ALMACENAMIENTO(reintentable = true),
    SIN_RED(reintentable = true),
    GENERICO(reintentable = true),
}

@Serializable
data class Adjunto(
    val id: String,
    val ruta: String,
    /** `nombre_original` único y sin datos del cliente: identifica el archivo al reintentar. */
    val nombre: String,
    val formato: FormatoEvidencia?,
    val tamano: Long,
    val tomadaEn: String? = null,
    val latitud: Double? = null,
    val longitud: Double? = null,
    val lugar: String = "",
    val estado: EstadoAdjunto = EstadoAdjunto.LISTO,
    val fallo: FalloCarga? = null,
) {
    val conCoordenadas: Boolean
        get() = latitud != null && longitud != null

    val reintentable: Boolean
        get() = estado == EstadoAdjunto.FALLIDO && fallo?.reintentable == true
}

data class EstadoEvidencia(
    val adjuntos: List<Adjunto> = emptyList(),
    val preparando: Boolean = false,
    /** Avance de la compresión, de 0 a 1; null si no se conoce. */
    val progreso: Float? = null,
    val rechazo: MotivoRechazo? = null,
    val permisoCamara: EstadoPermiso = EstadoPermiso.SIN_PEDIR,
    val permisoUbicacion: EstadoPermiso = EstadoPermiso.SIN_PEDIR,
    /** Se muestra para qué sirven los permisos antes de pedirlos. */
    val explicando: Boolean = false,
    /** El dispositivo no pudo abrir la cámara: quedan la galería y los documentos. */
    val camaraNoDisponible: Boolean = false,
    val enviando: Boolean = false,
    /** Desde la confirmación, el cliente adjunta más al mismo siniestro con el mismo paso. */
    val agregando: Boolean = false,
    /** Evidencia del siniestro según el BFF, cargada desde cualquier canal. */
    val delSiniestro: List<Evidencia> = emptyList(),
    val errorConsulta: Boolean = false,
    val sesionVencida: Boolean = false,
) {
    /** Lo que aún se puede quitar: lo que no se ha enviado. */
    val porEnviar: List<Adjunto>
        get() = adjuntos.filter { it.estado == EstadoAdjunto.LISTO || it.estado == EstadoAdjunto.NO_CABE }

    /** Lo que irá al BFF. */
    val listos: List<Adjunto>
        get() = adjuntos.filter { it.estado == EstadoAdjunto.LISTO }

    val puedeReintentar: Boolean
        get() = !enviando && adjuntos.any { it.reintentable }

    val camaraLista: Boolean
        get() = permisoCamara == EstadoPermiso.CONCEDIDO && permisoUbicacion != EstadoPermiso.SIN_PEDIR
}

/** Cómo terminó la respuesta a un requerimiento (WEB-F06): la pantalla decide el aviso con esto. */
sealed interface RespuestaRequerimiento {
    /** La evidencia llegó (con `201` o hallada al conciliar): el requerimiento quedó atendido. */
    data object Enviada : RespuestaRequerimiento

    /** El BFF rechazó la carga; el adjunto queda fallido con su motivo. */
    data class Rechazada(val falla: Resultado.Falla) : RespuestaRequerimiento

    /** Sin red o sin respuesta: quizá llegó. El reintento concilia antes de reenviar. */
    data object SinRespuesta : RespuestaRequerimiento

    /** No se pudo conciliar una carga incierta: no se reenvió nada para no duplicarla. */
    data object SinConciliar : RespuestaRequerimiento

    /** No hay archivo listo (o el sistema lo borró de la caché): hay que elegir otro. */
    data object SinArchivo : RespuestaRequerimiento
}

/**
 * Evidencia del reporte (MOB-F05): lo adjunto, su carga una a una después del reporte y los
 * reintentos. El adjunto se guarda en [borrador] y su archivo en la caché: sobreviven a la rotación
 * y al segundo plano. Un fallo de evidencia nunca deshace el reporte.
 *
 * @param unico respuesta a un requerimiento (WEB-F06): un archivo por respuesta, así que elegir otro
 * reemplaza el que no se ha enviado. Se envía con [responder], no con [subir].
 */
@Suppress("TooManyFunctions")
class GestorEvidencia(
    private val api: BffApi,
    private val fuentes: FuentesEvidencia,
    private val alcance: CoroutineScope,
    private val borrador: SavedStateHandle,
    private val unico: Boolean = false,
) {
    private val almacen = fuentes.almacen
    private var preparacion: Job? = null
    private var actual by mutableStateOf(
        EstadoEvidencia(adjuntos = restaurar(borrador), agregando = borrador[CLAVE_AGREGANDO] ?: false),
    )

    var estado: EstadoEvidencia
        get() = actual
        private set(valor) {
            actual = valor
            borrador[CLAVE_ADJUNTOS] = NetworkClient.json.encodeToString(SERIALIZADOR, valor.adjuntos)
            borrador[CLAVE_AGREGANDO] = valor.agregando
        }

    init {
        almacen.conservarSolo(estado.adjuntos.map { it.ruta }.filter(String::isNotEmpty).toSet())
    }

    fun permisos(camara: EstadoPermiso, ubicacion: EstadoPermiso) {
        estado = estado.copy(permisoCamara = camara, permisoUbicacion = ubicacion)
    }

    fun explicar(visible: Boolean) {
        estado = estado.copy(explicando = visible)
    }

    fun camaraFallo() {
        estado = estado.copy(camaraNoDisponible = true)
    }

    /** Foto de la cámara: lleva la hora y, con permiso, la ubicación leída una vez. */
    fun fotoTomada(uri: String) {
        val tomadaEn = FECHA_CON_ZONA.format(fuentes.ahora().truncatedTo(ChronoUnit.SECONDS))
        preparar(uri, tomadaEn, conUbicacion = estado.permisoUbicacion == EstadoPermiso.CONCEDIDO)
    }

    /** Archivo de la galería o de los documentos: la ubicación actual no sería la del evento. */
    fun elegido(uri: String) = preparar(uri, tomadaEn = null, conUbicacion = false)

    private fun preparar(uri: String, tomadaEn: String?, conUbicacion: Boolean) {
        estado = estado.copy(preparando = true, progreso = null, rechazo = null)
        preparacion = alcance.launch {
            val lectura = if (conUbicacion) async { fuentes.ubicacion.leer() } else null
            val preparado = almacen.preparar(uri) { avance -> actual = actual.copy(progreso = avance) }
            val coordenadas = lectura?.await()
            // Cancelada mientras se preparaba: el resultado no entra en la lista (cancelarPreparacion lo borra).
            ensureActive()
            val adjunto = when (preparado) {
                is Preparado.Listo -> Adjunto(
                    id = UUID.randomUUID().toString(),
                    ruta = preparado.ruta,
                    nombre = preparado.nombre,
                    formato = preparado.formato,
                    tamano = preparado.tamano,
                    tomadaEn = tomadaEn,
                    latitud = coordenadas?.latitud,
                    longitud = coordenadas?.longitud,
                )
                is Preparado.Rechazado -> noCabe(preparado)
            }
            val rechazo = (preparado as? Preparado.Rechazado)?.motivo
            // Con un archivo por respuesta, el nuevo reemplaza al anterior, que aún no salió.
            val reemplazados = if (unico && adjunto != null) estado.adjuntos.filter(::reemplazable) else emptyList()
            reemplazados.filter { it.ruta.isNotEmpty() }.forEach { almacen.borrar(it.ruta) }
            estado = estado.copy(
                preparando = false,
                progreso = null,
                rechazo = rechazo,
                adjuntos = estado.adjuntos - reemplazados.toSet() + listOfNotNull(adjunto),
            )
        }
    }

    /** Lo que aún no está en el BFF. Uno con envío incierto no se reemplaza: quizá ya llegó. */
    private fun reemplazable(adjunto: Adjunto) = adjunto.estado != EstadoAdjunto.ENVIADO &&
        adjunto.estado != EstadoAdjunto.ENVIANDO &&
        !(adjunto.estado == EstadoAdjunto.FALLIDO && adjunto.fallo in INCIERTOS)

    /** Lo que no cupo queda en la lista para explicarlo y ofrecer quitarlo; nunca se envía. */
    private fun noCabe(rechazado: Preparado.Rechazado) = rechazado.takeIf { it.motivo == MotivoRechazo.NO_CABE }?.let {
        Adjunto(
            id = UUID.randomUUID().toString(),
            ruta = "",
            nombre = "",
            formato = it.formato,
            tamano = it.tamano,
            estado = EstadoAdjunto.NO_CABE,
        )
    }

    fun quitar(id: String) {
        val adjunto = estado.adjuntos.firstOrNull { it.id == id } ?: return
        if (adjunto.ruta.isNotEmpty()) almacen.borrar(adjunto.ruta)
        estado = estado.copy(adjuntos = estado.adjuntos - adjunto, rechazo = null)
    }

    fun cambiarLugar(id: String, lugar: String) = cambiar(id) { it.copy(lugar = lugar.take(LARGO_MAXIMO_LUGAR)) }

    /** Al avanzar, lo que no cupo sale de la lista: ya se explicó y no bloquea el reporte (P20). */
    fun descartarNoCaben() {
        estado = estado.copy(adjuntos = estado.adjuntos.filter { it.estado != EstadoAdjunto.NO_CABE }, rechazo = null)
    }

    fun agregarMas() {
        estado = estado.copy(agregando = true, rechazo = null)
    }

    /**
     * Detiene la preparación en curso. Lo que alcance a escribir después en la carpeta no pertenece
     * a ningún adjunto, así que se borra al terminar la tarea.
     */
    private fun cancelarPreparacion() {
        val tarea = preparacion ?: return
        preparacion = null
        tarea.cancel()
        tarea.invokeOnCompletion { almacen.conservarSolo(rutasVigentes()) }
        estado = estado.copy(preparando = false, progreso = null)
    }

    private fun rutasVigentes() = actual.adjuntos.map { it.ruta }.filter(String::isNotEmpty).toSet()

    fun cancelarAgregar() {
        cancelarPreparacion()
        estado.listos.forEach { almacen.borrar(it.ruta) }
        estado = estado.copy(
            agregando = false,
            rechazo = null,
            adjuntos = estado.adjuntos - estado.porEnviar.toSet(),
        )
    }

    fun sesionAtendida() {
        estado = estado.copy(sesionVencida = false)
    }

    /** Sube cada archivo listo, uno a uno, como la web, y actualiza la evidencia del siniestro. */
    fun subir(idSiniestro: String) {
        if (estado.enviando) return
        descartarNoCaben()
        estado = estado.copy(enviando = true, agregando = false)
        alcance.launch {
            subirListos(idSiniestro)
            consultarAhora(idSiniestro)
            estado = estado.copy(enviando = false)
        }
    }

    /**
     * Sin clave de idempotencia, reenviar lo que sí llegó lo duplicaría: primero se consulta qué tiene
     * el siniestro y solo se reenvía lo que no está. Si no se puede consultar, no se reenvía nada.
     */
    fun reintentar(idSiniestro: String) {
        if (!estado.puedeReintentar) return
        estado = estado.copy(enviando = true)
        alcance.launch {
            val consulta = llamarBff { api.listarEvidencias(idSiniestro) }
            if (consulta !is Resultado.Exito) {
                estado = estado.copy(enviando = false, errorConsulta = true)
                return@launch
            }
            val llegaron = consulta.valor.mapNotNull { it.nombreOriginal }.toSet()
            estado.adjuntos.filter { it.reintentable && it.nombre in llegaron }.forEach { almacen.borrar(it.ruta) }
            estado = estado.copy(
                delSiniestro = consulta.valor,
                errorConsulta = false,
                adjuntos = estado.adjuntos.map { adjunto ->
                    when {
                        !adjunto.reintentable -> adjunto
                        adjunto.nombre in llegaron -> adjunto.copy(estado = EstadoAdjunto.ENVIADO, fallo = null)
                        else -> adjunto.copy(estado = EstadoAdjunto.LISTO, fallo = null)
                    }
                },
            )
            subirListos(idSiniestro)
            consultarAhora(idSiniestro)
            estado = estado.copy(enviando = false)
        }
    }

    /** Tras restaurar la confirmación: sube lo que no alcanzó a salir, salvo lo que se está agregando. */
    fun reanudar(idSiniestro: String) {
        if (estado.listos.isNotEmpty() && !estado.agregando) subir(idSiniestro) else consultar(idSiniestro)
    }

    fun consultar(idSiniestro: String) {
        alcance.launch { consultarAhora(idSiniestro) }
    }

    /**
     * Quita de la lista lo listo cuyo archivo ya no está en la caché (el sistema puede liberarla).
     * @return cuántos se quitaron: el asistente lo avisa antes de guardar, para no perderlos en silencio.
     */
    fun quitarPerdidos(): Int {
        val perdidos = estado.listos.filter { almacen.archivo(it.ruta) == null }
        if (perdidos.isNotEmpty()) estado = estado.copy(adjuntos = estado.adjuntos - perdidos.toSet())
        return perdidos.size
    }

    /**
     * Lo listo, con su manifiesto completo, para el borrador cifrado (MOB-F05-02). Lo que ya no está en
     * la caché no se puede guardar y queda fuera.
     */
    fun paraBorrador(): List<AdjuntoNuevo> = estado.listos.mapNotNull { adjunto ->
        val archivo = almacen.archivo(adjunto.ruta) ?: return@mapNotNull null
        val formato = adjunto.formato ?: return@mapNotNull null
        AdjuntoNuevo(
            AdjuntoBorrador(
                id = adjunto.id,
                nombre = adjunto.nombre,
                formato = formato,
                tamano = archivo.length(),
                tomadaEn = adjunto.tomadaEn,
                latitud = adjunto.latitud,
                longitud = adjunto.longitud,
                lugar = adjunto.lugar.trim(),
            ),
            archivo,
        )
    }

    /** El borrador ya tiene los archivos (y los borró de la caché): el asistente los suelta sin borrarlos. */
    fun transferidos() {
        cancelarPreparacion()
        estado = estado.copy(adjuntos = emptyList(), rechazo = null)
    }

    /** Sale del reporte: ningún archivo temporal se queda en el dispositivo. */
    fun descartar() {
        cancelarPreparacion()
        estado.adjuntos.filter { it.ruta.isNotEmpty() }.forEach { almacen.borrar(it.ruta) }
        estado = estado.copy(adjuntos = emptyList())
    }

    /**
     * Responde el requerimiento [idRequerimiento] con el único archivo de la lista (WEB-F06): una sola
     * carga con `id_requerimiento`, porque la primera lo deja atendido y otra recibiría 409
     * `requerimiento_ya_atendido`. Si un envío anterior quedó sin respuesta, primero concilia por
     * `nombre_original`: si la evidencia llegó, no se vuelve a cargar.
     */
    suspend fun responder(idSiniestro: String, idRequerimiento: String): RespuestaRequerimiento {
        val adjunto = estado.adjuntos.lastOrNull { it.estado == EstadoAdjunto.LISTO || it.reintentable }
        return when {
            estado.enviando -> RespuestaRequerimiento.SinRespuesta
            adjunto == null -> RespuestaRequerimiento.SinArchivo
            else -> {
                estado = estado.copy(enviando = true, rechazo = null)
                val incierto = adjunto.estado == EstadoAdjunto.FALLIDO && adjunto.fallo in INCIERTOS
                val respuesta = (if (incierto) conciliar(idSiniestro, adjunto) else null)
                    ?: cargarRespuesta(idSiniestro, idRequerimiento, adjunto)
                estado = estado.copy(enviando = false)
                respuesta
            }
        }
    }

    /**
     * Busca la carga incierta por `nombre_original`. @return [RespuestaRequerimiento.Enviada] si llegó,
     * [RespuestaRequerimiento.Rechazada] si el BFF rechazó la consulta (401 o 404 llegan así a su flujo),
     * [RespuestaRequerimiento.SinConciliar] si no hubo red, o null si no llegó y hay que cargarla.
     */
    private suspend fun conciliar(idSiniestro: String, adjunto: Adjunto): RespuestaRequerimiento? {
        val consulta = llamarBff { api.listarEvidencias(idSiniestro) }
        val evidencias = (consulta as? Resultado.Exito)?.valor
        val llego = evidencias?.any { it.nombreOriginal == adjunto.nombre }
        estado = estado.copy(delSiniestro = evidencias ?: estado.delSiniestro, errorConsulta = evidencias == null)
        if (llego == true) {
            almacen.borrar(adjunto.ruta)
            cambiar(adjunto.id) { it.copy(estado = EstadoAdjunto.ENVIADO, fallo = null) }
        }
        return when {
            consulta is Resultado.Falla -> RespuestaRequerimiento.Rechazada(consulta)
            llego == null -> RespuestaRequerimiento.SinConciliar
            llego -> RespuestaRequerimiento.Enviada
            else -> null
        }
    }

    private suspend fun cargarRespuesta(
        idSiniestro: String,
        idRequerimiento: String,
        adjunto: Adjunto,
    ): RespuestaRequerimiento = when (val resultado = subirUno(idSiniestro, adjunto, idRequerimiento)) {
        null -> RespuestaRequerimiento.SinArchivo
        is Resultado.Exito -> RespuestaRequerimiento.Enviada
        is Resultado.Falla -> RespuestaRequerimiento.Rechazada(resultado)
        Resultado.SinConexion -> RespuestaRequerimiento.SinRespuesta
    }

    /** Solo actualiza la lista del siniestro: `enviando` es de la tarea que controla los POST. */
    private suspend fun consultarAhora(idSiniestro: String) {
        val consulta = llamarBff { api.listarEvidencias(idSiniestro) }
        estado = when (consulta) {
            is Resultado.Exito -> estado.copy(delSiniestro = consulta.valor, errorConsulta = false)
            else -> estado.copy(errorConsulta = true)
        }
    }

    private suspend fun subirListos(idSiniestro: String) {
        estado = estado.copy(enviando = true)
        estado.listos.forEach { subirUno(idSiniestro, it) }
    }

    /** @return null si el archivo ya no está en la caché. */
    private suspend fun subirUno(
        idSiniestro: String,
        adjunto: Adjunto,
        idRequerimiento: String? = null,
    ): Resultado<Evidencia>? {
        val archivo = almacen.archivo(adjunto.ruta)
        val formato = adjunto.formato
        if (archivo == null || formato == null) {
            fallar(adjunto.id, FalloCarga.ARCHIVO_PERDIDO)
            return null
        }
        cambiar(adjunto.id) { it.copy(estado = EstadoAdjunto.ENVIANDO, fallo = null) }
        val lugar = adjunto.lugar.trim().takeIf { it.isNotEmpty() && !adjunto.conCoordenadas }
        val resultado = llamarBff {
            api.adjuntarEvidencia(
                idSiniestro,
                parteArchivo(archivo, adjunto.nombre, formato.tipoContenido),
                parteTexto(adjunto.tomadaEn),
                parteTexto(adjunto.latitud?.toString()),
                parteTexto(adjunto.longitud?.toString()),
                parteTexto(lugar),
                parteTexto(idRequerimiento),
            )
        }
        when (resultado) {
            is Resultado.Exito -> {
                almacen.borrar(adjunto.ruta)
                cambiar(adjunto.id) { it.copy(estado = EstadoAdjunto.ENVIADO, fallo = null) }
            }
            is Resultado.Falla -> {
                if (resultado.http == HTTP_NO_AUTENTICADO) estado = estado.copy(sesionVencida = true)
                fallar(adjunto.id, falloDe(resultado))
            }
            Resultado.SinConexion -> fallar(adjunto.id, FalloCarga.SIN_RED)
        }
        return resultado
    }

    private fun fallar(id: String, fallo: FalloCarga) =
        cambiar(id) { it.copy(estado = EstadoAdjunto.FALLIDO, fallo = fallo) }

    private fun cambiar(id: String, cambio: (Adjunto) -> Adjunto) {
        estado = estado.copy(adjuntos = estado.adjuntos.map { if (it.id == id) cambio(it) else it })
    }
}

private const val CLAVE_ADJUNTOS = "evidencia"
private const val CLAVE_AGREGANDO = "evidencia_agregando"
private const val HTTP_NO_AUTENTICADO = 401
private const val HTTP_NO_ENCONTRADO = 404
private val SERIALIZADOR = ListSerializer(Adjunto.serializer())

/** Fallos tras los que la carga quizá llegó: antes de reenviar se concilia por `nombre_original`. */
private val INCIERTOS = setOf(FalloCarga.SIN_RED, FalloCarga.GENERICO)

/** Con segundos y zona siempre: `OffsetDateTime.toString` los omite si son cero. */
private val FECHA_CON_ZONA: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

/** Traduce el rechazo del BFF; lo desconocido se trata como incierto y se puede reintentar. */
internal fun falloDe(falla: Resultado.Falla): FalloCarga = when {
    falla.codigo == "archivo_demasiado_grande" -> FalloCarga.TAMANO
    falla.codigo == "formato_no_admitido" -> FalloCarga.FORMATO
    falla.codigo == "archivo_vacio" -> FalloCarga.VACIO
    falla.codigo == "ubicacion_invalida" -> FalloCarga.UBICACION
    falla.codigo == "fecha_de_captura_invalida" -> FalloCarga.FECHA
    falla.codigo == "siniestro_cerrado" -> FalloCarga.CERRADO
    falla.codigo == "almacenamiento_no_disponible" -> FalloCarga.ALMACENAMIENTO
    falla.http == HTTP_NO_ENCONTRADO -> FalloCarga.NO_ENCONTRADO
    else -> FalloCarga.GENERICO
}

/** Lo que estaba saliendo cuando murió el proceso quizá llegó: queda para reintentar, que primero consulta. */
private fun restaurar(borrador: SavedStateHandle): List<Adjunto> =
    borrador.get<String>(CLAVE_ADJUNTOS)
        ?.let { json -> runCatching { NetworkClient.json.decodeFromString(SERIALIZADOR, json) }.getOrNull() }
        .orEmpty()
        .map { adjunto ->
            if (adjunto.estado == EstadoAdjunto.ENVIANDO) {
                adjunto.copy(estado = EstadoAdjunto.FALLIDO, fallo = FalloCarga.SIN_RED)
            } else {
                adjunto
            }
        }
