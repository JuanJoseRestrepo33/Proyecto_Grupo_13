package co.solventa.feature.siniestros.reporte

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.solventa.core.network.Siniestro
import co.solventa.feature.siniestros.R
import co.solventa.feature.siniestros.comun.BotonPrincipal
import co.solventa.feature.siniestros.comun.PlantillaPantalla

/** Confirmación del reporte: referencia (la misma que ve la web), estado y siguiente paso. */
@Composable
fun PantallaConfirmacion(siniestro: Siniestro, onTerminar: () -> Unit) {
    PlantillaPantalla(stringResource(R.string.confirmacion_titulo), onVolver = null) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Text(stringResource(R.string.confirmacion_referencia), style = MaterialTheme.typography.labelLarge)
        Text(
            siniestro.referencia,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.testTag("referencia").semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            stringResource(R.string.confirmacion_estado, stringResource(siniestro.estado.etiqueta())),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("estado"),
        )
        Text(stringResource(R.string.confirmacion_siguiente_paso), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(siniestro.siguientePaso.explicacion()),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("siguiente-paso"),
        )
        BotonPrincipal(stringResource(R.string.boton_volver_inicio), onClick = onTerminar)
    }
}
