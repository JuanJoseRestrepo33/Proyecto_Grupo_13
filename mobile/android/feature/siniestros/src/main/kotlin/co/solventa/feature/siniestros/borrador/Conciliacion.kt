package co.solventa.feature.siniestros.borrador

import co.solventa.core.network.Resultado
import co.solventa.core.network.Siniestro
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Tolerancia corta para la diferencia entre el reloj del teléfono y el del BFF. Se busca desde la creación
 * del borrador: un margen amplio podría tomar un reporte anterior con los mismos datos y subirle la
 * evidencia de este. Con un reloj muy adelantado no se encuentra y el borrador pide revisión.
 */
private val MARGEN_RELOJ: Duration = Duration.ofMinutes(5)

/**
 * El siniestro que registró un intento anterior del [borrador], si está en [siniestros]: misma póliza,
 * tipo, fecha, descripción y monto, `canal: movil` y reportado desde la creación del borrador.
 */
fun buscarRegistrado(borrador: Borrador, siniestros: List<Siniestro>): Siniestro? {
    val solicitud = borrador.solicitud
    val creado = instante(borrador.creadoEn) ?: Instant.EPOCH
    val iguales = siniestros.filter { siniestro ->
        siniestro.canal == "movil" &&
            siniestro.numeroPoliza == solicitud.numeroPoliza &&
            siniestro.tipoEvento == solicitud.tipoEvento &&
            siniestro.ocurridoEl == solicitud.ocurridoEl &&
            siniestro.descripcion.trim() == solicitud.descripcion.trim() &&
            siniestro.montoEstimado?.valor == solicitud.montoEstimado?.valor
    }.mapNotNull { siniestro -> instante(siniestro.reportadoEn)?.let { siniestro to it } }
    // Primero, el más antiguo desde la creación: el primero que pudo registrar esa clave.
    val despues = iguales.filter { (_, reportado) -> !reportado.isBefore(creado) }.minByOrNull { it.second }
    // Solo si no hay ninguno, el más cercano dentro de la tolerancia del reloj: nunca uno anterior lejano.
    val antes = iguales
        .filter { (_, reportado) -> reportado.isBefore(creado) && !reportado.isBefore(creado.minus(MARGEN_RELOJ)) }
        .maxByOrNull { it.second }
    return (despues ?: antes)?.first
}

private fun instante(iso: String): Instant? = try {
    Instant.parse(iso)
} catch (ignored: DateTimeParseException) {
    runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrNull()
}

/** Cómo terminó un `POST /siniestros` del borrador. */
internal sealed interface RespuestaReporte {
    data class Registrado(val siniestro: Siniestro) : RespuestaReporte

    /** Sin red, tiempo agotado o error sin código del contrato: quizá llegó. */
    data object Incierta : RespuestaReporte

    /** 401: la sesión no se pudo renovar; el borrador espera al ingreso. */
    data object SinSesion : RespuestaReporte

    /** La clave ya cubre un reporte con otros datos: no se rota, se concilia. */
    data class ClaveEnUso(val referencia: String?) : RespuestaReporte

    /** 404, 409 de vigencia o 422: el núcleo validó antes de la idempotencia. */
    data class Rechazada(val rechazo: RechazoBorrador) : RespuestaReporte
}

private const val HTTP_NO_AUTENTICADO = 401
private const val HTTP_TIEMPO_AGOTADO = 408
private const val HTTP_DEMASIADAS = 429
private val HTTP_CLIENTE = 400..499

internal fun clasificarReporte(resultado: Resultado<Siniestro>): RespuestaReporte = when (resultado) {
    is Resultado.Exito -> RespuestaReporte.Registrado(resultado.valor)
    Resultado.SinConexion -> RespuestaReporte.Incierta
    is Resultado.Falla -> when {
        resultado.http == HTTP_NO_AUTENTICADO -> RespuestaReporte.SinSesion
        resultado.codigo == "clave_idempotencia_en_uso" -> RespuestaReporte.ClaveEnUso(resultado.error?.referencia)
        // Un 4xx con código del contrato es definitivo; 408, 429, 5xx o un cuerpo ilegible, no.
        resultado.codigo != null && resultado.http in HTTP_CLIENTE &&
            resultado.http != HTTP_TIEMPO_AGOTADO && resultado.http != HTTP_DEMASIADAS -> RespuestaReporte.Rechazada(
            RechazoBorrador(
                http = resultado.http,
                codigo = resultado.codigo,
                campos = resultado.error?.campos.orEmpty(),
                vigenteDesde = resultado.error?.vigenteDesde,
                vigenteHasta = resultado.error?.vigenteHasta,
            ),
        )
        else -> RespuestaReporte.Incierta
    }
}
