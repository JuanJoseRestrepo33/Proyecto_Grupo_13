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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.core.network.BffApi

/**
 * @param onVolver regresa a Perfil.
 * @param onSesionVencida delega un 401 al flujo de sesión de la app (#154); la mutación no se reenvía.
 */
@Composable
fun RutaConsentimiento(api: BffApi, onVolver: () -> Unit, onSesionVencida: () -> Unit = {}) {
    val vm = viewModel { ConsentimientoViewModel(api) }
    PantallaConsentimiento(vm, onVolver, onSesionVencida)
}

/**
 * Consulta al entrar y cada vez que vuelve al primer plano mediante [LifecycleEventObserver]:
 * LaunchedEffect(Unit) solo correría una vez por composición. El observador recibe ON_RESUME
 * también al registrarse con la pantalla ya visible, que cubre la entrada.
 */
@Composable
fun PantallaConsentimiento(
    viewModel: ConsentimientoViewModel,
    onVolver: () -> Unit,
    onSesionVencida: () -> Unit = {},
) {
    val estado = viewModel.estado
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.cargar()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(estado.sesionVencida) {
        if (estado.sesionVencida) {
            viewModel.sesionAtendida()
            onSesionVencida()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("pantalla-consentimiento"),
    ) {
        BarraSuperior(habilitada = !estado.mutando, onVolver = onVolver)
        Avisos(estado, onActualizar = viewModel::cargar)
        Contenido(estado, viewModel)
    }

    Dialogos(estado, viewModel)
}

@Composable
private fun BarraSuperior(habilitada: Boolean, onVolver: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SolventaSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onVolver,
            enabled = habilitada,
            modifier = Modifier
                .size(SolventaSpacing.touchTarget)
                .testTag("boton-volver"),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.consentimiento_volver),
            )
        }
        Text(stringResource(R.string.consentimiento_titulo), style = MaterialTheme.typography.titleLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Contenido(estado: EstadoConsentimientoUi, viewModel: ConsentimientoViewModel) {
    PullToRefreshBox(
        isRefreshing = estado.cargando,
        onRefresh = viewModel::cargar,
        modifier = Modifier
            .fillMaxSize()
            .testTag("pull-to-refresh"),
    ) {
        when {
            estado.errorCarga && !estado.cargando -> ErrorCarga(onReintentar = viewModel::cargar)
            // Carga inicial: nunca se presenta como lista vacía.
            estado.finalidades.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val desc = stringResource(R.string.consentimiento_refrescando)
                CircularProgressIndicator(
                    modifier = Modifier
                        .semantics { contentDescription = desc }
                        .testTag("cargando"),
                )
            }
            else -> ListaFinalidades(
                finalidades = estado.finalidades,
                habilitado = estado.accionesHabilitadas,
                renovando = estado.renovando,
                onOtorgar = viewModel::abrirOtorgar,
                onRevocar = viewModel::abrirRevocar,
                onRenovar = viewModel::renovar,
            )
        }
    }
}

/** Avisos sobre la lista: error de consulta, error de mutación con su recuperación y éxito. */
@Composable
private fun Avisos(estado: EstadoConsentimientoUi, onActualizar: () -> Unit) {
    val dialogoAbierto = estado.otorgando != null || estado.revocando != null
    Column(
        modifier = Modifier.padding(horizontal = SolventaSpacing.md),
        verticalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
    ) {
        if (estado.mensajeCarga != null && !estado.errorCarga) {
            AvisoConAccion(
                texto = stringResource(estado.mensajeCarga),
                accion = stringResource(R.string.consentimiento_reintentar),
                habilitada = !estado.cargando,
                tag = "aviso-carga",
                onAccion = onActualizar,
            )
        }
        // Con un diálogo abierto, el error se muestra dentro del diálogo.
        if (estado.mensajeError != null && !dialogoAbierto) {
            if (estado.requiereRefresco) {
                AvisoConAccion(
                    texto = stringResource(estado.mensajeError),
                    accion = stringResource(R.string.consentimiento_actualizar),
                    habilitada = !estado.cargando,
                    tag = "mensaje-error",
                    onAccion = onActualizar,
                )
            } else {
                Text(
                    text = stringResource(estado.mensajeError),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("mensaje-error"),
                )
            }
        }
        if (estado.mensajeExito != null) {
            Column(Modifier.testTag("mensaje-exito")) {
                Text(stringResource(estado.mensajeExito), style = MaterialTheme.typography.bodyMedium)
                estado.nuevaVenceEn?.let {
                    Text(
                        stringResource(R.string.nueva_vence_en, fecha(it)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("nueva-vence-en"),
                    )
                }
            }
        }
    }
}

@Composable
private fun AvisoConAccion(texto: String, accion: String, habilitada: Boolean, tag: String, onAccion: () -> Unit) {
    Column(Modifier.testTag(tag)) {
        Text(texto, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(
            onClick = onAccion,
            enabled = habilitada,
            modifier = Modifier
                .heightIn(min = SolventaSpacing.touchTarget)
                .testTag("$tag-accion"),
        ) {
            Text(accion)
        }
    }
}

@Composable
private fun ErrorCarga(onReintentar: () -> Unit) {
    // Desplazable para que el pull-to-refresh siga funcionando sobre el error.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SolventaSpacing.md)
            .testTag("error-carga"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.consentimiento_error_carga), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.size(SolventaSpacing.md))
        Button(
            onClick = onReintentar,
            modifier = Modifier
                .heightIn(min = SolventaSpacing.touchTarget)
                .testTag("boton-reintentar"),
        ) {
            Text(stringResource(R.string.consentimiento_reintentar))
        }
    }
}

@Composable
private fun Dialogos(estado: EstadoConsentimientoUi, viewModel: ConsentimientoViewModel) {
    estado.otorgando?.let { finalidad ->
        DialogoOtorgar(
            finalidad = finalidad,
            entidadesSeleccionadas = estado.entidadesSeleccionadas,
            versionAceptada = estado.versionAceptada,
            puedeOtorgar = estado.puedeOtorgar,
            mutando = estado.mutando,
            mensajeError = estado.mensajeError,
            onToggleEntidad = viewModel::toggleEntidad,
            onToggleVersion = viewModel::toggleVersionAceptada,
            onOtorgar = viewModel::otorgar,
            onCancelar = viewModel::cerrarOtorgar,
        )
    }
    if (estado.revocando != null) {
        DialogoRevocar(
            finalidad = estado.finalidadRevocando,
            // Tampoco se confirma mientras se consulta el estado (p. ej., al volver a primer plano).
            mutando = estado.mutando || estado.cargando,
            mensajeError = estado.mensajeError,
            onConfirmar = viewModel::revocar,
            onCancelar = viewModel::cerrarRevocar,
        )
    }
}

/** Las fechas llegan en ISO 8601; se muestra la parte de fecha. */
internal fun fecha(iso: String): String = iso.take(LARGO_FECHA)

private const val LARGO_FECHA = 10
