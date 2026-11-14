package co.solventa.feature.siniestros.seguimiento

import co.solventa.core.network.EstadoSiniestro
import co.solventa.core.network.Requerimiento
import co.solventa.core.network.SiguientePaso
import co.solventa.core.network.SiniestroDetalle

// Reglas del seguimiento (WEB-F06) que no dependen de la interfaz: se prueban sin Compose.

/** Lo que el detalle le dice al cliente que sigue. */
sealed interface PasoSeguimiento {
    /** El caso terminó rechazado; [motivo] es el del último evento, si lo trae. */
    data class Rechazado(val motivo: String?) : PasoSeguimiento

    data object Pagado : PasoSeguimiento

    data object ResponderRequerimientos : PasoSeguimiento

    data object AdjuntarEvidencia : PasoSeguimiento

    data object EsperarEvaluacion : PasoSeguimiento
}

/**
 * El contrato manda `esperar_evaluacion` también en los casos cerrados: el siguiente paso se decide
 * con el estado y `siguiente_paso` juntos, en este orden.
 */
fun siguientePaso(detalle: SiniestroDetalle): PasoSeguimiento = when {
    detalle.estado == EstadoSiniestro.RECHAZADO ->
        PasoSeguimiento.Rechazado(detalle.historial.lastOrNull()?.motivo?.takeIf(String::isNotBlank))
    detalle.estado == EstadoSiniestro.PAGADO -> PasoSeguimiento.Pagado
    detalle.estado == EstadoSiniestro.REQUIERE_INFORMACION &&
        detalle.siguientePaso == SiguientePaso.ADJUNTAR_EVIDENCIA -> PasoSeguimiento.ResponderRequerimientos
    detalle.siguientePaso == SiguientePaso.ADJUNTAR_EVIDENCIA -> PasoSeguimiento.AdjuntarEvidencia
    else -> PasoSeguimiento.EsperarEvaluacion
}

/** Los tres estados que se muestran de un requerimiento. */
enum class EstadoRequerimiento {
    PENDIENTE,
    ATENDIDO,

    /** `pendiente: false` sin `atendido_en`: el caso salió de `requiere_informacion` sin respuesta. */
    CERRADO_SIN_RESPUESTA,
}

val Requerimiento.estadoMostrado: EstadoRequerimiento
    get() = when {
        pendiente -> EstadoRequerimiento.PENDIENTE
        atendidoEn != null -> EstadoRequerimiento.ATENDIDO
        else -> EstadoRequerimiento.CERRADO_SIN_RESPUESTA
    }

/** Solo un requerimiento pendiente se puede responder. */
val Requerimiento.respondible: Boolean
    get() = estadoMostrado == EstadoRequerimiento.PENDIENTE

/** Color de la insignia; siempre va con el texto del estado, nunca solo el color. */
enum class TonoInsignia { AMBAR, VERDE, ROJO }

val EstadoSiniestro.tono: TonoInsignia
    get() = when (this) {
        EstadoSiniestro.REPORTADO, EstadoSiniestro.EN_EVALUACION, EstadoSiniestro.REQUIERE_INFORMACION ->
            TonoInsignia.AMBAR
        EstadoSiniestro.APROBADO, EstadoSiniestro.PAGADO -> TonoInsignia.VERDE
        EstadoSiniestro.RECHAZADO -> TonoInsignia.ROJO
    }

/** Rechazado y pagado terminan el caso: ya no recibe evidencia. */
val EstadoSiniestro.terminaElCaso: Boolean
    get() = this == EstadoSiniestro.RECHAZADO || this == EstadoSiniestro.PAGADO
