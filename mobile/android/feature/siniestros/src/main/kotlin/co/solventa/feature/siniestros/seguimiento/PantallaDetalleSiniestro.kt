package co.solventa.feature.siniestros.seguimiento

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.feature.siniestros.DependenciasSiniestros
import co.solventa.feature.siniestros.R
import co.solventa.feature.siniestros.comun.BotonPrincipal
import co.solventa.feature.siniestros.comun.Cargando
import co.solventa.feature.siniestros.evidencia.AccionesEvidencia
import co.solventa.feature.siniestros.evidencia.FuentesEvidencia
import co.solventa.feature.siniestros.evidencia.PantallaCamara
import co.solventa.feature.siniestros.evidencia.rememberAccionesEvidencia

/**
 * Detalle del caso con su ViewModel. El `id` llega por la ruta `home/siniestros/detalle/{id}` (también
 * desde el push de MOB-F06) y la navegación lo deja en el SavedStateHandle.
 *
 * @param onVolver regresa a «Mis siniestros».
 */
@Composable
fun RutaDetalleSiniestro(
    dependencias: DependenciasSiniestros,
    onVolver: () -> Unit,
    onSesionVencida: () -> Unit = {},
) {
    val viewModel = viewModel {
        DetalleSiniestroViewModel(
            dependencias.api,
            createSavedStateHandle(),
            FuentesEvidencia(dependencias.almacen, dependencias.ubicacion),
        )
    }
    var camara by rememberSaveable { mutableStateOf(false) }
    val acciones = rememberAccionesEvidencia(viewModel.evidencia, onAbrirCamara = { camara = true })
    if (camara) {
        PantallaCamara(
            destino = dependencias.almacen::nuevaCaptura,
            onCapturada = { uri ->
                camara = false
                viewModel.evidencia.fotoTomada(uri)
            },
            onCancelar = { camara = false },
            onError = {
                camara = false
                viewModel.evidencia.camaraFallo()
            },
        )
    } else {
        PantallaDetalleSiniestro(viewModel, onVolver, onSesionVencida, acciones)
    }
}

/** Sin estado propio: lo que dibuja viene de [viewModel], que las pruebas arman con dobles. */
@Composable
fun PantallaDetalleSiniestro(
    viewModel: DetalleSiniestroViewModel,
    onVolver: () -> Unit,
    onSesionVencida: () -> Unit = {},
    acciones: AccionesEvidencia = AccionesEvidencia(),
) {
    val estado = viewModel.estado
    LaunchedEffect(estado.sesionVencida) {
        if (estado.sesionVencida) {
            viewModel.sesionAtendida()
            onSesionVencida()
        }
    }
    val avisos = remember { SnackbarHostState() }
    val textoAviso = estado.aviso?.let { stringResource(it.texto()) }
    LaunchedEffect(estado.aviso) {
        if (textoAviso != null) {
            avisos.showSnackbar(textoAviso)
            viewModel.avisoMostrado()
        }
    }
    BackHandler(onBack = onVolver)
    Box(Modifier.fillMaxSize()) {
        PlantillaSeguimiento(
            titulo = stringResource(R.string.seguimiento_detalle_titulo),
            actualizando = estado.cargando && estado.detalle != null,
            onActualizar = viewModel::actualizar,
            onVolver = onVolver,
            modifier = Modifier.testTag("pantalla-detalle-siniestro"),
        ) {
            ContenidoDetalle(estado, viewModel, onVolver)
        }
        SnackbarHost(avisos, Modifier.align(Alignment.BottomCenter).padding(SolventaSpacing.md))
    }
    val requerimiento = estado.requerimientoRespondiendo
    if (requerimiento != null) {
        HojaRespuesta(requerimiento, viewModel, acciones)
    }
}

@Composable
private fun ContenidoDetalle(estado: EstadoDetalle, viewModel: DetalleSiniestroViewModel, onVolver: () -> Unit) {
    val detalle = estado.detalle
    val error = estado.error
    when {
        estado.noEncontrado -> NoEncontrado(onVolver)
        detalle == null && error != null ->
            ErrorConsulta(textoFallo(error, R.string.seguimiento_error_caso), viewModel::actualizar)
        detalle == null -> Cargando(stringResource(R.string.seguimiento_cargando_caso))
        else -> {
            if (estado.respuestaSinConsulta) {
                ErrorConsulta(stringResource(R.string.respuesta_sin_consulta), viewModel::actualizar)
            } else if (error != null) {
                ErrorConsulta(textoFallo(error, R.string.seguimiento_error_caso), viewModel::actualizar)
            }
            Encabezado(detalle)
            Resumen(detalle)
            LineaDeTiempo(detalle.historial)
            Requerimientos(detalle.requerimientos, onResponder = viewModel::responder)
        }
    }
}

/** 404: no existe o es de otro cliente; no se dice cuál. */
@Composable
private fun NoEncontrado(onVolver: () -> Unit) {
    Text(
        stringResource(R.string.seguimiento_no_encontrado_titulo),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag("siniestro-no-encontrado").semantics { heading() },
    )
    Text(stringResource(R.string.seguimiento_no_encontrado_texto), style = MaterialTheme.typography.bodyLarge)
    BotonPrincipal(
        stringResource(R.string.seguimiento_volver_lista),
        onClick = onVolver,
        modifier = Modifier.testTag("volver-lista"),
    )
}

private fun AvisoSeguimiento.texto() = when (this) {
    AvisoSeguimiento.RESPUESTA_ENVIADA -> R.string.respuesta_enviada
    AvisoSeguimiento.YA_ATENDIDO -> R.string.aviso_ya_atendido
    AvisoSeguimiento.REQUERIMIENTO_NO_ENCONTRADO -> R.string.aviso_requerimiento_no_encontrado
    AvisoSeguimiento.CASO_CERRADO -> R.string.aviso_caso_cerrado
}
