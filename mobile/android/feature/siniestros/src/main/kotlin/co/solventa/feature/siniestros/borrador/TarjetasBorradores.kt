@file:Suppress("TooManyFunctions", "MatchingDeclarationName")

package co.solventa.feature.siniestros.borrador

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.feature.siniestros.R
import co.solventa.feature.siniestros.comun.BotonTexto
import co.solventa.feature.siniestros.evidencia.EstadoAdjunto
import co.solventa.feature.siniestros.evidencia.FalloCarga
import co.solventa.feature.siniestros.evidencia.etiqueta
import co.solventa.feature.siniestros.evidencia.mensaje
import co.solventa.feature.siniestros.reporte.etiqueta
import co.solventa.feature.siniestros.reporte.formatoFecha
import co.solventa.feature.siniestros.reporte.formatoFechaHora

/** Lo que el cliente puede hacer desde una tarjeta de Inicio. */
class AccionesTarjeta(
    /** M9 «Revisar»: abre el asistente en el paso afectado. */
    val onRevisar: (String) -> Unit = {},
    /** M12 «Ingresar»: el flujo de sesión de la app. */
    val onIngresar: () -> Unit = {},
    /** M14 «Reportar de nuevo». */
    val onReportar: () -> Unit = {},
)

/**
 * Inicio: una tarjeta por borrador del cliente con sesión, con su estado y sus acciones (M5–M10, M12,
 * M14). No hay pantalla de detalle. Los cambios de estado se anuncian como región viva.
 */
@Composable
fun TarjetasBorradores(enviador: EnviadorBorradores, acciones: AccionesTarjeta) {
    val tarjetas by enviador.tarjetas.collectAsState()
    var confirmar by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(SolventaSpacing.sm)) {
        tarjetas.forEach { tarjeta ->
            when (tarjeta) {
                is Tarjeta.DeBorrador -> TarjetaBorrador(tarjeta, enviador, acciones) { confirmar = tarjeta.id }
                is Tarjeta.Ilegible -> TarjetaIlegible(tarjeta, acciones.onReportar) { enviador.descartar(tarjeta.id) }
                is Tarjeta.Enviada -> TarjetaEnviada(tarjeta.registro) { enviador.entendido(tarjeta.id) }
            }
        }
    }
    val aDescartar = tarjetas.filterIsInstance<Tarjeta.DeBorrador>().firstOrNull { it.id == confirmar }
    if (aDescartar != null) {
        ConfirmarDescarte(
            yaIntentado = aDescartar.borrador.registroIncierto,
            onConfirmar = {
                confirmar = null
                enviador.descartar(aDescartar.id)
            },
            onCancelar = { confirmar = null },
        )
    }
}

@Composable
private fun Marco(etiqueta: String, contenido: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(etiqueta)) {
        Column(
            Modifier
                .padding(SolventaSpacing.md)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(SolventaSpacing.xs),
        ) { contenido() }
    }
}

@Composable
private fun Titulo(texto: String) {
    Text(texto, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Cuerpo(texto: String, esError: Boolean = false) {
    Text(
        texto,
        style = MaterialTheme.typography.bodyMedium,
        color = if (esError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Botones(contenido: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(SolventaSpacing.sm)) { contenido() }
}

@Composable
private fun TarjetaBorrador(
    tarjeta: Tarjeta.DeBorrador,
    enviador: EnviadorBorradores,
    acciones: AccionesTarjeta,
    onDescartar: () -> Unit,
) {
    val borrador = tarjeta.borrador
    Marco("borrador-${borrador.id}") {
        when {
            tarjeta.requiereIngreso && !borrador.reportado -> {
                Titulo(stringResource(R.string.tarjeta_ingresar_titulo))
                Resumen(borrador)
                Cuerpo(stringResource(R.string.tarjeta_ingresar_texto))
                Botones { BotonTexto(stringResource(R.string.tarjeta_ingresar), onClick = acciones.onIngresar) }
            }
            tarjeta.enCurso -> Enviando(borrador)
            borrador.reportado -> Reportado(borrador, enviador)
            borrador.estado == EstadoBorrador.REQUIERE_ATENCION -> {
                Titulo(stringResource(R.string.tarjeta_revision_titulo))
                Resumen(borrador)
                Cuerpo(textoRechazo(borrador.rechazo), esError = true)
                Botones {
                    BotonTexto(stringResource(R.string.tarjeta_revisar), onClick = { acciones.onRevisar(borrador.id) })
                    BotonTexto(stringResource(R.string.tarjeta_descartar), onClick = onDescartar)
                }
            }
            // Sin «Revisar» ni clave nueva hasta conciliar con listarSiniestros (M10).
            borrador.estado == EstadoBorrador.POR_CONCILIAR -> {
                Titulo(stringResource(R.string.tarjeta_conciliar_titulo))
                Resumen(borrador)
                Cuerpo(stringResource(R.string.tarjeta_conciliar_texto))
                borrador.referenciaEnUso?.let { Cuerpo(stringResource(R.string.tarjeta_ya_existe, it)) }
                Botones {
                    BotonTexto(
                        stringResource(R.string.boton_reintentar),
                        onClick = { enviador.reintentar(borrador.id) },
                    )
                    BotonTexto(stringResource(R.string.tarjeta_descartar), onClick = onDescartar)
                }
            }
            else -> {
                Titulo(stringResource(R.string.tarjeta_pendiente_titulo))
                Resumen(borrador)
                Cuerpo(
                    stringResource(
                        if (borrador.intentoIncierto) R.string.tarjeta_reintentando else R.string.tarjeta_esperando,
                    ),
                )
                Cuerpo(stringResource(R.string.tarjeta_guardado_el, formatoFechaHora(borrador.creadoEnLocal())))
                Botones { BotonTexto(stringResource(R.string.tarjeta_descartar), onClick = onDescartar) }
            }
        }
    }
}

/** M6: sin «Descartar» mientras se envía; el Mutex lo serializa. */
@Composable
private fun Enviando(borrador: Borrador) {
    Titulo(stringResource(R.string.tarjeta_enviando))
    Resumen(borrador)
    if (borrador.reportado && borrador.adjuntos.isNotEmpty()) {
        val enviados = borrador.adjuntos.count { it.estado == EstadoAdjunto.ENVIADO }
        Cuerpo(stringResource(R.string.tarjeta_progreso, enviados, borrador.adjuntos.size))
    }
}

/** M8: el siniestro quedó registrado; falta evidencia. Un fallo no lo deshace. */
@Composable
private fun Reportado(borrador: Borrador, enviador: EnviadorBorradores) {
    Titulo(stringResource(R.string.tarjeta_enviado_titulo))
    Cuerpo(requireNotNull(borrador.referencia))
    val enviados = borrador.adjuntos.count { it.estado == EstadoAdjunto.ENVIADO }
    val total = borrador.adjuntos.size
    Cuerpo(pluralStringResource(R.plurals.tarjeta_evidencia_parcial, total, enviados, total))
    borrador.adjuntos.forEachIndexed { indice, adjunto ->
        if (adjunto.estado != EstadoAdjunto.FALLIDO) return@forEachIndexed
        val nombre = stringResource(adjunto.formato.etiqueta(), indice + 1)
        Cuerpo(
            stringResource(
                R.string.tarjeta_archivo,
                nombre,
                stringResource((adjunto.fallo ?: FalloCarga.GENERICO).mensaje()),
            ),
            esError = true,
        )
        Botones {
            if (adjunto.reintentable) {
                BotonTexto(stringResource(R.string.boton_reintentar), onClick = { enviador.reintentar(borrador.id) })
            }
            BotonTexto(
                stringResource(R.string.tarjeta_quitar, nombre),
                onClick = { enviador.quitarAdjunto(borrador.id, adjunto.id) },
            )
        }
    }
    if (borrador.adjuntos.any { it.reintentoAutomatico || it.estado == EstadoAdjunto.LISTO }) {
        Cuerpo(stringResource(R.string.tarjeta_evidencia_pendiente))
    }
}

/** Tipo de evento y póliza, sin el número completo. */
@Composable
private fun Resumen(borrador: Borrador) {
    Cuerpo(stringResource(borrador.solicitud.tipoEvento.etiqueta()))
    Cuerpo(stringResource(R.string.tarjeta_poliza, borrador.solicitud.numeroPoliza.takeLast(DIGITOS_POLIZA)))
}

@Composable
private fun textoRechazo(rechazo: RechazoBorrador?): String {
    val desde = rechazo?.vigenteDesde
    val hasta = rechazo?.vigenteHasta
    return when (rechazo?.codigo) {
        "evento_fuera_de_vigencia" -> if (desde != null && hasta != null) {
            stringResource(R.string.aviso_fuera_de_vigencia, formatoFecha(desde), formatoFecha(hasta))
        } else {
            stringResource(R.string.aviso_fuera_de_vigencia_sin_fechas)
        }
        "poliza_no_encontrada" -> stringResource(R.string.aviso_poliza_no_encontrada)
        else -> stringResource(R.string.tarjeta_datos_invalidos) + " " + stringResource(R.string.tarjeta_no_registro)
    }
}

/** M7, hasta que el cliente toca «Entendido». */
@Composable
private fun TarjetaEnviada(registro: RegistroEnviado, onEntendido: () -> Unit) {
    Marco("enviado-${registro.id}") {
        Titulo(stringResource(R.string.tarjeta_enviado_titulo))
        Cuerpo(stringResource(R.string.tarjeta_referencia))
        Text(
            registro.referencia,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("referencia-enviada"),
        )
        Cuerpo(stringResource(R.string.tarjeta_enviado_web))
        Botones { BotonTexto(stringResource(R.string.tarjeta_entendido), onClick = onEntendido) }
    }
}

/** M14: nunca se descarta solo. Si el fallo es temporal, se reintenta y no se ofrece eliminar. */
@Composable
private fun TarjetaIlegible(tarjeta: Tarjeta.Ilegible, onReportar: () -> Unit, onEliminar: () -> Unit) {
    Marco("ilegible-${tarjeta.id}") {
        Titulo(stringResource(R.string.tarjeta_ilegible_titulo))
        if (tarjeta.permanente) {
            Cuerpo(stringResource(R.string.tarjeta_ilegible_texto))
            Botones {
                BotonTexto(stringResource(R.string.tarjeta_eliminar), onClick = onEliminar)
                BotonTexto(stringResource(R.string.tarjeta_reportar_nuevo), onClick = onReportar)
            }
        } else {
            Cuerpo(stringResource(R.string.tarjeta_ilegible_temporal))
        }
    }
}

/** M11: con un intento ya iniciado se aclara que eliminarlo del teléfono no cancela un siniestro. */
@Composable
private fun ConfirmarDescarte(yaIntentado: Boolean, onConfirmar: () -> Unit, onCancelar: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text(stringResource(if (yaIntentado) R.string.eliminar_titulo else R.string.descartar_titulo)) },
        text = { Text(stringResource(if (yaIntentado) R.string.eliminar_texto else R.string.descartar_texto)) },
        confirmButton = {
            BotonTexto(
                stringResource(if (yaIntentado) R.string.boton_eliminar else R.string.boton_descartar),
                onClick = onConfirmar,
                modifier = Modifier.testTag("confirmar-descarte"),
            )
        },
        dismissButton = { BotonTexto(stringResource(R.string.boton_cancelar), onClick = onCancelar) },
    )
}

private const val DIGITOS_POLIZA = 3

/** La hora de creación en la zona del teléfono, para «Guardado el …». */
private fun Borrador.creadoEnLocal(): String = runCatching {
    java.time.Instant.parse(creadoEn).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString()
}.getOrDefault(creadoEn)
