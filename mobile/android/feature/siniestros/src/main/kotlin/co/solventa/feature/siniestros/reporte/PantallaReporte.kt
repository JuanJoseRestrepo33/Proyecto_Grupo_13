package co.solventa.feature.siniestros.reporte

import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import co.solventa.feature.siniestros.DependenciasSiniestros
import co.solventa.feature.siniestros.R
import co.solventa.feature.siniestros.comun.BotonPrincipal
import co.solventa.feature.siniestros.comun.BotonSecundario
import co.solventa.feature.siniestros.comun.Cargando
import co.solventa.feature.siniestros.comun.Mensaje
import co.solventa.feature.siniestros.comun.PlantillaPantalla

/**
 * @param onTerminar sale del asistente: al volver desde el primer paso o desde la confirmación.
 * @param onSesionVencida delega un 401 al flujo de sesión de la app; el reporte no se reenvía solo.
 */
@Composable
fun RutaReporteSiniestro(
    dependencias: DependenciasSiniestros,
    onTerminar: () -> Unit,
    onSesionVencida: () -> Unit = {},
) {
    val viewModel = viewModel {
        ReporteViewModel(dependencias.api, createSavedStateHandle(), dependencias.hoy, dependencias.nuevaClave)
    }
    PantallaReporte(viewModel, onTerminar, onSesionVencida)
}

/** Asistente de reporte: póliza, información y revisión, y al final la confirmación con la referencia. */
@Composable
fun PantallaReporte(viewModel: ReporteViewModel, onTerminar: () -> Unit, onSesionVencida: () -> Unit = {}) {
    val estado = viewModel.estado
    LaunchedEffect(estado.sesionVencida) {
        if (estado.sesionVencida) {
            viewModel.sesionAtendida()
            onSesionVencida()
        }
    }
    val siniestro = estado.siniestro
    if (siniestro != null) {
        BackHandler(onBack = onTerminar)
        PantallaConfirmacion(siniestro, onTerminar)
        return
    }
    // Mientras se envía, Atrás no abandona un reporte que quizá ya quedó registrado.
    val volver = { if (!estado.enviando && !viewModel.atras()) onTerminar() }
    BackHandler(onBack = volver)
    PlantillaPantalla(stringResource(R.string.reporte_titulo), onVolver = volver) {
        when {
            estado.errorCarga != null -> {
                Mensaje(stringResource(estado.errorCarga))
                BotonSecundario(stringResource(R.string.boton_reintentar), onClick = viewModel::cargarPolizas)
            }
            estado.cargandoPolizas && estado.polizas.isEmpty() -> Cargando(stringResource(R.string.cargando_polizas))
            estado.sinPolizas -> SinPolizas(onTerminar)
            else -> Asistente(estado, viewModel)
        }
    }
}

@Composable
private fun Asistente(estado: EstadoReporte, viewModel: ReporteViewModel) {
    Text(
        stringResource(
            R.string.reporte_paso,
            estado.paso.ordinal + 1,
            PasoReporte.entries.size,
            stringResource(estado.paso.titulo()),
        ),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag("paso-${estado.paso.name.lowercase()}").semantics { heading() },
    )
    when (estado.paso) {
        PasoReporte.POLIZA -> PasoPoliza(estado, viewModel)
        PasoReporte.INFORMACION -> PasoInformacion(estado, viewModel)
        PasoReporte.REVISION -> PasoRevision(estado, viewModel)
    }
    estado.aviso?.let { Mensaje(textoAviso(it)) }
    val revision = estado.paso == PasoReporte.REVISION
    BotonPrincipal(
        texto = stringResource(if (revision) R.string.boton_enviar else R.string.boton_continuar),
        onClick = viewModel::continuar,
        cargando = estado.enviando,
        modifier = Modifier.testTag(if (revision) "enviar" else "continuar"),
    )
}

@Composable
private fun SinPolizas(onTerminar: () -> Unit) {
    Text(
        stringResource(R.string.sin_polizas_titulo),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag("sin-polizas").semantics { heading() },
    )
    Text(stringResource(R.string.sin_polizas_texto), style = MaterialTheme.typography.bodyLarge)
    BotonPrincipal(stringResource(R.string.boton_volver_inicio), onClick = onTerminar)
}

@Composable
private fun textoAviso(aviso: Aviso): String = when (aviso) {
    is Aviso.FueraDeVigencia -> if (aviso.desde != null && aviso.hasta != null) {
        stringResource(R.string.aviso_fuera_de_vigencia, formatoFecha(aviso.desde), formatoFecha(aviso.hasta))
    } else {
        stringResource(R.string.aviso_fuera_de_vigencia_sin_fechas)
    }
    Aviso.PolizaNoEncontrada -> stringResource(R.string.aviso_poliza_no_encontrada)
    Aviso.ConfirmarDeNuevo -> stringResource(R.string.aviso_confirmar_de_nuevo)
    Aviso.SinConexion -> stringResource(R.string.aviso_sin_conexion)
    Aviso.Generico -> stringResource(R.string.aviso_generico)
}
