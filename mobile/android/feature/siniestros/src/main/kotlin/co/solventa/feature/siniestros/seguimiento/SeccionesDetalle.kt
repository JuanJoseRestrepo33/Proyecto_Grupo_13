@file:Suppress("TooManyFunctions")

package co.solventa.feature.siniestros.seguimiento

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.core.network.EventoSiniestro
import co.solventa.core.network.Requerimiento
import co.solventa.core.network.SiniestroDetalle
import co.solventa.feature.siniestros.R
import co.solventa.feature.siniestros.comun.BotonPrincipal
import co.solventa.feature.siniestros.comun.BotonSecundario
import co.solventa.feature.siniestros.comun.Cargando
import co.solventa.feature.siniestros.comun.Mensaje
import co.solventa.feature.siniestros.evidencia.AccionesEvidencia
import co.solventa.feature.siniestros.evidencia.EstadoAdjunto
import co.solventa.feature.siniestros.evidencia.FalloCarga
import co.solventa.feature.siniestros.evidencia.PasoEvidencia
import co.solventa.feature.siniestros.evidencia.etiqueta
import co.solventa.feature.siniestros.evidencia.mensaje
import co.solventa.feature.siniestros.reporte.etiqueta
import co.solventa.feature.siniestros.reporte.formatoFecha
import co.solventa.feature.siniestros.reporte.formatoFechaHora
import co.solventa.feature.siniestros.reporte.formatoPesos

private val PUNTO = 12.dp

@Composable
internal fun Encabezado(detalle: SiniestroDetalle) {
    Column(
        verticalArrangement = Arrangement.spacedBy(SolventaSpacing.xs),
        modifier = Modifier.testTag("encabezado").semantics(mergeDescendants = true) {},
    ) {
        Text(
            stringResource(detalle.tipoEvento.etiqueta()),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        InsigniaEstado(detalle.estado, Modifier.testTag("insignia-estado"))
        Text(stringResource(R.string.seguimiento_referencia, detalle.referencia))
        Text(
            stringResource(R.string.seguimiento_poliza, detalle.numeroPoliza),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Cada evento del historial, del reporte al estado actual. Solo el último se resalta: un mismo estado
 * puede repetirse (p. ej. dos veces «Requiere información»).
 */
@Composable
internal fun LineaDeTiempo(historial: List<EventoSiniestro>) {
    Titulo(R.string.seguimiento_historial)
    Column(verticalArrangement = Arrangement.spacedBy(SolventaSpacing.sm), modifier = Modifier.testTag("historial")) {
        historial.forEachIndexed { indice, evento ->
            Evento(evento, indice, actual = indice == historial.lastIndex)
        }
    }
}

@Composable
private fun Evento(evento: EventoSiniestro, indice: Int, actual: Boolean) {
    // El primero es el reporte: se nombra así aunque el estado sea `reportado`.
    val nombre = if (evento.estadoAnterior == null && indice == 0) {
        stringResource(R.string.seguimiento_evento_reporte)
    } else {
        stringResource(evento.estado.etiqueta())
    }
    val fecha = formatoFechaHora(evento.ocurridoEn)
    val color = if (actual) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val descripcionActual = stringResource(R.string.seguimiento_evento_actual, nombre, fecha)
    Row(
        horizontalArrangement = Arrangement.spacedBy(SolventaSpacing.md),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (actual) "evento-actual" else "evento-$indice")
            .semantics(mergeDescendants = true) { if (actual) contentDescription = descripcionActual },
    ) {
        Box(Modifier.padding(top = 4.dp).size(PUNTO).background(color, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(
                nombre,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (actual) FontWeight.Bold else FontWeight.Normal,
            )
            Text(fecha, style = MaterialTheme.typography.bodySmall)
            evento.motivo?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            if (actual) {
                Text(
                    stringResource(R.string.seguimiento_estado_actual),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Fecha del evento, descripción, monto, canal, último cambio y siguiente paso. */
@Composable
internal fun Resumen(detalle: SiniestroDetalle) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("resumen")) {
        Column(
            verticalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
            modifier = Modifier.padding(SolventaSpacing.md),
        ) {
            Titulo(R.string.seguimiento_resumen)
            Fila(R.string.seguimiento_fecha_evento, formatoFecha(detalle.ocurridoEl), "fila-fecha")
            Fila(R.string.seguimiento_descripcion, detalle.descripcion, "fila-descripcion")
            val monto = detalle.montoEstimado?.let { dinero ->
                val pesos = stringResource(R.string.prefijo_pesos) + formatoPesos(dinero.valor)
                if (dinero.moneda == "COP") pesos else "$pesos ${dinero.moneda}"
            } ?: stringResource(R.string.seguimiento_sin_monto)
            Fila(R.string.seguimiento_monto, monto, "fila-monto")
            Fila(R.string.seguimiento_canal_titulo, textoCanal(detalle.canal), "fila-canal")
            val cambio = detalle.estadoActualizadoEn?.let(::formatoFechaHora)
                ?: stringResource(R.string.seguimiento_sin_cambios)
            Fila(R.string.seguimiento_ultimo_cambio, cambio, "fila-ultimo-cambio")
            Fila(R.string.seguimiento_siguiente_paso, textoPaso(siguientePaso(detalle)), "fila-siguiente-paso")
        }
    }
}

@Composable
private fun Fila(etiqueta: Int, valor: String, tag: String) {
    Column(Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {}) {
        Text(stringResource(etiqueta), style = MaterialTheme.typography.labelLarge)
        Text(valor, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun textoPaso(paso: PasoSeguimiento): String = when (paso) {
    is PasoSeguimiento.Rechazado -> paso.motivo
        ?.let { stringResource(R.string.paso_rechazado_motivo, it) }
        ?: stringResource(R.string.paso_rechazado)
    PasoSeguimiento.Pagado -> stringResource(R.string.paso_pagado)
    PasoSeguimiento.ResponderRequerimientos -> stringResource(R.string.paso_responder)
    PasoSeguimiento.AdjuntarEvidencia -> stringResource(R.string.paso_adjuntar)
    PasoSeguimiento.EsperarEvaluacion -> stringResource(R.string.paso_esperar)
}

/** Qué falta, desde cuándo y su estado; «Responder» solo si está pendiente. */
@Composable
internal fun Requerimientos(requerimientos: List<Requerimiento>, onResponder: (String) -> Unit) {
    if (requerimientos.isEmpty()) return
    OutlinedCard(Modifier.fillMaxWidth().testTag("requerimientos")) {
        Column(
            verticalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
            modifier = Modifier.padding(SolventaSpacing.md),
        ) {
            Titulo(R.string.seguimiento_requerimientos)
            requerimientos.forEachIndexed { indice, requerimiento ->
                if (indice > 0) HorizontalDivider()
                FilaRequerimiento(requerimiento, onResponder)
            }
        }
    }
}

@Composable
private fun FilaRequerimiento(requerimiento: Requerimiento, onResponder: (String) -> Unit) {
    val estado = requerimiento.estadoMostrado
    Column(
        verticalArrangement = Arrangement.spacedBy(SolventaSpacing.xs),
        modifier = Modifier.fillMaxWidth().testTag("requerimiento-${requerimiento.id}"),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(requerimiento.descripcion, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.requerimiento_pedido, formatoFechaHora(requerimiento.creadoEn)),
                style = MaterialTheme.typography.bodySmall,
            )
            val texto = when (estado) {
                EstadoRequerimiento.PENDIENTE -> stringResource(R.string.requerimiento_pendiente)
                EstadoRequerimiento.ATENDIDO -> stringResource(
                    R.string.requerimiento_atendido,
                    formatoFechaHora(requireNotNull(requerimiento.atendidoEn)),
                )
                EstadoRequerimiento.CERRADO_SIN_RESPUESTA -> stringResource(R.string.requerimiento_cerrado)
            }
            Text(
                texto,
                style = MaterialTheme.typography.labelLarge,
                color = if (estado == EstadoRequerimiento.PENDIENTE) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.testTag("estado-requerimiento-${requerimiento.id}"),
            )
        }
        if (requerimiento.respondible) {
            val descripcion = stringResource(R.string.requerimiento_responder_descripcion, requerimiento.descripcion)
            BotonPrincipal(
                stringResource(R.string.requerimiento_responder),
                onClick = { onResponder(requerimiento.id) },
                modifier = Modifier
                    .testTag("responder-${requerimiento.id}")
                    .semantics { contentDescription = descripcion },
            )
        }
    }
}

/**
 * Hoja inferior para responder un requerimiento: cámara, galería o documento, con las reglas, la
 * compresión y los permisos del paso de evidencia. Un archivo por respuesta: elegir otro lo reemplaza.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HojaRespuesta(
    requerimiento: Requerimiento,
    viewModel: DetalleSiniestroViewModel,
    acciones: AccionesEvidencia,
) {
    val gestor = viewModel.evidencia
    val evidencia = gestor.estado
    ModalBottomSheet(
        onDismissRequest = viewModel::cancelarRespuesta,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            // Mientras se envía, la hoja no se cierra: se perdería el resultado.
            confirmValueChange = { !gestor.estado.enviando },
        ),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(SolventaSpacing.md),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SolventaSpacing.md)
                .padding(bottom = SolventaSpacing.lg)
                .testTag("hoja-respuesta"),
        ) {
            Text(
                stringResource(R.string.respuesta_titulo, requerimiento.descripcion),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            PasoEvidencia(evidencia, gestor, acciones, instrucciones = R.string.respuesta_instrucciones)
            val fallido = evidencia.adjuntos.lastOrNull { it.estado == EstadoAdjunto.FALLIDO }
            fallido?.let { adjunto ->
                val nombre = stringResource(adjunto.formato.etiqueta(), 1)
                val motivo = stringResource((adjunto.fallo ?: FalloCarga.GENERICO).mensaje())
                Mensaje(stringResource(R.string.carga_fallida, nombre, motivo), Modifier.testTag("respuesta-fallida"))
            }
            if (evidencia.errorConsulta) Mensaje(stringResource(R.string.respuesta_sin_conciliar))
            if (evidencia.enviando) Cargando(stringResource(R.string.respuesta_enviando))
            val reintentar = evidencia.listos.isEmpty() && evidencia.adjuntos.any { it.reintentable }
            BotonPrincipal(
                stringResource(if (reintentar) R.string.respuesta_reintentar else R.string.respuesta_enviar),
                onClick = viewModel::enviarRespuesta,
                habilitado = (evidencia.listos.isNotEmpty() || reintentar) && !evidencia.preparando,
                cargando = evidencia.enviando,
                modifier = Modifier.testTag("enviar-respuesta"),
            )
            BotonSecundario(
                stringResource(R.string.boton_cancelar),
                onClick = viewModel::cancelarRespuesta,
                habilitado = !evidencia.enviando,
                modifier = Modifier.testTag("cancelar-respuesta"),
            )
        }
    }
}

@Composable
private fun Titulo(texto: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(texto),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
    }
}
