package co.solventa.feature.siniestros.seguimiento

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.core.network.BffApi
import co.solventa.core.network.Resultado
import co.solventa.core.network.SiniestroDetalle
import co.solventa.core.network.llamarBff
import co.solventa.feature.siniestros.evidencia.FuentesEvidencia
import co.solventa.feature.siniestros.evidencia.GestorEvidencia
import co.solventa.feature.siniestros.evidencia.RespuestaRequerimiento
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Aviso breve (snackbar) tras responder un requerimiento; el caso ya se volvió a consultar. */
enum class AvisoSeguimiento {
    RESPUESTA_ENVIADA,

    /** 409 `requerimiento_ya_atendido`. */
    YA_ATENDIDO,

    /** 404 `requerimiento_no_encontrado`. */
    REQUERIMIENTO_NO_ENCONTRADO,

    /** 409 `siniestro_cerrado`: el caso ya no recibe evidencia. */
    CASO_CERRADO,
}

data class EstadoDetalle(
    val detalle: SiniestroDetalle? = null,
    val cargando: Boolean = true,
    /** La consulta falló; si ya había un detalle, se sigue mostrando con el aviso y «Reintentar». */
    val error: FalloConsulta? = null,
    /** 404: no existe o es de otro cliente; se tratan igual. */
    val noEncontrado: Boolean = false,
    val sesionVencida: Boolean = false,
    /** Requerimiento que se está respondiendo: la hoja inferior está abierta. */
    val respondiendo: String? = null,
    val aviso: AvisoSeguimiento? = null,
    /** La respuesta llegó, pero no se pudo consultar el caso: «Reintentar» solo consulta. */
    val respuestaSinConsulta: Boolean = false,
) {
    val requerimientoRespondiendo
        get() = detalle?.requerimientos?.firstOrNull { it.id == respondiendo }
}

/**
 * Detalle del caso (WEB-F06): encabezado, historial, resumen, siguiente paso y requerimientos. Recibe
 * el `id` por la ruta (`home/siniestros/detalle/{id}`), que queda en [estadoGuardado]: tras la muerte del
 * proceso vuelve al mismo caso y lo consulta otra vez. Del siniestro no se guarda nada en disco.
 *
 * Responder un requerimiento es una sola carga con `id_requerimiento` ([GestorEvidencia.responder]);
 * después se vuelve a consultar el detalle, que trae el requerimiento atendido con su fecha.
 */
@Suppress("TooManyFunctions")
class DetalleSiniestroViewModel(
    private val api: BffApi,
    private val estadoGuardado: SavedStateHandle,
    fuentes: FuentesEvidencia = FuentesEvidencia(),
) : ViewModel() {
    val id: String = requireNotNull(estadoGuardado.get<String>(ARGUMENTO_ID)) { "Falta el id del siniestro" }

    private var actual by mutableStateOf(EstadoDetalle(respondiendo = estadoGuardado[CLAVE_RESPONDIENDO]))

    var estado: EstadoDetalle
        get() = actual
        private set(valor) {
            actual = valor
            estadoGuardado[CLAVE_RESPONDIENDO] = valor.respondiendo
        }

    /** El archivo de la respuesta: uno solo, con las reglas y la compresión del paso de evidencia. */
    val evidencia = GestorEvidencia(api, fuentes, viewModelScope, estadoGuardado, unico = true)

    private var consulta: Job? = null

    init {
        consultar()
    }

    /** Primera carga, «Actualizar», deslizar hacia abajo y «Reintentar». */
    fun actualizar() = consultar()

    private fun consultar(aviso: AvisoSeguimiento? = null, trasRespuesta: Boolean = false) {
        if (consulta?.isActive == true && !trasRespuesta) return
        consulta?.cancel()
        estado = estado.copy(cargando = true, error = null)
        consulta = viewModelScope.launch {
            val resultado = llamarBff { api.obtenerSiniestro(id) }
            estado = when (resultado) {
                is Resultado.Exito -> estado.copy(
                    detalle = resultado.valor,
                    cargando = false,
                    noEncontrado = false,
                    respuestaSinConsulta = false,
                    aviso = aviso ?: estado.aviso,
                    // Si el requerimiento ya no es respondible (lo atendió otro canal), la hoja se cierra.
                    respondiendo = estado.respondiendo?.takeIf { abierto ->
                        resultado.valor.requerimientos.any { it.id == abierto && it.respondible }
                    },
                )
                is Resultado.Falla -> conFalla(resultado, trasRespuesta)
                Resultado.SinConexion -> estado.copy(
                    cargando = false,
                    error = FalloConsulta.SIN_RED,
                    respuestaSinConsulta = trasRespuesta || estado.respuestaSinConsulta,
                )
            }
            if (estado.respondiendo == null && !evidencia.estado.enviando) evidencia.descartar()
        }
    }

    private fun conFalla(falla: Resultado.Falla, trasRespuesta: Boolean): EstadoDetalle = when {
        falla.http == HTTP_NO_AUTENTICADO -> estado.copy(cargando = false, sesionVencida = true)
        falla.http == HTTP_NO_ENCONTRADO -> estado.copy(cargando = false, noEncontrado = true, respondiendo = null)
        else -> estado.copy(
            cargando = false,
            error = FalloConsulta.GENERICO,
            respuestaSinConsulta = trasRespuesta || estado.respuestaSinConsulta,
        )
    }

    /** «Responder» en un requerimiento pendiente: abre la hoja para elegir un archivo. */
    fun responder(idRequerimiento: String) {
        val requerimiento = estado.detalle?.requerimientos?.firstOrNull { it.id == idRequerimiento } ?: return
        if (!requerimiento.respondible || evidencia.estado.enviando) return
        if (estado.respondiendo != idRequerimiento) evidencia.descartar()
        estado = estado.copy(respondiendo = idRequerimiento, aviso = null)
    }

    /** Cierra la hoja sin enviar; mientras se envía no se puede, para no perder el resultado. */
    fun cancelarRespuesta() {
        if (evidencia.estado.enviando) return
        evidencia.descartar()
        estado = estado.copy(respondiendo = null)
    }

    /** Envía el archivo elegido, o lo reintenta (conciliando antes si el envío anterior quedó incierto). */
    fun enviarRespuesta() {
        val idRequerimiento = estado.respondiendo ?: return
        if (evidencia.estado.enviando) return
        viewModelScope.launch {
            when (val respuesta = evidencia.responder(id, idRequerimiento)) {
                RespuestaRequerimiento.Enviada -> cerrarYConsultar(AvisoSeguimiento.RESPUESTA_ENVIADA)
                is RespuestaRequerimiento.Rechazada -> rechazada(respuesta.falla)
                // El adjunto queda en la hoja con su motivo y se puede reintentar sin volver a elegirlo.
                RespuestaRequerimiento.SinRespuesta,
                RespuestaRequerimiento.SinConciliar,
                RespuestaRequerimiento.SinArchivo,
                -> Unit
            }
        }
    }

    /** Decide por `codigo`, no solo por el estado HTTP (tabla de errores de §5). */
    private fun rechazada(falla: Resultado.Falla) {
        when {
            falla.codigo == "requerimiento_ya_atendido" -> cerrarYConsultar(AvisoSeguimiento.YA_ATENDIDO)
            falla.codigo == "requerimiento_no_encontrado" ->
                cerrarYConsultar(AvisoSeguimiento.REQUERIMIENTO_NO_ENCONTRADO)
            falla.codigo == "siniestro_cerrado" -> cerrarYConsultar(AvisoSeguimiento.CASO_CERRADO)
            falla.http == HTTP_NO_ENCONTRADO -> {
                evidencia.descartar()
                estado = estado.copy(noEncontrado = true, respondiendo = null)
            }
            falla.http == HTTP_NO_AUTENTICADO -> {
                evidencia.sesionAtendida()
                estado = estado.copy(sesionVencida = true)
            }
            // 413, 422, 503 y lo demás: el motivo queda en el adjunto, dentro de la hoja.
            else -> Unit
        }
    }

    private fun cerrarYConsultar(aviso: AvisoSeguimiento) {
        evidencia.descartar()
        estado = estado.copy(respondiendo = null)
        consultar(aviso, trasRespuesta = true)
    }

    fun avisoMostrado() {
        estado = estado.copy(aviso = null)
    }

    fun sesionAtendida() {
        estado = estado.copy(sesionVencida = false)
    }

    companion object {
        /** Argumento de la ruta del detalle; la navegación lo deja en el SavedStateHandle. */
        const val ARGUMENTO_ID = "id"
    }
}

private const val CLAVE_RESPONDIENDO = "respondiendo"
private const val HTTP_NO_ENCONTRADO = 404
