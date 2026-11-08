package co.solventa.feature.siniestros.evidencia

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import co.solventa.core.network.Evidencia
import co.solventa.feature.siniestros.R
import co.solventa.feature.siniestros.comun.BotonSecundario
import co.solventa.feature.siniestros.comun.Cargando
import co.solventa.feature.siniestros.comun.Mensaje
import co.solventa.feature.siniestros.reporte.formatoFechaHora
import co.solventa.feature.siniestros.reporte.formatoTamano

/**
 * En la confirmación: qué se adjuntó, qué no y por qué; la evidencia del siniestro y agregar más. Lo del
 * borrador lo sube el enviador y lo agregado después, el gestor: la pantalla los muestra juntos.
 */
@Composable
internal fun SeccionEvidencia(
    estado: EstadoEvidencia,
    onReintentar: () -> Unit,
    onConsultar: () -> Unit,
    onAgregar: () -> Unit,
) {
    if (estado.adjuntos.isNotEmpty()) {
        Text(stringResource(R.string.confirmacion_adjuntos), style = MaterialTheme.typography.titleSmall)
        estado.adjuntos.forEachIndexed { indice, adjunto -> FilaCarga(adjunto, indice) }
    }
    if (estado.enviando) Cargando(stringResource(R.string.evidencia_enviando))
    if (estado.puedeReintentar) {
        BotonSecundario(
            stringResource(R.string.evidencia_reintentar),
            onClick = onReintentar,
            modifier = Modifier.testTag("reintentar-evidencia"),
        )
    }
    if (estado.errorConsulta) {
        Mensaje(stringResource(R.string.evidencia_error_consulta))
        BotonSecundario(stringResource(R.string.boton_reintentar), onClick = onConsultar)
    }
    if (estado.delSiniestro.isNotEmpty()) {
        Text(stringResource(R.string.confirmacion_evidencia), style = MaterialTheme.typography.titleSmall)
        estado.delSiniestro.forEachIndexed { indice, evidencia -> FilaEvidencia(evidencia, indice) }
    }
    BotonSecundario(
        stringResource(R.string.evidencia_agregar),
        onClick = onAgregar,
        habilitado = !estado.enviando,
        modifier = Modifier.testTag("agregar-evidencia"),
    )
}

@Composable
private fun FilaCarga(adjunto: Adjunto, indice: Int) {
    val nombre = stringResource(adjunto.formato.etiqueta(), indice + 1)
    val texto = when (adjunto.estado) {
        EstadoAdjunto.ENVIADO -> stringResource(R.string.carga_enviada, nombre)
        EstadoAdjunto.FALLIDO -> stringResource(
            R.string.carga_fallida,
            nombre,
            stringResource((adjunto.fallo ?: FalloCarga.GENERICO).mensaje()),
        )
        else -> stringResource(R.string.carga_enviando, nombre)
    }
    val fallida = adjunto.estado == EstadoAdjunto.FALLIDO
    Text(
        texto,
        color = if (fallida) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("carga-$indice").semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun FilaEvidencia(evidencia: Evidencia, indice: Int) {
    val formato = FormatoEvidencia.entries.firstOrNull { it.tipoContenido == evidencia.tipoContenido }
    val tieneCoordenadas = evidencia.latitud != null && evidencia.longitud != null
    Column(Modifier.testTag("evidencia-$indice").semantics(mergeDescendants = true) {}) {
        HorizontalDivider()
        Text(
            "${stringResource(formato.etiqueta(), indice + 1)} · ${formatoTamano(evidencia.tamano)}",
            style = MaterialTheme.typography.titleSmall,
        )
        evidencia.tomadaEn?.let {
            Text(
                stringResource(R.string.evidencia_tomada, formatoFechaHora(it)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val lugar = if (tieneCoordenadas) textoCoordenadas(evidencia.latitud, evidencia.longitud) else evidencia.lugar
        lugar?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@StringRes
internal fun FalloCarga.mensaje() = when (this) {
    FalloCarga.TAMANO -> R.string.fallo_tamano
    FalloCarga.FORMATO -> R.string.fallo_formato
    FalloCarga.VACIO -> R.string.fallo_vacio
    FalloCarga.UBICACION -> R.string.fallo_ubicacion
    FalloCarga.FECHA -> R.string.fallo_fecha
    FalloCarga.CERRADO -> R.string.fallo_cerrado
    FalloCarga.NO_ENCONTRADO -> R.string.fallo_no_encontrado
    FalloCarga.ARCHIVO_PERDIDO -> R.string.fallo_archivo_perdido
    FalloCarga.ALMACENAMIENTO -> R.string.fallo_almacenamiento
    FalloCarga.SIN_RED -> R.string.fallo_sin_red
    FalloCarga.GENERICO -> R.string.fallo_generico
}
