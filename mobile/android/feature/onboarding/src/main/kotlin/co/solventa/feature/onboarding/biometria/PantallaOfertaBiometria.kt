package co.solventa.feature.onboarding.biometria

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import co.solventa.feature.onboarding.DependenciasOnboarding
import co.solventa.feature.onboarding.R
import co.solventa.feature.onboarding.comun.BotonPrincipal
import co.solventa.feature.onboarding.comun.BotonSecundario
import co.solventa.feature.onboarding.comun.Mensaje
import co.solventa.feature.onboarding.comun.PlantillaPantalla

@Composable
fun RutaOfertaBiometria(dependencias: DependenciasOnboarding, onTerminar: () -> Unit) {
    val viewModel = viewModel { OfertaBiometriaViewModel(dependencias.sesion, dependencias.almacen) }
    PantallaOfertaBiometria(viewModel, dependencias.biometria, onTerminar)
}

/** Ofrece la biometría explicando qué implica antes de activarla (MOB-F01, criterio 4). */
@Composable
fun PantallaOfertaBiometria(viewModel: OfertaBiometriaViewModel, biometria: Biometria, onTerminar: () -> Unit) {
    val estado = viewModel.estado
    PlantillaPantalla(stringResource(R.string.oferta_titulo), onVolver = null, modifier = Modifier.testTag("oferta")) {
        if (estado.activada) {
            Mensaje(stringResource(R.string.oferta_activada), esError = false)
            BotonPrincipal(stringResource(R.string.ir_al_inicio), onClick = onTerminar)
        } else {
            Explicacion(R.string.oferta_explicacion_rapido)
            Explicacion(R.string.oferta_explicacion_privacidad)
            Explicacion(R.string.oferta_explicacion_respaldo)
            estado.mensaje?.let { Mensaje(stringResource(it)) }
            BotonPrincipal(
                stringResource(R.string.oferta_activar),
                cargando = estado.activando,
                onClick = {
                    viewModel.prepararActivacion()?.let { cipher ->
                        biometria.autenticar(cipher, PropositoBiometrico.ACTIVAR, viewModel::resultadoBiometrico)
                    }
                },
            )
            BotonSecundario(
                stringResource(R.string.oferta_ahora_no),
                onClick = onTerminar,
                habilitado = !estado.activando,
            )
        }
    }
}

@Composable
private fun Explicacion(@StringRes texto: Int) {
    Text(stringResource(texto), style = MaterialTheme.typography.bodyLarge)
}
