package co.solventa.feature.onboarding.ingreso

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import co.solventa.core.network.Cliente
import co.solventa.feature.onboarding.DependenciasOnboarding
import co.solventa.feature.onboarding.R
import co.solventa.feature.onboarding.biometria.PropositoBiometrico
import co.solventa.feature.onboarding.comun.BotonPrincipal
import co.solventa.feature.onboarding.comun.BotonSecundario
import co.solventa.feature.onboarding.comun.BotonTexto
import co.solventa.feature.onboarding.comun.CampoTexto
import co.solventa.feature.onboarding.comun.Mensaje
import co.solventa.feature.onboarding.comun.PlantillaPantalla

@Composable
fun RutaIngreso(dependencias: DependenciasOnboarding, onIngresado: (Cliente) -> Unit, onCrearCuenta: () -> Unit) {
    val viewModel = viewModel {
        with(dependencias) { IngresoViewModel(api, sesion, almacen, biometria.disponibilidad()) }
    }
    PantallaIngreso(viewModel, dependencias, onIngresado, onCrearCuenta)
}

/** Ingreso con contraseña y, si está activa, con huella o rostro (P22 con respaldo). */
@Composable
fun PantallaIngreso(
    viewModel: IngresoViewModel,
    dependencias: DependenciasOnboarding,
    onIngresado: (Cliente) -> Unit,
    onCrearCuenta: () -> Unit,
) {
    val estado = viewModel.estado
    LaunchedEffect(estado.ingresado) {
        estado.ingresado?.let {
            viewModel.ingresoAtendido()
            onIngresado(it)
        }
    }
    PlantillaPantalla(stringResource(R.string.ingreso_titulo), onVolver = null) {
        Text(stringResource(R.string.ingreso_subtitulo), style = MaterialTheme.typography.bodyLarge)
        CampoTexto(
            valor = estado.correo,
            onCambio = viewModel::cambiarCorreo,
            etiqueta = stringResource(R.string.campo_correo),
            teclado = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.testTag("ingreso-correo"),
        )
        CampoTexto(
            valor = estado.contrasena,
            onCambio = viewModel::cambiarContrasena,
            etiqueta = stringResource(R.string.campo_contrasena),
            teclado = KeyboardOptions(keyboardType = KeyboardType.Password),
            secreto = true,
            modifier = Modifier.testTag("ingreso-contrasena"),
        )
        estado.mensaje?.let { Mensaje(stringResource(it)) }
        BotonPrincipal(
            stringResource(R.string.ingreso_boton),
            onClick = viewModel::ingresar,
            cargando = estado.enviando,
        )
        if (estado.biometriaActiva) {
            BotonSecundario(
                stringResource(R.string.ingreso_biometria),
                habilitado = !estado.enviando,
                onClick = {
                    val cipher = viewModel.prepararBiometria()
                    if (cipher != null) {
                        dependencias.biometria.autenticar(
                            cipher,
                            PropositoBiometrico.INGRESAR,
                            viewModel::resultadoBiometrico,
                        )
                    }
                },
            )
        }
        Text(stringResource(R.string.ingreso_nuevo), style = MaterialTheme.typography.bodyMedium)
        BotonTexto(stringResource(R.string.boton_crear_cuenta), onClick = onCrearCuenta)
    }
}
