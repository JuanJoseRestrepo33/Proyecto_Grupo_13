package co.solventa.feature.onboarding.registro

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.core.network.BffApi
import co.solventa.core.network.Registro
import co.solventa.core.network.TipoDocumento
import co.solventa.feature.onboarding.R
import co.solventa.feature.onboarding.comun.BotonPrincipal
import co.solventa.feature.onboarding.comun.BotonTexto
import co.solventa.feature.onboarding.comun.CampoTexto
import co.solventa.feature.onboarding.comun.Mensaje
import co.solventa.feature.onboarding.comun.PlantillaPantalla

@Composable
fun RutaRegistro(api: BffApi, onRegistrado: (Registro) -> Unit, onSalir: () -> Unit) {
    PantallaRegistro(viewModel { RegistroViewModel(api) }, onRegistrado, onSalir)
}

/** Asistente de registro (P04): datos, documento y contraseña, con validación en línea por campo. */
@Composable
fun PantallaRegistro(viewModel: RegistroViewModel, onRegistrado: (Registro) -> Unit, onSalir: () -> Unit) {
    val estado = viewModel.estado
    LaunchedEffect(estado.registro) {
        estado.registro?.let {
            viewModel.registroAtendido()
            onRegistrado(it)
        }
    }
    val volver = { if (!viewModel.atras()) onSalir() }
    BackHandler(onBack = volver)
    PlantillaPantalla(stringResource(R.string.registro_titulo), onVolver = volver) {
        Text(
            stringResource(
                R.string.registro_paso,
                estado.paso.ordinal + 1,
                PasoRegistro.entries.size,
                stringResource(estado.paso.titulo()),
            ),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("paso-${estado.paso.name.lowercase()}"),
        )
        when (estado.paso) {
            PasoRegistro.DATOS -> PasoDatos(estado, viewModel)
            PasoRegistro.DOCUMENTO -> PasoDocumento(estado, viewModel)
            PasoRegistro.CONTRASENA -> PasoContrasena(estado, viewModel)
        }
        estado.errorGeneral?.let { Mensaje(stringResource(it)) }
        val ultimo = estado.paso == PasoRegistro.entries.last()
        BotonPrincipal(
            texto = stringResource(if (ultimo) R.string.boton_crear_cuenta else R.string.boton_continuar),
            onClick = viewModel::continuar,
            cargando = estado.enviando,
        )
        if (estado.paso == PasoRegistro.DATOS) {
            BotonTexto(stringResource(R.string.registro_ya_tengo_cuenta), onClick = onSalir)
        }
    }
}

@Composable
private fun PasoDatos(estado: EstadoRegistro, viewModel: RegistroViewModel) {
    CampoRegistro(
        campo = Campo.NOMBRE,
        estado = estado,
        viewModel = viewModel,
        etiqueta = R.string.campo_nombre,
        teclado = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        cambio = { datos, valor -> datos.copy(nombre = valor) },
    )
    CampoRegistro(
        campo = Campo.CORREO,
        estado = estado,
        viewModel = viewModel,
        etiqueta = R.string.campo_correo,
        teclado = KeyboardOptions(keyboardType = KeyboardType.Email),
        cambio = { datos, valor -> datos.copy(correo = valor.trim()) },
    )
    CampoRegistro(
        campo = Campo.CELULAR,
        estado = estado,
        viewModel = viewModel,
        etiqueta = R.string.campo_celular,
        ayuda = R.string.ayuda_celular,
        teclado = KeyboardOptions(keyboardType = KeyboardType.Phone),
        cambio = { datos, valor -> datos.copy(celular = valor.filter(Char::isDigit).take(DIGITOS_CELULAR)) },
    )
}

@Composable
private fun PasoDocumento(estado: EstadoRegistro, viewModel: RegistroViewModel) {
    Text(stringResource(R.string.campo_tipo_documento), style = MaterialTheme.typography.titleSmall)
    Column(Modifier.selectableGroup()) {
        TipoDocumento.entries.forEach { tipo ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SolventaSpacing.touchTarget)
                    .selectable(
                        selected = estado.datos.tipoDocumento == tipo,
                        role = Role.RadioButton,
                        onClick = { viewModel.actualizar(Campo.DOCUMENTO) { it.copy(tipoDocumento = tipo) } },
                    ),
            ) {
                RadioButton(selected = estado.datos.tipoDocumento == tipo, onClick = null)
                Text(stringResource(tipo.etiqueta()))
            }
        }
    }
    val cedula = estado.datos.tipoDocumento == TipoDocumento.CEDULA_CIUDADANIA
    CampoRegistro(
        campo = Campo.DOCUMENTO,
        estado = estado,
        viewModel = viewModel,
        etiqueta = R.string.campo_numero_documento,
        ayuda = R.string.ayuda_documento,
        teclado = KeyboardOptions(keyboardType = if (cedula) KeyboardType.Number else KeyboardType.Ascii),
        cambio = { datos, valor -> datos.copy(numeroDocumento = valor.filter(Char::isLetterOrDigit)) },
    )
}

@Composable
private fun PasoContrasena(estado: EstadoRegistro, viewModel: RegistroViewModel) {
    val teclado = KeyboardOptions(keyboardType = KeyboardType.Password)
    CampoRegistro(
        campo = Campo.CONTRASENA,
        estado = estado,
        viewModel = viewModel,
        etiqueta = R.string.campo_contrasena,
        ayuda = R.string.ayuda_contrasena,
        teclado = teclado,
        secreto = true,
        cambio = { datos, valor -> datos.copy(contrasena = valor) },
    )
    CampoRegistro(
        campo = Campo.CONFIRMACION,
        estado = estado,
        viewModel = viewModel,
        etiqueta = R.string.campo_confirmacion,
        teclado = teclado,
        secreto = true,
        cambio = { datos, valor -> datos.copy(confirmacion = valor) },
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SolventaSpacing.touchTarget)
            .testTag("terminos")
            .toggleable(
                value = estado.datos.aceptaTerminos,
                role = Role.Checkbox,
                onValueChange = { acepta -> viewModel.actualizar(Campo.TERMINOS) { it.copy(aceptaTerminos = acepta) } },
            ),
    ) {
        Checkbox(checked = estado.datos.aceptaTerminos, onCheckedChange = null)
        Text(stringResource(R.string.registro_terminos), style = MaterialTheme.typography.bodySmall)
    }
    estado.error(Campo.TERMINOS)?.let { Mensaje(stringResource(it.mensaje)) }
}

@Composable
private fun CampoRegistro(
    campo: Campo,
    estado: EstadoRegistro,
    viewModel: RegistroViewModel,
    @StringRes etiqueta: Int,
    teclado: KeyboardOptions,
    cambio: (DatosRegistro, String) -> DatosRegistro,
    @StringRes ayuda: Int? = null,
    secreto: Boolean = false,
) {
    CampoTexto(
        valor = estado.datos.valor(campo),
        onCambio = { valor -> viewModel.actualizar(campo) { cambio(it, valor) } },
        etiqueta = stringResource(etiqueta),
        error = estado.error(campo)?.let { stringResource(it.mensaje) },
        ayuda = ayuda?.let { stringResource(it) },
        teclado = teclado,
        secreto = secreto,
        onSalir = { viewModel.salirDe(campo) },
        modifier = Modifier.testTag("campo-${campo.name.lowercase()}"),
    )
}

private const val DIGITOS_CELULAR = 10

private fun PasoRegistro.titulo() = when (this) {
    PasoRegistro.DATOS -> R.string.paso_datos
    PasoRegistro.DOCUMENTO -> R.string.paso_documento
    PasoRegistro.CONTRASENA -> R.string.paso_contrasena
}

private fun TipoDocumento.etiqueta() = when (this) {
    TipoDocumento.CEDULA_CIUDADANIA -> R.string.tipo_cedula_ciudadania
    TipoDocumento.CEDULA_EXTRANJERIA -> R.string.tipo_cedula_extranjeria
    TipoDocumento.PASAPORTE -> R.string.tipo_pasaporte
}
