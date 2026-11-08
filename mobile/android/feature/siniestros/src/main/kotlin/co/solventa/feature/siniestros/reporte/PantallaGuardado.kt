package co.solventa.feature.siniestros.reporte

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.solventa.feature.siniestros.R
import co.solventa.feature.siniestros.comun.BotonPrincipal
import co.solventa.feature.siniestros.comun.PlantillaPantalla

/**
 * M4 · el reporte quedó cifrado en el teléfono. Dos variantes, para no decir «No hay conexión» cuando
 * solo se perdió la respuesta. Ningún texto promete un envío con la app cerrada.
 */
@Composable
fun PantallaGuardado(tipo: ReporteGuardado, archivos: Int, onTerminar: () -> Unit) {
    PlantillaPantalla(stringResource(R.string.guardado_titulo), onVolver = null) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        val texto = when {
            tipo == ReporteGuardado.SIN_CONFIRMAR -> stringResource(R.string.guardado_sin_confirmar)
            archivos > 0 -> pluralStringResource(R.plurals.guardado_sin_conexion_archivos, archivos, archivos)
            else -> stringResource(R.string.guardado_sin_conexion)
        }
        Text(
            texto,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("guardado-${tipo.name.lowercase()}").semantics {
                liveRegion = LiveRegionMode.Polite
            },
        )
        Text(
            stringResource(
                if (tipo == ReporteGuardado.SIN_CONFIRMAR) {
                    R.string.guardado_referencia_confirmar
                } else {
                    R.string.guardado_referencia_envio
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        BotonPrincipal(stringResource(R.string.boton_volver_inicio), onClick = onTerminar)
    }
}
