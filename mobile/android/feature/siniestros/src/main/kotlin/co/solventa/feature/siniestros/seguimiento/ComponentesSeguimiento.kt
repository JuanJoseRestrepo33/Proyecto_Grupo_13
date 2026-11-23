package co.solventa.feature.siniestros.seguimiento

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.solventa.core.designsystem.SolventaColors
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.core.network.EstadoSiniestro
import co.solventa.feature.siniestros.R
import co.solventa.feature.siniestros.comun.BotonPrincipal
import co.solventa.feature.siniestros.comun.Mensaje
import co.solventa.feature.siniestros.reporte.etiqueta

private const val OPACIDAD_FONDO_INSIGNIA = 0.18f

/**
 * Barra superior con volver, título y «Actualizar», y debajo el contenido desplazable que también se
 * actualiza al deslizar hacia abajo. «Actualizar» existe para quien no use el gesto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlantillaSeguimiento(
    titulo: String,
    actualizando: Boolean,
    onActualizar: () -> Unit,
    onVolver: (() -> Unit)?,
    modifier: Modifier = Modifier,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = SolventaSpacing.xs),
        ) {
            if (onVolver != null) {
                IconButton(onClick = onVolver, modifier = Modifier.size(SolventaSpacing.touchTarget)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.volver))
                }
            }
            Text(
                titulo,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).padding(horizontal = SolventaSpacing.sm).semantics { heading() },
            )
            IconButton(
                onClick = onActualizar,
                enabled = !actualizando,
                modifier = Modifier.size(SolventaSpacing.touchTarget).testTag("actualizar"),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.seguimiento_actualizar))
            }
        }
        PullToRefreshBox(
            isRefreshing = actualizando,
            onRefresh = onActualizar,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(SolventaSpacing.md),
                verticalArrangement = Arrangement.spacedBy(SolventaSpacing.md),
                content = contenido,
            )
        }
    }
}

/** Estado del caso con texto y color; TalkBack lee «Estado: …». */
@Composable
internal fun InsigniaEstado(estado: EstadoSiniestro, modifier: Modifier = Modifier) {
    val texto = stringResource(estado.etiqueta())
    val descripcion = stringResource(R.string.seguimiento_estado, texto)
    Box(
        modifier
            .background(estado.tono.color().copy(alpha = OPACIDAD_FONDO_INSIGNIA), RoundedCornerShape(50))
            .padding(horizontal = SolventaSpacing.sm, vertical = SolventaSpacing.xs)
            .semantics { contentDescription = descripcion },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).background(estado.tono.color(), RoundedCornerShape(50)))
            Text(texto, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

internal fun TonoInsignia.color(): Color = when (this) {
    TonoInsignia.AMBAR -> SolventaColors.warning
    TonoInsignia.VERDE -> SolventaColors.success
    TonoInsignia.ROJO -> SolventaColors.error
}

/** «Web» o «App», del `canal` del contrato. */
@Composable
internal fun textoCanal(canal: String): String = when (canal) {
    "web" -> stringResource(R.string.seguimiento_canal_web)
    "movil" -> stringResource(R.string.seguimiento_canal_movil)
    else -> canal
}

/** Error de consulta con «Reintentar». */
@Composable
internal fun ErrorConsulta(texto: String, onReintentar: () -> Unit) {
    Mensaje(texto)
    BotonPrincipal(
        stringResource(R.string.boton_reintentar),
        onClick = onReintentar,
        modifier = Modifier.testTag("reintentar"),
    )
}

@Composable
internal fun textoFallo(fallo: FalloConsulta, @androidx.annotation.StringRes generico: Int): String = when (fallo) {
    FalloConsulta.SIN_RED -> stringResource(R.string.seguimiento_sin_red)
    FalloConsulta.GENERICO -> stringResource(generico)
}
