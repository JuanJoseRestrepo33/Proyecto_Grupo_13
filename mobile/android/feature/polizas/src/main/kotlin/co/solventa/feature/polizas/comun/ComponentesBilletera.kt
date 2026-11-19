package co.solventa.feature.polizas.comun

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import co.solventa.core.designsystem.SolventaColors
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.feature.polizas.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Piezas de la billetera sin conexión (MOB-F03) que comparten la lista, el detalle y la credencial.

private const val OPACIDAD_FRANJA = 0.16f

/** Qué dice la franja fija de arriba. */
enum class Franja {
    /** «Sin conexión · datos del 18 nov, 08:14». */
    SIN_CONEXION,

    /** «No pudimos actualizar · datos del …» con «Reintentar». */
    SIN_ACTUALIZAR,

    /** «Conexión recuperada · actualizando tus pólizas…» con indicador. */
    SINCRONIZANDO,

    /** «Sin conexión», sin fecha: la credencial ya la lleva en el pie. */
    SOLO_SIN_CONEXION,
}

/**
 * Franja fija arriba de la pantalla. Siempre con ícono y texto, nunca solo el color; TalkBack la lee
 * completa y la anuncia al aparecer. «Reintentar» es un control aparte de al menos 48 dp.
 */
@Composable
internal fun FranjaBilletera(
    franja: Franja,
    sincronizadaEn: Instant?,
    zona: ZoneId,
    onReintentar: (() -> Unit)? = null,
) {
    val datos = sincronizadaEn?.let { formatoSincronizacion(it, zona) }.orEmpty()
    val texto = when (franja) {
        Franja.SIN_CONEXION -> stringResource(R.string.franja_sin_conexion, datos)
        Franja.SIN_ACTUALIZAR -> stringResource(R.string.franja_sin_actualizar, datos)
        Franja.SINCRONIZANDO -> stringResource(R.string.franja_sincronizando)
        Franja.SOLO_SIN_CONEXION -> stringResource(R.string.franja_solo_sin_conexion)
    }
    val color = if (franja == Franja.SINCRONIZANDO) SolventaColors.secondary else SolventaColors.warning
    Column(
        verticalArrangement = Arrangement.spacedBy(SolventaSpacing.xs),
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = OPACIDAD_FRANJA))
            .padding(horizontal = SolventaSpacing.md, vertical = SolventaSpacing.sm)
            .testTag("franja-${franja.name.lowercase().replace('_', '-')}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SolventaSpacing.touchTarget)
                .clearAndSetSemantics {
                    contentDescription = texto
                    liveRegion = LiveRegionMode.Polite
                },
        ) {
            if (franja == Franja.SINCRONIZANDO) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(texto, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        if (franja == Franja.SIN_ACTUALIZAR && onReintentar != null) {
            OutlinedButton(
                onClick = onReintentar,
                modifier = Modifier.heightIn(min = SolventaSpacing.touchTarget).testTag("franja-reintentar"),
            ) { Text(stringResource(R.string.reintentar)) }
        }
    }
}

/** La franja que corresponde, o null si no hay que mostrar ninguna. */
internal fun franjaDe(sinConexion: Boolean, sinActualizar: Boolean, sincronizando: Boolean): Franja? = when {
    sincronizando -> Franja.SINCRONIZANDO
    sinConexion && sinActualizar -> Franja.SIN_ACTUALIZAR
    sinConexion -> Franja.SIN_CONEXION
    else -> null
}

/**
 * Bajo el título con conexión: «Disponible sin conexión · sincronizada hoy 08:14», o «Aún no disponible
 * sin conexión» si nunca hubo una sincronización completa.
 */
@Composable
internal fun LineaSincronizacion(sincronizadaEn: Instant?, hoy: LocalDate, zona: ZoneId) {
    val texto = when {
        sincronizadaEn == null -> stringResource(R.string.billetera_no_disponible)
        esHoy(sincronizadaEn, hoy, zona) ->
            stringResource(R.string.billetera_disponible_hoy, formatoHora(sincronizadaEn, zona))
        else -> stringResource(R.string.billetera_disponible_el, formatoSincronizacion(sincronizadaEn, zona))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (sincronizadaEn == null) "billetera-no-disponible" else "billetera-disponible")
            .clearAndSetSemantics { contentDescription = texto },
    ) {
        Icon(
            if (sincronizadaEn == null) Icons.Default.Info else Icons.Default.CheckCircle,
            contentDescription = null,
            tint = if (sincronizadaEn == null) MaterialTheme.colorScheme.onSurfaceVariant else SolventaColors.success,
            modifier = Modifier.size(20.dp),
        )
        Text(texto, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Texto junto a un control que sin conexión no está disponible (en lugar de la notificación del mockup). */
@Composable
internal fun RequiereConexion(texto: String, etiqueta: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
        modifier = Modifier.fillMaxWidth().testTag(etiqueta).clearAndSetSemantics { contentDescription = texto },
    ) {
        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(texto, style = MaterialTheme.typography.bodyMedium, color = Color.Unspecified)
    }
}
