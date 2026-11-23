package co.solventa.feature.siniestros.reporte

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.feature.siniestros.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Campo de solo lectura que abre el calendario; el calendario no deja elegir días posteriores a
 * [EstadoReporte.hoy]. El error se muestra al cerrar el calendario o al intentar avanzar.
 */
@Composable
internal fun SelectorFecha(estado: EstadoReporte, viewModel: ReporteViewModel) {
    var abierto by rememberSaveable { mutableStateOf(false) }
    val toques = remember { MutableInteractionSource() }
    LaunchedEffect(toques) {
        toques.interactions.collect { if (it is PressInteraction.Release) abierto = true }
    }
    val error = estado.error(Campo.FECHA)?.let { stringResource(it.mensaje) }
    val elegir = stringResource(R.string.elegir_fecha)
    OutlinedTextField(
        value = estado.datos.fecha?.let(::formatoFecha).orEmpty(),
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        label = { Text(stringResource(R.string.campo_fecha)) },
        isError = error != null,
        supportingText = { Text(error ?: stringResource(R.string.ayuda_fecha)) },
        trailingIcon = {
            IconButton(
                onClick = { abierto = true },
                modifier = Modifier.size(SolventaSpacing.touchTarget).testTag("abrir-fecha"),
            ) { Icon(Icons.Default.DateRange, contentDescription = elegir) }
        },
        interactionSource = toques,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("campo-fecha")
            .semantics { if (error != null) error(error) },
    )
    if (abierto) {
        DialogoFecha(
            inicial = estado.datos.fecha,
            hoy = estado.hoy,
            onElegir = { fecha -> viewModel.actualizar(Campo.FECHA) { it.copy(fecha = fecha) } },
            onCerrar = {
                abierto = false
                viewModel.salirDe(Campo.FECHA)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogoFecha(inicial: LocalDate?, hoy: LocalDate, onElegir: (LocalDate) -> Unit, onCerrar: () -> Unit) {
    val seleccionables = remember(hoy) { hastaHoy(hoy) }
    val calendario = rememberDatePickerState(
        initialSelectedDateMillis = inicial?.enMilisUtc(),
        selectableDates = seleccionables,
    )
    DatePickerDialog(
        onDismissRequest = onCerrar,
        confirmButton = {
            TextButton(
                onClick = {
                    calendario.selectedDateMillis?.let { onElegir(fechaDeMilisUtc(it)) }
                    onCerrar()
                },
                enabled = calendario.selectedDateMillis != null,
                modifier = Modifier.heightIn(min = SolventaSpacing.touchTarget).testTag("aceptar-fecha"),
            ) { Text(stringResource(R.string.boton_aceptar)) }
        },
        dismissButton = {
            TextButton(onClick = onCerrar, modifier = Modifier.heightIn(min = SolventaSpacing.touchTarget)) {
                Text(stringResource(R.string.boton_cancelar))
            }
        },
    ) { DatePicker(state = calendario) }
}

/** Días que deja elegir el calendario: hasta [hoy], incluido. */
@OptIn(ExperimentalMaterial3Api::class)
internal fun hastaHoy(hoy: LocalDate): SelectableDates {
    val limite = hoy.enMilisUtc()
    return object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= limite

        override fun isSelectableYear(year: Int) = year <= hoy.year
    }
}

// El DatePicker de Material trabaja con la medianoche UTC de cada día.
private fun LocalDate.enMilisUtc(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun fechaDeMilisUtc(milis: Long): LocalDate = Instant.ofEpochMilli(milis).atZone(ZoneOffset.UTC).toLocalDate()
