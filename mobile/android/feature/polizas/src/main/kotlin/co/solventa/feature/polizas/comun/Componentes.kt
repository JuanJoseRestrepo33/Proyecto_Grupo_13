package co.solventa.feature.polizas.comun

// Los módulos de feature no dependen entre sí: estas piezas siguen las de feature/siniestros
// (PlantillaSeguimiento, BotonPrincipal, Mensaje, Cargando) con los textos de este módulo.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.solventa.core.designsystem.SolventaColors
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.core.network.EstadoPoliza
import co.solventa.feature.polizas.R

private const val OPACIDAD_FONDO_INSIGNIA = 0.18f
private val GRIS = Color(0xFF6B7280)

/**
 * Barra superior con volver (opcional), título y, con [onActualizar], el botón «Actualizar» y el gesto
 * de deslizar hacia abajo sobre el contenido. El botón existe para quien no use el gesto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlantillaPolizas(
    titulo: String,
    onVolver: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actualizando: Boolean = false,
    onActualizar: (() -> Unit)? = null,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = SolventaSpacing.touchTarget)
                .padding(horizontal = SolventaSpacing.xs),
        ) {
            if (onVolver != null) {
                IconButton(
                    onClick = onVolver,
                    modifier = Modifier.size(SolventaSpacing.touchTarget).testTag("volver"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.volver))
                }
            }
            Text(
                titulo,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).padding(horizontal = SolventaSpacing.sm).semantics { heading() },
            )
            if (onActualizar != null) {
                IconButton(
                    onClick = onActualizar,
                    enabled = !actualizando,
                    modifier = Modifier.size(SolventaSpacing.touchTarget).testTag("actualizar"),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.actualizar))
                }
            }
        }
        val columna: @Composable () -> Unit = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(SolventaSpacing.md),
                verticalArrangement = Arrangement.spacedBy(SolventaSpacing.md),
                content = contenido,
            )
        }
        if (onActualizar != null) {
            PullToRefreshBox(isRefreshing = actualizando, onRefresh = onActualizar, modifier = Modifier.fillMaxSize()) {
                columna()
            }
        } else {
            columna()
        }
    }
}

@Composable
internal fun BotonPrincipal(
    texto: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cargando: Boolean = false,
    textoCargando: String? = null,
    icono: (@Composable () -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        enabled = !cargando,
        modifier = modifier.fillMaxWidth().heightIn(min = SolventaSpacing.touchTarget),
    ) {
        if (cargando) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            textoCargando?.let { Text(it, modifier = Modifier.padding(start = SolventaSpacing.sm)) }
        } else {
            icono?.let {
                it()
                Box(Modifier.size(SolventaSpacing.sm))
            }
            Text(texto)
        }
    }
}

@Composable
internal fun BotonSecundario(texto: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = SolventaSpacing.touchTarget)) {
        Text(texto)
    }
}

/** Mensaje de estado que TalkBack anuncia al aparecer. */
@Composable
internal fun Mensaje(texto: String, modifier: Modifier = Modifier, esError: Boolean = true) {
    Text(
        texto,
        color = if (esError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.testTag("mensaje").semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Error de consulta con «Reintentar». */
@Composable
internal fun ErrorConsulta(texto: String, onReintentar: () -> Unit) {
    Mensaje(texto)
    BotonSecundario(
        stringResource(R.string.reintentar),
        onClick = onReintentar,
        modifier = Modifier.testTag("reintentar"),
    )
}

@Composable
internal fun textoFallo(fallo: FalloConsulta, @androidx.annotation.StringRes generico: Int): String = when (fallo) {
    FalloConsulta.SIN_RED -> stringResource(R.string.sin_red)
    FalloConsulta.GENERICO -> stringResource(generico)
}

@Composable
internal fun Cargando(texto: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = SolventaSpacing.xl).testTag("cargando"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SolventaSpacing.md),
    ) {
        CircularProgressIndicator()
        Text(texto, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Estado de la póliza con texto y color; TalkBack lee «Estado: …». */
@Composable
internal fun InsigniaEstado(estado: EstadoPoliza, modifier: Modifier = Modifier) {
    val texto = stringResource(estado.etiqueta())
    val descripcion = stringResource(R.string.estado_descripcion, texto)
    val color = estado.tono.color()
    Box(
        modifier
            .background(color.copy(alpha = OPACIDAD_FONDO_INSIGNIA), RoundedCornerShape(50))
            .padding(horizontal = SolventaSpacing.sm, vertical = SolventaSpacing.xs)
            .testTag("insignia-${estado.name.lowercase()}")
            .clearAndSetSemantics { contentDescription = descripcion },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
            Text(texto, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

internal fun TonoInsignia.color(): Color = when (this) {
    TonoInsignia.VERDE -> SolventaColors.success
    TonoInsignia.GRIS -> GRIS
    TonoInsignia.INDIGO -> SolventaColors.secondary
}
