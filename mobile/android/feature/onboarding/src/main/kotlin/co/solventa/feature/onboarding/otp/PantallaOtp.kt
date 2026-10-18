package co.solventa.feature.onboarding.otp

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import co.solventa.core.network.BffApi
import co.solventa.core.network.Cliente
import co.solventa.feature.onboarding.R
import co.solventa.feature.onboarding.comun.BotonPrincipal
import co.solventa.feature.onboarding.comun.BotonTexto
import co.solventa.feature.onboarding.comun.CampoTexto
import co.solventa.feature.onboarding.comun.Mensaje
import co.solventa.feature.onboarding.comun.PlantillaPantalla
import co.solventa.feature.onboarding.sesion.GestorSesion
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

@Composable
fun RutaOtp(
    api: BffApi,
    sesion: GestorSesion,
    idRegistro: String,
    reenvioDisponibleEn: Instant,
    reloj: () -> Instant,
    onVerificado: (Cliente) -> Unit,
) {
    val viewModel = viewModel { OtpViewModel(api, sesion, idRegistro, reenvioDisponibleEn, reloj) }
    PantallaOtp(viewModel, reloj, onVerificado)
}

/** Verificación del correo: 3 intentos por código y reenvío con espera; la cuenta nunca se bloquea. */
@Composable
fun PantallaOtp(viewModel: OtpViewModel, reloj: () -> Instant, onVerificado: (Cliente) -> Unit) {
    val estado = viewModel.estado
    LaunchedEffect(estado.verificado) {
        estado.verificado?.let {
            viewModel.verificacionAtendida()
            onVerificado(it)
        }
    }
    val espera = segundosHasta(estado.reenvioDisponibleEn, reloj)
    val sinCodigoValido = estado.bloqueado || estado.vencido
    PlantillaPantalla(stringResource(R.string.otp_titulo), onVolver = null) {
        Mensaje(stringResource(R.string.otp_pendiente), esError = false)
        Text(stringResource(R.string.otp_instruccion), style = MaterialTheme.typography.bodyLarge)
        CampoTexto(
            valor = estado.codigo,
            onCambio = viewModel::cambiarCodigo,
            etiqueta = stringResource(R.string.campo_otp),
            ayuda = stringResource(R.string.otp_intentos, estado.intentosRestantes),
            teclado = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            habilitado = !sinCodigoValido,
            modifier = Modifier.testTag("campo-otp"),
        )
        estado.error?.let { Mensaje(stringResource(it, estado.intentosRestantes)) }
        estado.aviso?.let { Mensaje(stringResource(it), esError = false) }
        val etiquetaReenvio = if (espera > 0) {
            stringResource(R.string.otp_reenviar_en, espera)
        } else {
            stringResource(if (sinCodigoValido) R.string.otp_pedir_nuevo else R.string.otp_reenviar)
        }
        if (sinCodigoValido) {
            BotonPrincipal(
                etiquetaReenvio,
                onClick = viewModel::reenviar,
                habilitado = espera == 0L,
                cargando = estado.enviando,
            )
        } else {
            BotonPrincipal(
                stringResource(R.string.otp_verificar),
                onClick = viewModel::verificar,
                habilitado = estado.puedeVerificar,
                cargando = estado.enviando,
            )
            BotonTexto(etiquetaReenvio, onClick = viewModel::reenviar, habilitado = espera == 0L && !estado.enviando)
        }
    }
}

/** Segundos que faltan para [hasta]; se actualiza cada segundo mientras queden. */
@Composable
private fun segundosHasta(hasta: Instant, reloj: () -> Instant): Long {
    var ahora by remember { mutableStateOf(reloj()) }
    LaunchedEffect(hasta) {
        ahora = reloj()
        while (ahora.isBefore(hasta)) {
            delay(UN_SEGUNDO)
            ahora = reloj()
        }
    }
    return Duration.between(ahora, hasta).seconds.coerceAtLeast(0)
}

private const val UN_SEGUNDO = 1_000L
