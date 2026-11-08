package co.solventa.feature.siniestros.borrador

import co.solventa.core.network.ErrorCampo
import co.solventa.core.network.PolizaResumen
import co.solventa.core.network.SolicitudSiniestro
import co.solventa.feature.siniestros.evidencia.EstadoAdjunto
import co.solventa.feature.siniestros.evidencia.FalloCarga
import co.solventa.feature.siniestros.evidencia.FormatoEvidencia
import kotlinx.serialization.Serializable

/**
 * Estados de un reporte guardado en el teléfono (MOB-F05-02). `ILEGIBLE` no se guarda: es lo que se
 * muestra de un borrador que ya no se puede descifrar.
 */
@Serializable
enum class EstadoBorrador {
    ESPERANDO_CONEXION,
    ENVIANDO,

    /** El BFF registró el siniestro: id y referencia ya están guardados, falta la evidencia. */
    REPORTADO,
    SUBIENDO_EVIDENCIA,
    ENVIADO,

    /** Rechazo definitivo sin intento incierto previo: el cliente corrige (clave nueva) o descarta. */
    REQUIERE_ATENCION,

    /** Un intento anterior pudo registrar el siniestro: se busca en `listarSiniestros` antes de seguir. */
    POR_CONCILIAR,
    ILEGIBLE,
}

/**
 * Manifiesto completo de un archivo del borrador: lo necesario para subirlo sin el asistente. El
 * contenido va cifrado aparte, en `<id>.bin` dentro de la carpeta del borrador.
 */
@Serializable
data class AdjuntoBorrador(
    val id: String,
    /** `nombre_original` estable: identifica el archivo al conciliar con `listarEvidencias`. */
    val nombre: String,
    val formato: FormatoEvidencia,
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

    /** Se reintenta solo, sin que el cliente lo pida: se cortó la red. */
    val reintentoAutomatico: Boolean
        get() = estado == EstadoAdjunto.FALLIDO && fallo == FalloCarga.SIN_RED

    val reintentable: Boolean
        get() = estado == EstadoAdjunto.FALLIDO && fallo?.reintentable == true
}

/** Lo que dijo el BFF al rechazar el reporte: alimenta el aviso de #169 en el paso afectado. */
@Serializable
data class RechazoBorrador(
    val http: Int,
    val codigo: String?,
    val campos: List<ErrorCampo> = emptyList(),
    val vigenteDesde: String? = null,
    val vigenteHasta: String? = null,
)

@Serializable
data class Borrador(
    val id: String,
    /** `cliente.id` de la sesión que lo guardó: otra cuenta no lo ve ni lo envía. */
    val duenio: String,
    val solicitud: SolicitudSiniestro,
    val claveIdempotencia: String,
    val adjuntos: List<AdjuntoBorrador> = emptyList(),
    val estado: EstadoBorrador = EstadoBorrador.ESPERANDO_CONEXION,
    /** Hubo un envío sin respuesta: quizá el siniestro quedó registrado con esta clave. */
    val intentoIncierto: Boolean = false,
    /** Instante ISO de creación: ordena el envío y acota la conciliación. */
    val creadoEn: String,
    val idSiniestro: String? = null,
    val referencia: String? = null,
    val rechazo: RechazoBorrador? = null,
    /** Con `clave_idempotencia_en_uso`, la referencia que informó el BFF, si la trae. */
    val referenciaEnUso: String? = null,
) {
    /** Lo que aún hay que subir, incluido lo que se cortó por falta de red. */
    val adjuntosPendientes: List<AdjuntoBorrador>
        get() = adjuntos.filter {
            it.estado == EstadoAdjunto.LISTO || it.estado == EstadoAdjunto.ENVIANDO || it.reintentoAutomatico
        }

    val reportado: Boolean
        get() = idSiniestro != null

    /** Quizá ya quedó registrado y aún no se sabe: no se ofrece reportar otro (criterio 8). */
    val registroIncierto: Boolean
        get() = !reportado &&
            (intentoIncierto || estado == EstadoBorrador.ENVIANDO || estado == EstadoBorrador.POR_CONCILIAR)
}

/** Lo que queda de un borrador enviado: la tarjeta «Reporte enviado» hasta que el cliente toca «Entendido». */
@Serializable
data class RegistroEnviado(val id: String, val referencia: String, val enviadoEn: String)

/** Última lista de pólizas que cargó bien, para el paso 1 sin conexión. */
@Serializable
data class PolizasGuardadas(val polizas: List<PolizaResumen>, val actualizadasEn: String)
