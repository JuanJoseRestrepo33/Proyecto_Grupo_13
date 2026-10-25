package co.solventa.feature.consentimiento

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import co.solventa.core.network.BffApi
import co.solventa.core.network.CodigoFinalidad
import co.solventa.core.network.Consentimiento
import co.solventa.core.network.EstadoConsentimiento
import co.solventa.core.network.FinalidadConsentimiento
import co.solventa.core.designsystem.SolventaSpacing

// ── Puntos de entrada ─────────────────────────────────────────────────────────────────────────

@Composable
fun RutaConsentimiento(api: BffApi, onVolver: () -> Unit) {
    val vm = viewModel { ConsentimientoViewModel(api) }
    PantallaConsentimiento(vm, onVolver)
}

// ── Pantalla principal ────────────────────────────────────────────────────────────────────────

/**
 * Refresca en ON_RESUME usando [LifecycleEventObserver]. LaunchedEffect(Unit) no es suficiente
 * porque solo corre una vez por composición; ON_RESUME dispara cada vez que la pantalla vuelve
 * al frente (desde otra app, o al volver desde Perfil).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaConsentimiento(viewModel: ConsentimientoViewModel, onVolver: () -> Unit) {
    val estado = viewModel.estado
    val lifecycleOwner = LocalLifecycleOwner.current

    // Refresco en ON_RESUME: cada vez que la pantalla vuelve al primer plano.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.cargar()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("pantalla-consentimiento"),
    ) {
        // Barra superior con título y botón volver
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SolventaSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onVolver, modifier = Modifier.heightIn(min = SolventaSpacing.touchTarget)) {
                Text(stringResource(R.string.consentimiento_titulo))
            }
        }

        // Mensaje de error de mutación
        if (estado.mensajeError != null) {
            Text(
                text = stringResource(estado.mensajeError),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(horizontal = SolventaSpacing.md)
                    .testTag("mensaje-error"),
            )
        }

        // Contenido principal con pull-to-refresh
        PullToRefreshBox(
            isRefreshing = estado.cargando,
            onRefresh = viewModel::cargar,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                estado.cargando && estado.finalidades.isEmpty() -> {
                    // Carga inicial
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val desc = stringResource(R.string.consentimiento_refrescando)
                        CircularProgressIndicator(
                            modifier = Modifier.semantics { contentDescription = desc },
                        )
                    }
                }
                estado.errorCarga -> {
                    // Error de carga: no mostrar lista vacía
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(SolventaSpacing.md)
                            .testTag("error-carga"),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.consentimiento_error_carga),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.size(SolventaSpacing.md))
                        Button(
                            onClick = viewModel::cargar,
                            modifier = Modifier.heightIn(min = SolventaSpacing.touchTarget),
                        ) {
                            Text(stringResource(R.string.consentimiento_reintentar))
                        }
                    }
                }
                else -> {
                    ListaFinalidades(
                        finalidades = estado.finalidades,
                        mutando = estado.mutando,
                        renovando = estado.renovando,
                        onOtorgar = viewModel::abrirOtorgar,
                        onRevocar = { id, fin -> viewModel.abrirRevocar(id, fin) },
                        onRenovar = viewModel::renovar,
                    )
                }
            }
        }
    }

    // ── Diálogos ─────────────────────────────────────────────────────────────────────────────

    if (estado.otorgando != null) {
        DialogoOtorgar(
            finalidad = estado.otorgando,
            entidadesSeleccionadas = estado.entidadesSeleccionadas,
            versionAceptada = estado.versionAceptada,
            puedeOtorgar = estado.puedeOtorgar,
            mutando = estado.mutando,
            onToggleEntidad = viewModel::toggleEntidad,
            onToggleVersion = viewModel::toggleVersionAceptada,
            onOtorgar = viewModel::otorgar,
            onCancelar = viewModel::cerrarOtorgar,
        )
    }

    if (estado.revocando != null) {
        DialogoRevocar(
            finalidad = estado.finalidadRevocando,
            mutando = estado.mutando,
            onConfirmar = viewModel::revocar,
            onCancelar = viewModel::cerrarRevocar,
        )
    }
}

// ── Lista de finalidades ──────────────────────────────────────────────────────────────────────

@Composable
private fun ListaFinalidades(
    finalidades: List<FinalidadUi>,
    mutando: Boolean,
    renovando: String?,
    onOtorgar: (FinalidadConsentimiento) -> Unit,
    onRevocar: (String, CodigoFinalidad) -> Unit,
    onRenovar: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(SolventaSpacing.md),
        verticalArrangement = Arrangement.spacedBy(SolventaSpacing.md),
    ) {
        items(finalidades, key = { it.finalidad.codigo.name }) { item ->
            TarjetaFinalidad(
                item = item,
                mutando = mutando,
                renovandoId = renovando,
                onOtorgar = onOtorgar,
                onRevocar = onRevocar,
                onRenovar = onRenovar,
            )
        }
    }
}

// ── Tarjeta de finalidad ──────────────────────────────────────────────────────────────────────

@Composable
private fun TarjetaFinalidad(
    item: FinalidadUi,
    mutando: Boolean,
    renovandoId: String?,
    onOtorgar: (FinalidadConsentimiento) -> Unit,
    onRevocar: (String, CodigoFinalidad) -> Unit,
    onRenovar: (String) -> Unit,
) {
    val f = item.finalidad
    val c = item.consentimiento
    val esRenovando = c != null && renovandoId == c.id

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tarjeta-${f.codigo.name.lowercase()}"),
    ) {
        Column(
            modifier = Modifier.padding(SolventaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
        ) {
            // Título y estado
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(f.nombre, style = MaterialTheme.typography.titleMedium)
                if (c != null) EstadoChip(c)
            }

            Text(f.descripcion, style = MaterialTheme.typography.bodyMedium)

            // Entidades autorizadas (si hay consentimiento)
            if (c != null) {
                Text(
                    c.entidadesOrigen.joinToString { it.nombre },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.otorgado_en, c.otorgadoEn.take(10)),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (c.estado == EstadoConsentimiento.VIGENTE) {
                    Text(
                        stringResource(R.string.vence_en, c.venceEn.take(10)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (c.revocadoEn != null) {
                    Text(
                        stringResource(R.string.revocado_en, c.revocadoEn.take(10)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // Marca de por vencer
                if (c.porVencer && c.diasParaVencer != null) {
                    val textoPorVencer = if (c.diasParaVencer == 0) {
                        stringResource(R.string.por_vencer_hoy)
                    } else {
                        stringResource(R.string.por_vencer_dias, c.diasParaVencer)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.testTag("por-vencer-${f.codigo.name.lowercase()}"),
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = stringResource(R.string.cd_por_vencer),
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(textoPorVencer, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                // Sin consentimiento: el texto lo genera la app, no la API
                Text(
                    stringResource(R.string.consentimiento_sin_registro),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("sin-consentimiento-${f.codigo.name.lowercase()}"),
                )
            }

            // Acciones
            AccionesFinalidad(
                finalidad = f,
                consentimiento = c,
                mutando = mutando,
                esRenovando = esRenovando,
                onOtorgar = onOtorgar,
                onRevocar = onRevocar,
                onRenovar = onRenovar,
            )
        }
    }
}

@Composable
private fun EstadoChip(consentimiento: Consentimiento) {
    val (texto, cd) = when (consentimiento.estado) {
        EstadoConsentimiento.VIGENTE -> Pair(
            stringResource(R.string.estado_vigente),
            stringResource(R.string.cd_vigente),
        )
        EstadoConsentimiento.REVOCADO -> Pair(
            stringResource(R.string.estado_revocado),
            stringResource(R.string.cd_revocado),
        )
        EstadoConsentimiento.VENCIDO -> Pair(
            stringResource(R.string.estado_vencido),
            stringResource(R.string.cd_vencido),
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.semantics { contentDescription = cd },
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null, // cd ya está en el Row
            modifier = Modifier.size(16.dp),
        )
        Text(texto, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AccionesFinalidad(
    finalidad: FinalidadConsentimiento,
    consentimiento: Consentimiento?,
    mutando: Boolean,
    esRenovando: Boolean,
    onOtorgar: (FinalidadConsentimiento) -> Unit,
    onRevocar: (String, CodigoFinalidad) -> Unit,
    onRenovar: (String) -> Unit,
) {
    val habilitado = !mutando

    when {
        // Sin consentimiento o revocado → otorgar nuevo
        consentimiento == null || consentimiento.estado == EstadoConsentimiento.REVOCADO -> {
            Button(
                onClick = { onOtorgar(finalidad) },
                enabled = habilitado,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SolventaSpacing.touchTarget)
                    .testTag("boton-otorgar-${finalidad.codigo.name.lowercase()}"),
            ) {
                Text(stringResource(R.string.boton_otorgar))
            }
        }
        // Vigente → revocar y, si está por vencer, también renovar
        consentimiento.estado == EstadoConsentimiento.VIGENTE -> {
            if (consentimiento.porVencer) {
                Button(
                    onClick = { onRenovar(consentimiento.id) },
                    enabled = habilitado,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = SolventaSpacing.touchTarget)
                        .testTag("boton-renovar-${finalidad.codigo.name.lowercase()}"),
                ) {
                    if (esRenovando) {
                        val desc = stringResource(R.string.consentimiento_refrescando)
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .semantics { contentDescription = desc },
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.boton_renovar))
                    }
                }
            }
            OutlinedButton(
                onClick = { onRevocar(consentimiento.id, finalidad.codigo) },
                enabled = habilitado,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SolventaSpacing.touchTarget)
                    .testTag("boton-revocar-${finalidad.codigo.name.lowercase()}"),
            ) {
                Text(stringResource(R.string.boton_revocar))
            }
        }
        // Vencido → renovar
        consentimiento.estado == EstadoConsentimiento.VENCIDO -> {
            Button(
                onClick = { onRenovar(consentimiento.id) },
                enabled = habilitado,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SolventaSpacing.touchTarget)
                    .testTag("boton-renovar-${finalidad.codigo.name.lowercase()}"),
            ) {
                if (esRenovando) {
                    val desc = stringResource(R.string.consentimiento_refrescando)
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .semantics { contentDescription = desc },
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.boton_renovar))
                }
            }
        }
    }
}

// ── Diálogo Otorgar ───────────────────────────────────────────────────────────────────────────

@Composable
private fun DialogoOtorgar(
    finalidad: FinalidadConsentimiento,
    entidadesSeleccionadas: Set<String>,
    versionAceptada: Boolean,
    puedeOtorgar: Boolean,
    mutando: Boolean,
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
                // Alcance
                Text(
                    stringResource(R.string.otorgar_alcance_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(finalidad.alcance.joinToString(", "), style = MaterialTheme.typography.bodySmall)

                // Vigencia
                Text(
                    stringResource(R.string.otorgar_vigencia_label, finalidad.vigenciaMeses),
                    style = MaterialTheme.typography.bodySmall,
                )

                // Entidades: checkboxes sin marcar inicialmente
                Text(
                    stringResource(R.string.otorgar_entidades_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                finalidad.entidadesOrigen.forEach { entidad ->
                    val seleccionada = entidad.id in entidadesSeleccionadas
                    val cdEntidad = if (seleccionada) {
                        stringResource(R.string.cd_entidad_seleccionada, entidad.nombre)
                    } else {
                        stringResource(R.string.cd_entidad_no_seleccionada, entidad.nombre)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = SolventaSpacing.touchTarget)
                            .semantics { contentDescription = cdEntidad }
                            .testTag("entidad-${entidad.id}"),
                    ) {
                        Checkbox(
                            checked = seleccionada,
                            onCheckedChange = { onToggleEntidad(entidad.id) },
                            enabled = !mutando,
                        )
                        Text(entidad.nombre, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // Aceptación de versión: desmarcada inicialmente
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = SolventaSpacing.touchTarget)
                        .testTag("check-version"),
                ) {
                    Checkbox(
                        checked = versionAceptada,
                        onCheckedChange = { onToggleVersion() },
                        enabled = !mutando,
                        modifier = Modifier.semantics {
                            contentDescription = stringResource(R.string.otorgar_version_cd)
                        },
                    )
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
                if (mutando) {
                    val desc = stringResource(R.string.consentimiento_refrescando)
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .semantics { contentDescription = desc },
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.boton_otorgar))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancelar,
                enabled = !mutando,
                modifier = Modifier
                    .heightIn(min = SolventaSpacing.touchTarget)
                    .testTag("boton-cancelar-otorgar"),
            ) {
                Text(stringResource(R.string.boton_cancelar))
            }
        },
    )
}

// ── Diálogo Revocar ───────────────────────────────────────────────────────────────────────────

@Composable
private fun DialogoRevocar(
    finalidad: CodigoFinalidad?,
    mutando: Boolean,
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!mutando) onCancelar() },
        title = { Text(stringResource(R.string.revocar_titulo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SolventaSpacing.sm)) {
                Text(
                    stringResource(R.string.revocar_pregunta),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Advertencia específica para cotización
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
                if (mutando) {
                    val desc = stringResource(R.string.consentimiento_refrescando)
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .semantics { contentDescription = desc },
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.boton_confirmar))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancelar,
                enabled = !mutando,
                modifier = Modifier
                    .heightIn(min = SolventaSpacing.touchTarget)
                    .testTag("boton-cancelar-revocar"),
            ) {
                Text(stringResource(R.string.boton_cancelar))
            }
        },
    )
}
