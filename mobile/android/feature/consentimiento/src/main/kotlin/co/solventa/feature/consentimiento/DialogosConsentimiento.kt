package co.solventa.feature.consentimiento

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.core.network.CodigoFinalidad
import co.solventa.core.network.FinalidadConsentimiento

/**
 * Formulario de otorgamiento: entidades y aceptación de la versión legal, todas desmarcadas al
 * abrir. El catálogo no trae el texto completo, así que se presenta la autorización y su versión.
 */
@Composable
internal fun DialogoOtorgar(
    finalidad: FinalidadConsentimiento,
    entidadesSeleccionadas: Set<String>,
    versionAceptada: Boolean,
    puedeOtorgar: Boolean,
    mutando: Boolean,
    @StringRes mensajeError: Int?,
    onToggleEntidad: (String) -> Unit,
    onToggleVersion: () -> Unit,
    onOtorgar: () -> Unit,
    onCancelar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!mutando) onCancelar() },
        title = { Text(stringResource(R.string.otorgar_titulo)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
            ) {
                MensajeEnDialogo(mensajeError)
                DetalleFinalidad(finalidad)
                finalidad.entidadesOrigen.forEach { entidad ->
                    val seleccionada = entidad.id in entidadesSeleccionadas
                    FilaCheck(
                        marcada = seleccionada,
                        habilitada = !mutando,
                        descripcion = stringResource(
                            if (seleccionada) R.string.cd_entidad_seleccionada else R.string.cd_entidad_no_seleccionada,
                            entidad.nombre,
                        ),
                        tag = "entidad-${entidad.id}",
                        onCambio = { onToggleEntidad(entidad.id) },
                    ) {
                        Text(entidad.nombre, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                FilaCheck(
                    marcada = versionAceptada,
                    habilitada = !mutando,
                    descripcion = stringResource(R.string.otorgar_version_cd),
                    tag = "check-version",
                    onCambio = onToggleVersion,
                ) {
                    Text(
                        stringResource(R.string.otorgar_version_label, finalidad.versionTexto),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onOtorgar,
                enabled = puedeOtorgar,
                modifier = Modifier
                    .heightIn(min = SolventaSpacing.touchTarget)
                    .testTag("boton-confirmar-otorgar"),
            ) {
                TextoOProgreso(enProgreso = mutando, texto = R.string.boton_otorgar)
            }
        },
        dismissButton = { BotonCancelar(habilitado = !mutando, tag = "boton-cancelar-otorgar", onCancelar) },
    )
}

@Composable
private fun DetalleFinalidad(finalidad: FinalidadConsentimiento) {
    Text(finalidad.nombre, style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.otorgar_alcance_label), style = MaterialTheme.typography.labelMedium)
    Text(finalidad.alcance.joinToString(", "), style = MaterialTheme.typography.bodySmall)
    Text(
        stringResource(R.string.otorgar_vigencia_label, finalidad.vigenciaMeses),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(stringResource(R.string.otorgar_entidades_label), style = MaterialTheme.typography.labelMedium)
    Text(stringResource(R.string.otorgar_entidades_ayuda), style = MaterialTheme.typography.bodySmall)
}

/** Fila completa conmutable (objetivo táctil ≥ 48 dp) con casilla de selección múltiple. */
@Composable
private fun FilaCheck(
    marcada: Boolean,
    habilitada: Boolean,
    descripcion: String,
    tag: String,
    onCambio: () -> Unit,
    etiqueta: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SolventaSpacing.touchTarget)
            .toggleable(value = marcada, enabled = habilitada, role = Role.Checkbox, onValueChange = { onCambio() })
            .semantics { contentDescription = descripcion }
            .testTag(tag),
    ) {
        Checkbox(checked = marcada, onCheckedChange = null, enabled = habilitada)
        etiqueta()
    }
}

@Composable
internal fun DialogoRevocar(
    finalidad: CodigoFinalidad?,
    mutando: Boolean,
    @StringRes mensajeError: Int?,
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!mutando) onCancelar() },
        title = { Text(stringResource(R.string.revocar_titulo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SolventaSpacing.sm)) {
                MensajeEnDialogo(mensajeError)
                Text(stringResource(R.string.revocar_pregunta), style = MaterialTheme.typography.bodyMedium)
                if (finalidad == CodigoFinalidad.COTIZACION) {
                    Text(
                        stringResource(R.string.revocar_advertencia_cotizacion),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("advertencia-cotizacion"),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmar,
                enabled = !mutando,
                modifier = Modifier
                    .heightIn(min = SolventaSpacing.touchTarget)
                    .testTag("boton-confirmar-revocar"),
            ) {
                TextoOProgreso(enProgreso = mutando, texto = R.string.boton_confirmar)
            }
        },
        dismissButton = { BotonCancelar(habilitado = !mutando, tag = "boton-cancelar-revocar", onCancelar) },
    )
}

@Composable
private fun BotonCancelar(habilitado: Boolean, tag: String, onCancelar: () -> Unit) {
    TextButton(
        onClick = onCancelar,
        enabled = habilitado,
        modifier = Modifier
            .heightIn(min = SolventaSpacing.touchTarget)
            .testTag(tag),
    ) {
        Text(stringResource(R.string.boton_cancelar))
    }
}

@Composable
private fun MensajeEnDialogo(@StringRes mensaje: Int?) {
    if (mensaje != null) {
        Text(
            stringResource(mensaje),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("error-dialogo"),
        )
    }
}

@Composable
internal fun TextoOProgreso(enProgreso: Boolean, @StringRes texto: Int) {
    if (enProgreso) {
        val desc = stringResource(R.string.consentimiento_refrescando)
        CircularProgressIndicator(
            modifier = Modifier
                .size(20.dp)
                .semantics { contentDescription = desc },
            strokeWidth = 2.dp,
        )
    } else {
        Text(stringResource(texto))
    }
}
