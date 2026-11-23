package co.solventa.feature.polizas.detalle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.feature.polizas.R

// Piezas del detalle: una tarjeta por sección y filas de etiqueta y valor.

@Composable
internal fun Seccion(titulo: String, etiqueta: String, contenido: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(etiqueta)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
            modifier = Modifier.padding(SolventaSpacing.md),
        ) {
            Text(
                titulo,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            contenido()
        }
    }
}

/** Etiqueta y valor; TalkBack los lee juntos («Deducible: No disponible»). */
@Composable
internal fun Fila(etiqueta: String, valor: String, tag: String) {
    val descripcion = stringResource(R.string.fila_descripcion, etiqueta, valor)
    Row(
        horizontalArrangement = Arrangement.spacedBy(SolventaSpacing.md),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("fila-$tag")
            .clearAndSetSemantics { contentDescription = descripcion },
    ) {
        Text(etiqueta, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            valor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}
