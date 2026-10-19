package co.solventa.feature.onboarding.comun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.feature.onboarding.R

/** Estructura común: barra con volver opcional, título y contenido desplazable (fuentes grandes). */
@Composable
fun PlantillaPantalla(
    titulo: String,
    onVolver: (() -> Unit)?,
    modifier: Modifier = Modifier,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SolventaSpacing.md),
        verticalArrangement = Arrangement.spacedBy(SolventaSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onVolver != null) {
                // El área táctil por defecto de IconButton es 48 dp, pero su nodo mide 40: se fija en 48.
                IconButton(onClick = onVolver, modifier = Modifier.size(SolventaSpacing.touchTarget)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.volver))
                }
            }
            Text(
                titulo,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        contenido()
    }
}

@Composable
fun BotonPrincipal(
    texto: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
    cargando: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = habilitado && !cargando,
        modifier = modifier.fillMaxWidth().heightIn(min = SolventaSpacing.touchTarget),
    ) {
        if (cargando) {
            val descripcion = stringResource(R.string.cargando)
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp).semantics { contentDescription = descripcion },
                strokeWidth = 2.dp,
            )
        } else {
            Text(texto)
        }
    }
}

@Composable
fun BotonSecundario(texto: String, onClick: () -> Unit, modifier: Modifier = Modifier, habilitado: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = habilitado,
        modifier = modifier.fillMaxWidth().heightIn(min = SolventaSpacing.touchTarget),
    ) { Text(texto) }
}

@Composable
fun BotonTexto(texto: String, onClick: () -> Unit, modifier: Modifier = Modifier, habilitado: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = habilitado,
        modifier = modifier.heightIn(min = SolventaSpacing.touchTarget),
    ) { Text(texto) }
}

/**
 * Campo con validación en línea (P06): el error se anuncia a TalkBack y reemplaza la ayuda.
 * [onSalir] se llama cuando el campo pierde el foco después de haberlo tenido.
 */
@Composable
fun CampoTexto(
    valor: String,
    onCambio: (String) -> Unit,
    etiqueta: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    ayuda: String? = null,
    teclado: KeyboardOptions = KeyboardOptions.Default,
    secreto: Boolean = false,
    habilitado: Boolean = true,
    onSalir: () -> Unit = {},
) {
    var tuvoFoco by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = valor,
        onValueChange = onCambio,
        label = { Text(etiqueta) },
        isError = error != null,
        enabled = habilitado,
        singleLine = true,
        keyboardOptions = teclado,
        visualTransformation = if (secreto) PasswordVisualTransformation() else VisualTransformation.None,
        supportingText = (error ?: ayuda)?.let { texto -> { Text(texto) } },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { foco ->
                if (foco.isFocused) {
                    tuvoFoco = true
                } else if (tuvoFoco) {
                    onSalir()
                }
            }
            .semantics { if (error != null) error(error) },
    )
}

/** Mensaje de estado que TalkBack anuncia al aparecer. */
@Composable
fun Mensaje(texto: String, modifier: Modifier = Modifier, esError: Boolean = true) {
    Text(
        texto,
        color = if (esError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.testTag("mensaje").semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
fun Cargando(texto: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = SolventaSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SolventaSpacing.md),
    ) {
        CircularProgressIndicator()
        Text(texto, style = MaterialTheme.typography.bodyLarge)
    }
}
