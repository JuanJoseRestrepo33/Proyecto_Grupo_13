package co.solventa.feature.siniestros.reporte

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import co.solventa.core.network.EstadoPoliza
import co.solventa.core.network.EstadoSiniestro
import co.solventa.core.network.PolizaResumen
import co.solventa.core.network.SiguientePaso
import co.solventa.core.network.TipoEventoSiniestro
import co.solventa.feature.siniestros.R
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Textos legibles de los valores del contrato: la interfaz nunca muestra el código tal cual.

private val COLOMBIA: Locale = Locale.forLanguageTag("es-CO")
private val FECHA_LARGA: DateTimeFormatter = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", COLOMBIA)

fun formatoFecha(fecha: LocalDate): String = FECHA_LARGA.format(fecha)

/** La fecha del contrato (ISO) en texto largo; si no se puede leer, tal cual. */
fun formatoFecha(iso: String): String = runCatching { formatoFecha(LocalDate.parse(iso)) }.getOrDefault(iso)

fun formatoPesos(pesos: Long): String = NumberFormat.getIntegerInstance(COLOMBIA).format(pesos)

fun TipoEventoSiniestro.etiqueta() = when (this) {
    TipoEventoSiniestro.ACCIDENTE_VEHICULO -> R.string.tipo_accidente_vehiculo
    TipoEventoSiniestro.HURTO -> R.string.tipo_hurto
    TipoEventoSiniestro.DANO_HOGAR -> R.string.tipo_dano_hogar
    TipoEventoSiniestro.RETRASO_VUELO -> R.string.tipo_retraso_vuelo
    TipoEventoSiniestro.EQUIPO_DANADO_O_PERDIDO -> R.string.tipo_equipo_danado_o_perdido
    TipoEventoSiniestro.CANCELACION_VIAJE -> R.string.tipo_cancelacion_viaje
    TipoEventoSiniestro.OTRO -> R.string.tipo_otro
}

fun EstadoPoliza.etiqueta() = when (this) {
    EstadoPoliza.VIGENTE -> R.string.poliza_vigente
    EstadoPoliza.VENCIDA -> R.string.poliza_vencida
    EstadoPoliza.POR_INICIAR -> R.string.poliza_por_iniciar
}

fun EstadoSiniestro.etiqueta() = when (this) {
    EstadoSiniestro.REPORTADO -> R.string.estado_reportado
    EstadoSiniestro.EN_EVALUACION -> R.string.estado_en_evaluacion
    EstadoSiniestro.REQUIERE_INFORMACION -> R.string.estado_requiere_informacion
    EstadoSiniestro.APROBADO -> R.string.estado_aprobado
    EstadoSiniestro.RECHAZADO -> R.string.estado_rechazado
    EstadoSiniestro.PAGADO -> R.string.estado_pagado
}

fun SiguientePaso.explicacion() = when (this) {
    SiguientePaso.ADJUNTAR_EVIDENCIA -> R.string.siguiente_adjuntar_evidencia
    SiguientePaso.ESPERAR_EVALUACION -> R.string.siguiente_esperar_evaluacion
}

fun PasoReporte.titulo() = when (this) {
    PasoReporte.POLIZA -> R.string.paso_poliza
    PasoReporte.INFORMACION -> R.string.paso_informacion
    PasoReporte.REVISION -> R.string.paso_revision
}

/** El producto llega como código libre (`auto`); los conocidos tienen nombre propio. */
@Composable
fun nombreProducto(poliza: PolizaResumen): String = when (poliza.producto) {
    "auto" -> stringResource(R.string.producto_auto)
    "hogar" -> stringResource(R.string.producto_hogar)
    "viaje" -> stringResource(R.string.producto_viaje)
    else -> stringResource(R.string.producto_otro, poliza.producto.replace('_', ' '))
}
