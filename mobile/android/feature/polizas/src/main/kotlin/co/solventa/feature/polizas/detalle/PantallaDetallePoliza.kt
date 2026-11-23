package co.solventa.feature.polizas.detalle

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.core.network.PolizaDetalle
import co.solventa.feature.polizas.DependenciasPolizas
import co.solventa.feature.polizas.R
import co.solventa.feature.polizas.comun.BotonPrincipal
import co.solventa.feature.polizas.comun.BotonSecundario
import co.solventa.feature.polizas.comun.Cargando
import co.solventa.feature.polizas.comun.ErrorConsulta
import co.solventa.feature.polizas.comun.InsigniaEstado
import co.solventa.feature.polizas.comun.PlantillaPolizas
import co.solventa.feature.polizas.comun.formatoDiaDe
import co.solventa.feature.polizas.comun.nombrePoliza
import co.solventa.feature.polizas.comun.textoFallo
import co.solventa.feature.polizas.comun.textoPrima
import co.solventa.feature.polizas.comun.textoVigencia

/**
 * Detalle de la póliza con su ViewModel. El número llega por la ruta `policies/detalle/{numero}` y la
 * navegación lo deja en el SavedStateHandle. El PDF descargado se abre con [DependenciasPolizas.visor] o,
 * sin él, con el visor del sistema.
 *
 * @param onVolver regresa a «Mis pólizas».
 */
@Composable
fun RutaDetallePoliza(
    dependencias: DependenciasPolizas,
    onVolver: () -> Unit,
    onSesionVencida: () -> Unit = {},
) {
    val viewModel = viewModel {
        DetallePolizaViewModel(dependencias.api, createSavedStateHandle(), dependencias.documentos, dependencias.io)
    }
    val contexto = LocalContext.current
    val visor = remember(contexto) { dependencias.visor ?: VisorPdfAndroid(contexto) }
    val estado = viewModel.estado
    LaunchedEffect(estado.pdf) {
        estado.pdf?.let { viewModel.pdfAbierto(visor.abrir(it)) }
    }
    LaunchedEffect(estado.sesionVencida) {
        if (estado.sesionVencida) {
            viewModel.sesionAtendida()
            onSesionVencida()
        }
    }
    BackHandler(onBack = onVolver)
    PantallaDetallePoliza(
        estado = estado,
        onVolver = onVolver,
        onReintentar = viewModel::consultar,
        onDescargar = viewModel::descargar,
        onAvisoMostrado = viewModel::avisoMostrado,
    )
}

/** Sin estado propio: las pruebas la dibujan con cualquier [estado]. */
@Composable
fun PantallaDetallePoliza(
    estado: EstadoDetallePoliza,
    onVolver: () -> Unit,
    onReintentar: () -> Unit,
    onDescargar: () -> Unit,
    onAvisoMostrado: () -> Unit,
) {
    val avisos = remember { SnackbarHostState() }
    val aviso = estado.aviso
    val textoAviso = aviso?.let { stringResource(it.texto()) }
    val reintentar = stringResource(R.string.reintentar)
    LaunchedEffect(aviso) {
        if (aviso != null && textoAviso != null) {
            val resultado = avisos.showSnackbar(
                textoAviso,
                actionLabel = reintentar.takeIf { aviso.reintentable },
                duration = SnackbarDuration.Long,
            )
            // «Reintentar» vuelve a descargar, que también limpia el aviso.
            if (resultado == SnackbarResult.ActionPerformed) onDescargar() else onAvisoMostrado()
        }
    }
    Box(Modifier.fillMaxSize()) {
        PlantillaPolizas(
            titulo = stringResource(R.string.detalle_titulo),
            onVolver = onVolver,
            modifier = Modifier.testTag("pantalla-detalle-poliza"),
        ) {
            val detalle = estado.detalle
            val error = estado.error
            when {
                estado.noEncontrada -> NoEncontrada(onVolver)
                detalle == null && error != null ->
                    ErrorConsulta(textoFallo(error, R.string.detalle_error), onReintentar)
                detalle == null -> Cargando(stringResource(R.string.detalle_cargando))
                else -> {
                    if (error != null) ErrorConsulta(textoFallo(error, R.string.detalle_error), onReintentar)
                    Encabezado(detalle)
                    InformacionGeneral(detalle)
                    InformacionFinanciera(detalle)
                    Coberturas(detalle)
                    Documento(estado, onDescargar)
                }
            }
        }
        SnackbarHost(avisos, Modifier.align(Alignment.BottomCenter).padding(SolventaSpacing.md))
    }
}

@Composable
private fun Encabezado(detalle: PolizaDetalle) {
    Column(
        verticalArrangement = Arrangement.spacedBy(SolventaSpacing.xs),
        modifier = Modifier.testTag("encabezado"),
    ) {
        Text(
            nombrePoliza(detalle.resumen),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
        ) {
            Text(detalle.numero, style = MaterialTheme.typography.bodyLarge)
            InsigniaEstado(detalle.estado)
        }
    }
}

@Composable
private fun InformacionGeneral(detalle: PolizaDetalle) {
    Seccion(stringResource(R.string.detalle_informacion_general), "informacion-general") {
        Fila(stringResource(R.string.detalle_vigencia), textoVigencia(detalle.resumen), "vigencia")
        Fila(stringResource(R.string.detalle_prima), textoPrima(detalle.resumen), "prima")
        // Sin fecha de emisión (pólizas de prueba) la fila no aparece.
        detalle.emitidaEn?.let { Fila(stringResource(R.string.detalle_emitida), formatoDiaDe(it), "emitida") }
    }
}

@Composable
private fun InformacionFinanciera(detalle: PolizaDetalle) {
    val noDisponible = stringResource(R.string.no_disponible)
    Seccion(stringResource(R.string.detalle_informacion_financiera), "informacion-financiera") {
        Fila(
            stringResource(R.string.detalle_valor_asegurado),
            detalle.valorAsegurado?.takeIf(String::isNotBlank) ?: noDisponible,
            "valor-asegurado",
        )
        Fila(
            stringResource(R.string.detalle_deducible),
            detalle.deducible?.takeIf(String::isNotBlank) ?: noDisponible,
            "deducible",
        )
    }
}

@Composable
private fun Coberturas(detalle: PolizaDetalle) {
    Seccion(stringResource(R.string.detalle_coberturas), "coberturas") {
        if (detalle.coberturas.isEmpty()) {
            Text(
                stringResource(R.string.detalle_sin_coberturas),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("sin-coberturas"),
            )
        }
        detalle.coberturas.forEachIndexed { indice, cobertura ->
            Fila(cobertura.nombre, cobertura.limite, "cobertura-$indice")
        }
    }
}

/** «Descargar PDF» si la póliza tiene documento; si no, se explica que no está disponible. */
@Composable
private fun Documento(estado: EstadoDetallePoliza, onDescargar: () -> Unit) {
    if (estado.ofreceDescarga) {
        BotonPrincipal(
            stringResource(R.string.detalle_descargar),
            onClick = onDescargar,
            cargando = estado.descargando,
            textoCargando = stringResource(R.string.detalle_descargando),
            modifier = Modifier.testTag("descargar-pdf"),
        )
    } else {
        val texto = stringResource(R.string.detalle_sin_documento)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("sin-documento")
                .clearAndSetSemantics { contentDescription = texto },
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(texto, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 404: no existe o es de otro cliente; no se dice cuál. */
@Composable
private fun NoEncontrada(onVolver: () -> Unit) {
    Text(
        stringResource(R.string.no_encontrada_titulo),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag("poliza-no-encontrada").semantics { heading() },
    )
    Text(stringResource(R.string.no_encontrada_texto), style = MaterialTheme.typography.bodyLarge)
    BotonSecundario(
        stringResource(R.string.volver_lista),
        onClick = onVolver,
        modifier = Modifier.testTag("volver-lista"),
    )
}

private fun AvisoPoliza.texto() = when (this) {
    AvisoPoliza.DOCUMENTO_NO_DISPONIBLE -> R.string.aviso_documento_no_disponible
    AvisoPoliza.DESCARGA_SIN_RED -> R.string.aviso_descarga_sin_red
    AvisoPoliza.DESCARGA_FALLIDA -> R.string.aviso_descarga_fallida
    AvisoPoliza.SIN_VISOR -> R.string.aviso_sin_visor
}
