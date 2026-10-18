package co.solventa.feature.onboarding.identidad

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import co.solventa.core.network.BffApi
import co.solventa.core.network.EstadoVerificacion
import co.solventa.core.network.MotivoRechazo
import co.solventa.core.network.VerificacionIdentidad
import co.solventa.feature.onboarding.R
import co.solventa.feature.onboarding.comun.BotonPrincipal
import co.solventa.feature.onboarding.comun.BotonSecundario
import co.solventa.feature.onboarding.comun.Cargando
import co.solventa.feature.onboarding.comun.Mensaje
import co.solventa.feature.onboarding.comun.PlantillaPantalla
import co.solventa.feature.onboarding.sesion.GestorSesion
import kotlinx.coroutines.delay

/** Espera antes de reconsultar una verificación `en_proceso`, en vez de bloquear la pantalla. */
const val ESPERA_RECONSULTA_MS = 3_000L

@Composable
fun RutaIdentidad(api: BffApi, sesion: GestorSesion, onAprobada: () -> Unit, onIrAlInicio: () -> Unit) {
    PantallaIdentidad(viewModel { IdentidadViewModel(api, sesion) }, onAprobada, onIrAlInicio)
}

/** Resultado de KYC/AML con un siguiente paso concreto para cada caso, sin jerga del proveedor. */
@Composable
fun PantallaIdentidad(viewModel: IdentidadViewModel, onAprobada: () -> Unit, onIrAlInicio: () -> Unit) {
    when (val estado = viewModel.estado) {
        EstadoIdentidadUi.Cargando -> Resultado(R.string.kyc_en_proceso_titulo, "kyc-cargando") {
            Cargando(stringResource(R.string.kyc_cargando))
        }
        EstadoIdentidadUi.Error -> Resultado(R.string.kyc_en_proceso_titulo, "kyc-error") {
            Mensaje(stringResource(R.string.kyc_error))
            BotonPrincipal(stringResource(R.string.kyc_error_reintentar), onClick = viewModel::consultar)
            BotonSecundario(stringResource(R.string.ir_al_inicio), onClick = onIrAlInicio)
        }
        is EstadoIdentidadUi.Listo -> Verificacion(estado.verificacion, viewModel, onAprobada, onIrAlInicio)
    }
}

@Composable
private fun Verificacion(
    verificacion: VerificacionIdentidad,
    viewModel: IdentidadViewModel,
    onAprobada: () -> Unit,
    onIrAlInicio: () -> Unit,
) {
    when (verificacion.estado) {
        EstadoVerificacion.APROBADA -> Resultado(R.string.kyc_aprobada_titulo, "kyc-aprobada") {
            Texto(R.string.kyc_aprobada_texto)
            BotonPrincipal(stringResource(R.string.boton_continuar), onClick = onAprobada)
        }
        EstadoVerificacion.RECHAZADA -> Resultado(R.string.kyc_rechazada_titulo, "kyc-rechazada") {
            Texto(verificacion.motivo.explicacion())
            if (verificacion.reintentoPermitido == true) {
                Texto(R.string.kyc_rechazada_reintento)
                BotonPrincipal(stringResource(R.string.kyc_reintentar), onClick = viewModel::reintentar)
                BotonSecundario(stringResource(R.string.ir_al_inicio), onClick = onIrAlInicio)
            } else {
                Texto(R.string.kyc_rechazada_sin_reintento)
                BotonPrincipal(stringResource(R.string.ir_al_inicio), onClick = onIrAlInicio)
            }
        }
        EstadoVerificacion.EN_REVISION -> Resultado(R.string.kyc_revision_titulo, "kyc-en-revision") {
            Texto(R.string.kyc_revision_texto)
            BotonPrincipal(stringResource(R.string.ir_al_inicio), onClick = onIrAlInicio)
        }
        EstadoVerificacion.EN_PROCESO -> {
            LaunchedEffect(verificacion.actualizadaEn) {
                delay(ESPERA_RECONSULTA_MS)
                viewModel.consultar()
            }
            Resultado(R.string.kyc_en_proceso_titulo, "kyc-en-proceso") {
                Texto(R.string.kyc_en_proceso_texto)
                BotonPrincipal(stringResource(R.string.kyc_consultar), onClick = viewModel::consultar)
                BotonSecundario(stringResource(R.string.ir_al_inicio), onClick = onIrAlInicio)
            }
        }
    }
}

@Composable
private fun Resultado(@StringRes titulo: Int, etiqueta: String, contenido: @Composable () -> Unit) {
    PlantillaPantalla(stringResource(titulo), onVolver = null, modifier = Modifier.testTag(etiqueta)) {
        contenido()
    }
}

@Composable
private fun Texto(@StringRes texto: Int) {
    Text(stringResource(texto), style = MaterialTheme.typography.bodyLarge)
}

/** Explicación en lenguaje claro; nunca muestra el código del proveedor. */
@StringRes
private fun MotivoRechazo?.explicacion(): Int = when (this) {
    MotivoRechazo.DATOS_NO_COINCIDEN -> R.string.kyc_motivo_datos
    MotivoRechazo.DOCUMENTO_NO_VALIDO -> R.string.kyc_motivo_documento
    MotivoRechazo.NO_SUPERADA, null -> R.string.kyc_motivo_no_superada
}
