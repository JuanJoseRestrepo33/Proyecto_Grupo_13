package co.solventa.feature.siniestros.seguimiento

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import co.solventa.core.designsystem.SolventaColors
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.core.network.EstadoSiniestro
import co.solventa.core.network.Siniestro
import co.solventa.feature.siniestros.DependenciasSiniestros
import co.solventa.feature.siniestros.R
import co.solventa.feature.siniestros.comun.BotonPrincipal
import co.solventa.feature.siniestros.comun.Cargando
import co.solventa.feature.siniestros.reporte.etiqueta
import co.solventa.feature.siniestros.reporte.formatoFechaHora

/**
 * «Mis siniestros» con su ViewModel. [reporteBloqueado] lo decide la app con los borradores
 * pendientes de confirmar (MOB-F05-02): el mismo bloqueo y el mismo mensaje que en Inicio.
 *
 * @param onAbrir abre el detalle del `id` del siniestro.
 * @param onReportar abre el asistente de reporte (#169).
 */
@Composable
fun RutaMisSiniestros(
    dependencias: DependenciasSiniestros,
    reporteBloqueado: Boolean,
    onAbrir: (String) -> Unit,
    onReportar: () -> Unit,
    onVolver: () -> Unit,
    onSesionVencida: () -> Unit = {},
) {
    val viewModel = viewModel { MisSiniestrosViewModel(dependencias.api) }
    val estado = viewModel.estado
    LaunchedEffect(estado.sesionVencida) {
        if (estado.sesionVencida) {
            viewModel.sesionAtendida()
            onSesionVencida()
        }
    }
    PantallaMisSiniestros(
        estado = estado,
        reporteBloqueado = reporteBloqueado,
        onActualizar = viewModel::actualizar,
        onAbrir = onAbrir,
        onReportar = onReportar,
        onVolver = onVolver,
    )
}

/** Sin estado propio: las pruebas la dibujan con cualquier [estado]. */
@Composable
fun PantallaMisSiniestros(
    estado: EstadoMisSiniestros,
    reporteBloqueado: Boolean,
    onActualizar: () -> Unit,
    onAbrir: (String) -> Unit,
    onReportar: () -> Unit,
    onVolver: (() -> Unit)? = null,
) {
    val siniestros = estado.siniestros
    PlantillaSeguimiento(
        titulo = stringResource(R.string.seguimiento_titulo),
        actualizando = estado.cargando && siniestros != null,
        onActualizar = onActualizar,
        onVolver = onVolver,
        modifier = Modifier.testTag("pantalla-mis-siniestros"),
    ) {
        val error = estado.error
        when {
            siniestros == null && error != null ->
                ErrorConsulta(textoFallo(error, R.string.seguimiento_error), onActualizar)
            siniestros == null -> Cargando(stringResource(R.string.seguimiento_cargando))
            siniestros.isEmpty() -> {
                if (error != null) ErrorConsulta(textoFallo(error, R.string.seguimiento_error), onActualizar)
                SinSiniestros(reporteBloqueado, onReportar)
            }
            else -> {
                if (error != null) ErrorConsulta(textoFallo(error, R.string.seguimiento_error), onActualizar)
                Reportar(reporteBloqueado, onReportar)
                siniestros.forEach { TarjetaSiniestro(it, onAbrir) }
            }
        }
    }
}

@Composable
private fun Reportar(bloqueado: Boolean, onReportar: () -> Unit) {
    BotonPrincipal(
        stringResource(R.string.seguimiento_reportar),
        // El botón deshabilitado no recibe toques; la guarda evita un toque que llegue en el cambio.
        onClick = { if (!bloqueado) onReportar() },
        habilitado = !bloqueado,
        modifier = Modifier.testTag("seguimiento-reportar"),
    )
    if (bloqueado) {
        Text(
            stringResource(R.string.seguimiento_reporte_por_confirmar),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("reporte-bloqueado"),
        )
    }
}

@Composable
private fun SinSiniestros(bloqueado: Boolean, onReportar: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SolventaSpacing.md),
        modifier = Modifier.fillMaxWidth().padding(vertical = SolventaSpacing.lg).testTag("sin-siniestros"),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.List,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Text(
            stringResource(R.string.seguimiento_vacio_titulo),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.seguimiento_vacio_texto), style = MaterialTheme.typography.bodyLarge)
    }
    Reportar(bloqueado, onReportar)
}

/** Toda la tarjeta abre el detalle; TalkBack la lee como un solo control con todo su contenido. */
@Composable
private fun TarjetaSiniestro(siniestro: Siniestro, onAbrir: (String) -> Unit) {
    val tipo = stringResource(siniestro.tipoEvento.etiqueta())
    val estado = stringResource(siniestro.estado.etiqueta())
    val fecha = formatoFechaHora(siniestro.reportadoEn)
    val canal = textoCanal(siniestro.canal)
    val descripcion = stringResource(
        R.string.seguimiento_tarjeta,
        tipo,
        estado,
        siniestro.referencia,
        siniestro.numeroPoliza,
        fecha,
        canal,
    )
    val requiere = siniestro.estado == EstadoSiniestro.REQUIERE_INFORMACION
    OutlinedCard(
        // El borde ámbar acompaña a la insignia, que dice «Requiere información» en texto.
        border = if (requiere) BorderStroke(2.dp, SolventaColors.warning) else CardDefaults.outlinedCardBorder(),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SolventaSpacing.touchTarget)
            .testTag("siniestro-${siniestro.id}"),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(SolventaSpacing.xs),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { onAbrir(siniestro.id) }
                .padding(SolventaSpacing.md)
                .clearAndSetSemantics { contentDescription = descripcion },
        ) {
            Text(tipo, style = MaterialTheme.typography.titleMedium)
            InsigniaEstado(siniestro.estado)
            Text(stringResource(R.string.seguimiento_referencia, siniestro.referencia))
            Text(
                stringResource(R.string.seguimiento_poliza, siniestro.numeroPoliza),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.seguimiento_reportado_el, fecha),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.seguimiento_canal, canal),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
