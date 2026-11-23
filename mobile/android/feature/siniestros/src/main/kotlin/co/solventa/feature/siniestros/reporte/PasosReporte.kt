package co.solventa.feature.siniestros.reporte

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.core.network.PolizaResumen
import co.solventa.core.network.TipoEventoSiniestro
import co.solventa.feature.siniestros.R
import co.solventa.feature.siniestros.comun.BotonTexto
import co.solventa.feature.siniestros.comun.CampoTexto
import co.solventa.feature.siniestros.comun.Mensaje
import co.solventa.feature.siniestros.evidencia.FormatoEvidencia
import co.solventa.feature.siniestros.evidencia.etiqueta

// ── Paso 1 · Póliza ───────────────────────────────────────────────────────────────────────────

@Composable
internal fun PasoPoliza(estado: EstadoReporte, viewModel: ReporteViewModel) {
    Text(stringResource(R.string.poliza_instrucciones), style = MaterialTheme.typography.bodyMedium)
    estado.polizasDel?.let { fecha ->
        Text(
            stringResource(R.string.polizas_actualizacion, formatoFechaHora(fecha)),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("polizas-guardadas"),
        )
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(SolventaSpacing.sm)) {
        estado.polizas.forEach { poliza ->
            FilaPoliza(
                poliza = poliza,
                elegida = poliza.numero == estado.datos.numeroPoliza,
                onElegir = { viewModel.actualizar(Campo.POLIZA) { it.copy(numeroPoliza = poliza.numero) } },
            )
        }
    }
    estado.error(Campo.POLIZA)?.let { Mensaje(stringResource(it.mensaje)) }
    if (estado.polizasDel != null) Mensaje(stringResource(R.string.polizas_sin_confirmar), esError = false)
}

@Composable
private fun FilaPoliza(poliza: PolizaResumen, elegida: Boolean, onElegir: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SolventaSpacing.touchTarget)
            .testTag("poliza-${poliza.numero}")
            .selectable(selected = elegida, role = Role.RadioButton, onClick = onElegir),
    ) {
        RadioButton(selected = elegida, onClick = null)
        Column(Modifier.padding(vertical = SolventaSpacing.xs)) {
            Text(nombreProducto(poliza), style = MaterialTheme.typography.titleSmall)
            Text(poliza.numero, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.poliza_vigencia, formatoFecha(poliza.inicio), formatoFecha(poliza.fin)),
                style = MaterialTheme.typography.bodySmall,
            )
            // El estado va en texto, no solo en color.
            Text(stringResource(poliza.estado.etiqueta()), style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ── Paso 2 · Información ──────────────────────────────────────────────────────────────────────

@Composable
internal fun PasoInformacion(estado: EstadoReporte, viewModel: ReporteViewModel) {
    Text(stringResource(R.string.campo_tipo), style = MaterialTheme.typography.titleSmall)
    Column(Modifier.selectableGroup().testTag("tipos")) {
        TipoEventoSiniestro.entries.forEach { tipo ->
            OpcionRadio(
                texto = stringResource(tipo.etiqueta()),
                elegida = estado.datos.tipoEvento == tipo,
                onElegir = { viewModel.actualizar(Campo.TIPO) { it.copy(tipoEvento = tipo) } },
                modifier = Modifier.testTag("tipo-${tipo.name.lowercase()}"),
            )
        }
    }
    estado.error(Campo.TIPO)?.let { Mensaje(stringResource(it.mensaje)) }
    SelectorFecha(estado, viewModel)
    val descripcion = estado.datos.descripcion
    CampoTexto(
        valor = descripcion,
        onCambio = { valor ->
            viewModel.actualizar(Campo.DESCRIPCION) { it.copy(descripcion = valor.take(DESCRIPCION.last)) }
        },
        etiqueta = stringResource(R.string.campo_descripcion),
        error = estado.error(Campo.DESCRIPCION)?.let { stringResource(it.mensaje) },
        ayuda = stringResource(R.string.ayuda_descripcion, descripcion.trim().length, DESCRIPCION.last),
        teclado = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        lineas = LINEAS_DESCRIPCION,
        onSalir = { viewModel.salirDe(Campo.DESCRIPCION) },
        modifier = Modifier.testTag("campo-descripcion"),
    )
    CampoTexto(
        valor = estado.datos.monto,
        // Sin filtrar: un texto con decimales se marca como error en vez de convertirse en otro monto.
        onCambio = { valor -> viewModel.actualizar(Campo.MONTO) { it.copy(monto = valor) } },
        etiqueta = stringResource(R.string.campo_monto),
        error = estado.error(Campo.MONTO)?.let { stringResource(it.mensaje) },
        ayuda = stringResource(R.string.ayuda_monto),
        teclado = KeyboardOptions(keyboardType = KeyboardType.Number),
        prefijo = stringResource(R.string.prefijo_pesos),
        onSalir = { viewModel.salirDe(Campo.MONTO) },
        modifier = Modifier.testTag("campo-monto"),
    )
}

@Composable
private fun OpcionRadio(texto: String, elegida: Boolean, onElegir: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SolventaSpacing.touchTarget)
            .selectable(selected = elegida, role = Role.RadioButton, onClick = onElegir),
    ) {
        RadioButton(selected = elegida, onClick = null)
        Text(texto)
    }
}

// ── Paso 3 · Revisión ─────────────────────────────────────────────────────────────────────────

@Composable
internal fun PasoRevision(estado: EstadoReporte, viewModel: ReporteViewModel) {
    val datos = estado.datos
    Text(stringResource(R.string.revision_instrucciones), style = MaterialTheme.typography.bodyMedium)
    val poliza = estado.polizaElegida
    Resumen(
        titulo = R.string.revision_poliza,
        valor = poliza?.let { "${nombreProducto(it)} · ${it.numero}" } ?: datos.numeroPoliza.orEmpty(),
        paso = PasoReporte.POLIZA,
        viewModel = viewModel,
        habilitado = estado.puedeCorregir,
    )
    val informacion = listOf(
        R.string.revision_tipo to datos.tipoEvento?.let { stringResource(it.etiqueta()) }.orEmpty(),
        R.string.revision_fecha to datos.fecha?.let(::formatoFecha).orEmpty(),
        R.string.revision_descripcion to datos.descripcion.trim(),
        R.string.revision_monto to (
            ValidadorReporte.montoEnPesos(datos.monto)
                ?.let { stringResource(R.string.prefijo_pesos) + formatoPesos(it) }
                ?: stringResource(R.string.revision_sin_monto)
            ),
    )
    informacion.forEach { (titulo, valor) ->
        Resumen(titulo, valor, PasoReporte.INFORMACION, viewModel, habilitado = estado.puedeCorregir)
    }
    val previos = estado.adjuntosBorrador.map { Triple(it.formato, it.tamano, it.id) }
    val nuevos = viewModel.evidencia.estado.listos.map { Triple(it.formato, it.tamano, it.id) }
    Resumen(
        titulo = R.string.revision_evidencia,
        valor = resumenEvidencia(previos + nuevos),
        paso = PasoReporte.EVIDENCIA,
        viewModel = viewModel,
        habilitado = estado.puedeCorregir,
    )
    if (estado.sinConexion) {
        Mensaje(
            stringResource(R.string.revision_sin_conexion),
            esError = false,
            modifier = Modifier.testTag("revision-sin-conexion"),
        )
    }
}

/** Lo que irá con el reporte, un archivo por línea. */
@Composable
private fun resumenEvidencia(adjuntos: List<Triple<FormatoEvidencia?, Long, String>>): String {
    if (adjuntos.isEmpty()) return stringResource(R.string.revision_sin_evidencia)
    return adjuntos.mapIndexed { indice, (formato, tamano, _) ->
        "${stringResource(formato.etiqueta(), indice + 1)} · ${formatoTamano(tamano)}"
    }.joinToString("\n")
}

@Composable
private fun Resumen(
    @StringRes titulo: Int,
    valor: String,
    paso: PasoReporte,
    viewModel: ReporteViewModel,
    habilitado: Boolean,
) {
    val nombre = stringResource(titulo)
    val cambiar = stringResource(R.string.cambiar_paso, nombre.lowercase())
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).padding(vertical = SolventaSpacing.sm)) {
                Text(nombre, style = MaterialTheme.typography.labelLarge)
                Text(valor, style = MaterialTheme.typography.bodyLarge)
            }
            BotonTexto(
                texto = stringResource(R.string.boton_cambiar),
                onClick = { viewModel.irA(paso) },
                habilitado = habilitado,
                modifier = Modifier.semantics { contentDescription = cambiar },
            )
        }
    }
}

private const val LINEAS_DESCRIPCION = 4
