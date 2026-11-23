package co.solventa.feature.consentimiento

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.core.network.CodigoFinalidad
import co.solventa.core.network.Consentimiento
import co.solventa.core.network.EstadoConsentimiento
import co.solventa.core.network.FinalidadConsentimiento

@Composable
internal fun ListaFinalidades(
    finalidades: List<FinalidadUi>,
    habilitado: Boolean,
    renovando: String?,
    onOtorgar: (FinalidadConsentimiento) -> Unit,
    onRevocar: (String, CodigoFinalidad) -> Unit,
    onRenovar: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(SolventaSpacing.md),
        verticalArrangement = Arrangement.spacedBy(SolventaSpacing.md),
    ) {
        items(finalidades, key = { it.finalidad.codigo.name }) { item ->
            TarjetaFinalidad(item, habilitado, renovando, onOtorgar, onRevocar, onRenovar)
        }
    }
}

@Composable
private fun TarjetaFinalidad(
    item: FinalidadUi,
    habilitado: Boolean,
    renovandoId: String?,
    onOtorgar: (FinalidadConsentimiento) -> Unit,
    onRevocar: (String, CodigoFinalidad) -> Unit,
    onRenovar: (String) -> Unit,
) {
    val f = item.finalidad
    val c = item.consentimiento
    val sufijo = f.codigo.name.lowercase()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tarjeta-$sufijo"),
    ) {
        Column(
            modifier = Modifier.padding(SolventaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(f.nombre, style = MaterialTheme.typography.titleMedium)
                if (c != null) EstadoChip(c)
            }
            Text(f.descripcion, style = MaterialTheme.typography.bodyMedium)
            if (c != null) {
                DetalleConsentimiento(c, sufijo)
            } else {
                // Sin registros: el texto lo genera la app, no es un estado de la API.
                Text(
                    stringResource(R.string.consentimiento_sin_registro),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("sin-consentimiento-$sufijo"),
                )
            }
            AccionesFinalidad(
                finalidad = f,
                consentimiento = c,
                habilitado = habilitado,
                esRenovando = c != null && renovandoId == c.id,
                onOtorgar = onOtorgar,
                onRevocar = onRevocar,
                onRenovar = onRenovar,
            )
        }
    }
}

@Composable
private fun DetalleConsentimiento(c: Consentimiento, sufijo: String) {
    Text(c.entidadesOrigen.joinToString { it.nombre }, style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.otorgado_en, fecha(c.otorgadoEn)), style = MaterialTheme.typography.bodySmall)
    if (c.estado != EstadoConsentimiento.REVOCADO) {
        Text(stringResource(R.string.vence_en, fecha(c.venceEn)), style = MaterialTheme.typography.bodySmall)
    }
    c.revocadoEn?.let {
        Text(stringResource(R.string.revocado_en, fecha(it)), style = MaterialTheme.typography.bodySmall)
    }
    val dias = c.diasParaVencer
    // `0` es el último día antes del vencimiento; `null` corresponde a no vigentes.
    if (c.estado == EstadoConsentimiento.VIGENTE && c.porVencer && dias != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.testTag("por-vencer-$sufijo"),
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = stringResource(R.string.cd_por_vencer),
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp),
            )
            val texto = if (dias == 0) {
                stringResource(R.string.por_vencer_hoy)
            } else {
                stringResource(R.string.por_vencer_dias, dias)
            }
            Text(texto, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EstadoChip(consentimiento: Consentimiento) {
    val (texto, cd) = when (consentimiento.estado) {
        EstadoConsentimiento.VIGENTE -> R.string.estado_vigente to R.string.cd_vigente
        EstadoConsentimiento.REVOCADO -> R.string.estado_revocado to R.string.cd_revocado
        EstadoConsentimiento.VENCIDO -> R.string.estado_vencido to R.string.cd_vencido
    }
    val descripcion = stringResource(cd)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.semantics { contentDescription = descripcion },
    ) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(stringResource(texto), style = MaterialTheme.typography.labelSmall)
    }
}

/** Revocado y sin registro se otorgan de nuevo; vigente se revoca; por vencer y vencido se renuevan. */
@Composable
private fun AccionesFinalidad(
    finalidad: FinalidadConsentimiento,
    consentimiento: Consentimiento?,
    habilitado: Boolean,
    esRenovando: Boolean,
    onOtorgar: (FinalidadConsentimiento) -> Unit,
    onRevocar: (String, CodigoFinalidad) -> Unit,
    onRenovar: (String) -> Unit,
) {
    val sufijo = finalidad.codigo.name.lowercase()
    val estado = consentimiento?.estado
    if (consentimiento == null || estado == EstadoConsentimiento.REVOCADO) {
        Button(
            onClick = { onOtorgar(finalidad) },
            enabled = habilitado,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SolventaSpacing.touchTarget)
                .testTag("boton-otorgar-$sufijo"),
        ) {
            Text(stringResource(R.string.boton_otorgar))
        }
        return
    }
    val renovable = estado == EstadoConsentimiento.VENCIDO ||
        (estado == EstadoConsentimiento.VIGENTE && consentimiento.porVencer)
    if (renovable) {
        Button(
            onClick = { onRenovar(consentimiento.id) },
            enabled = habilitado,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SolventaSpacing.touchTarget)
                .testTag("boton-renovar-$sufijo"),
        ) {
            TextoOProgreso(enProgreso = esRenovando, texto = R.string.boton_renovar)
        }
    }
    if (estado == EstadoConsentimiento.VIGENTE) {
        OutlinedButton(
            onClick = { onRevocar(consentimiento.id, finalidad.codigo) },
            enabled = habilitado,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SolventaSpacing.touchTarget)
                .testTag("boton-revocar-$sufijo"),
        ) {
            Text(stringResource(R.string.boton_revocar))
        }
    }
}
