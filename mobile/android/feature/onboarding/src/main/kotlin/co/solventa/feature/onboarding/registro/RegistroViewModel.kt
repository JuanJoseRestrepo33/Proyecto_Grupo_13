package co.solventa.feature.onboarding.registro

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.core.network.BffApi
import co.solventa.core.network.Registro
import co.solventa.core.network.SolicitudRegistro
import co.solventa.feature.onboarding.R
import co.solventa.feature.onboarding.comun.Resultado
import co.solventa.feature.onboarding.comun.llamarBff
import kotlinx.coroutines.launch

data class EstadoRegistro(
    val paso: PasoRegistro = PasoRegistro.DATOS,
    val datos: DatosRegistro = DatosRegistro(),
    /** Campos cuyo error ya se muestra: tras salir del campo o al intentar avanzar (P06). */
    val visibles: Set<Campo> = emptySet(),
    val erroresServidor: Map<Campo, ProblemaCampo> = emptyMap(),
    val enviando: Boolean = false,
    @StringRes val errorGeneral: Int? = null,
    /** Cuenta creada; la pantalla navega al OTP y lo consume. */
    val registro: Registro? = null,
) {
    fun error(campo: Campo): ProblemaCampo? =
        erroresServidor[campo] ?: ValidadorRegistro.validar(campo, datos).takeIf { campo in visibles }
}

class RegistroViewModel(private val api: BffApi) : ViewModel() {
    var estado by mutableStateOf(EstadoRegistro())
        private set

    /** Cambia un dato; un error del servidor sobre ese campo deja de aplicar. */
    fun actualizar(campo: Campo, cambio: (DatosRegistro) -> DatosRegistro) {
        estado = estado.copy(
            datos = cambio(estado.datos),
            erroresServidor = estado.erroresServidor - campo,
            errorGeneral = null,
        )
    }

    fun salirDe(campo: Campo) {
        if (campo !in estado.visibles && estado.datos.valor(campo).isNotEmpty()) {
            estado = estado.copy(visibles = estado.visibles + campo)
        }
    }

    /** Valida el paso actual; si está completo avanza o, en el último, crea la cuenta. */
    fun continuar() {
        val paso = estado.paso
        estado = estado.copy(visibles = estado.visibles + paso.campos)
        if (paso.campos.any { estado.error(it) != null } || estado.enviando) return
        val siguiente = PasoRegistro.entries.getOrNull(paso.ordinal + 1)
        if (siguiente != null) {
            estado = estado.copy(paso = siguiente)
        } else {
            registrar()
        }
    }

    /** @return false si ya está en el primer paso y la pantalla debe salir del asistente. */
    fun atras(): Boolean {
        val anterior = PasoRegistro.entries.getOrNull(estado.paso.ordinal - 1) ?: return false
        estado = estado.copy(paso = anterior, errorGeneral = null)
        return true
    }

    fun registroAtendido() {
        estado = estado.copy(registro = null)
    }

    private fun registrar() {
        val datos = estado.datos
        estado = estado.copy(enviando = true, errorGeneral = null)
        viewModelScope.launch {
            val resultado = llamarBff { api.crearRegistro(solicitud(datos)) }
            estado = when (resultado) {
                is Resultado.Exito -> estado.copy(enviando = false, registro = resultado.valor)
                is Resultado.Falla -> rechazado(resultado).copy(enviando = false)
                Resultado.SinConexion -> estado.copy(enviando = false, errorGeneral = R.string.error_sin_conexion)
            }
        }
    }

    /** Lleva al usuario al primer paso con un campo rechazado y le muestra por qué. */
    private fun rechazado(falla: Resultado.Falla): EstadoRegistro {
        val errores = when (falla.codigo) {
            "correo_ya_registrado" -> mapOf(Campo.CORREO to ProblemaCampo.CORREO_YA_REGISTRADO)
            "documento_ya_registrado" -> mapOf(Campo.DOCUMENTO to ProblemaCampo.DOCUMENTO_YA_REGISTRADO)
            else -> falla.error?.campos.orEmpty()
                .mapNotNull { Campo.delContrato(it.campo) }
                .associateWith { ValidadorRegistro.formatoDe(it, estado.datos.tipoDocumento) }
        }
        val primero = errores.keys.minByOrNull { it.ordinal }
            ?: return estado.copy(errorGeneral = R.string.error_generico)
        return estado.copy(paso = PasoRegistro.de(primero), erroresServidor = errores)
    }

    private fun solicitud(datos: DatosRegistro) = SolicitudRegistro(
        nombreCompleto = datos.nombre.trim(),
        tipoDocumento = datos.tipoDocumento,
        numeroDocumento = datos.numeroDocumento,
        correo = datos.correo.trim(),
        celular = datos.celular,
        contrasena = datos.contrasena,
        versionTerminos = VERSION_TERMINOS,
    )
}
