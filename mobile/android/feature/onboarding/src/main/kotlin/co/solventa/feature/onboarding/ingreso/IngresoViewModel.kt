package co.solventa.feature.onboarding.ingreso

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.core.network.BffApi
import co.solventa.core.network.Cliente
import co.solventa.core.network.Credenciales
import co.solventa.core.network.Sesion
import co.solventa.core.network.SolicitudRenovacion
import co.solventa.feature.onboarding.R
import co.solventa.feature.onboarding.biometria.AlmacenToken
import co.solventa.feature.onboarding.biometria.DisponibilidadBiometrica
import co.solventa.feature.onboarding.biometria.ResultadoBiometrico
import co.solventa.feature.onboarding.biometria.rotar
import co.solventa.core.network.Resultado
import co.solventa.core.network.llamarBff
import co.solventa.feature.onboarding.sesion.GestorSesion
import javax.crypto.Cipher
import kotlinx.coroutines.launch

private val CORREO = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

data class EstadoIngreso(
    val correo: String = "",
    val contrasena: String = "",
    val enviando: Boolean = false,
    /** Hay un token guardado y el sensor responde: se ofrece entrar con huella o rostro. */
    val biometriaActiva: Boolean = false,
    @StringRes val mensaje: Int? = null,
    /** Sesión abierta; la pantalla decide el destino según la identidad y lo consume. */
    val ingresado: Cliente? = null,
) {
    override fun toString() = "EstadoIngreso(correo=$correo, enviando=$enviando, biometriaActiva=$biometriaActiva)"
}

/**
 * Ingreso con contraseña o biometría (P22). Cualquier problema con la biometría termina en el
 * formulario de contraseña con una explicación; la cuenta nunca se bloquea (MOB-F01, criterio 5).
 */
class IngresoViewModel(
    private val api: BffApi,
    private val sesion: GestorSesion,
    private val almacen: AlmacenToken,
    disponibilidad: DisponibilidadBiometrica,
) : ViewModel() {
    var estado by mutableStateOf(
        EstadoIngreso(biometriaActiva = disponibilidad == DisponibilidadBiometrica.DISPONIBLE && almacen.hayToken()),
    )
        private set

    fun cambiarCorreo(valor: String) {
        estado = estado.copy(correo = valor, mensaje = null)
    }

    fun cambiarContrasena(valor: String) {
        estado = estado.copy(contrasena = valor, mensaje = null)
    }

    fun ingresar() {
        if (estado.enviando) return
        if (!CORREO.matches(estado.correo.trim()) || estado.contrasena.isEmpty()) {
            estado = estado.copy(mensaje = R.string.ingreso_datos_incompletos)
            return
        }
        val credenciales = Credenciales(estado.correo.trim(), estado.contrasena)
        abrir({ api.iniciarSesion(credenciales) }) { estado.copy(mensaje = R.string.ingreso_credenciales_invalidas) }
    }

    /** Cipher que la biometría debe desbloquear; null si la llave quedó invalidada. */
    fun prepararBiometria(): Cipher? = almacen.prepararLectura().also {
        if (it == null) desactivarBiometria(R.string.biometria_invalidada)
    }

    fun resultadoBiometrico(resultado: ResultadoBiometrico) {
        when (resultado) {
            is ResultadoBiometrico.Desbloqueado -> renovar(resultado.cipher)
            ResultadoBiometrico.Fallido -> estado = estado.copy(mensaje = R.string.biometria_fallida)
            ResultadoBiometrico.NoDisponible -> estado = estado.copy(mensaje = R.string.biometria_no_disponible)
            ResultadoBiometrico.Cancelado -> Unit
        }
    }

    fun ingresoAtendido() {
        estado = estado.copy(ingresado = null, contrasena = "")
    }

    private fun renovar(desbloqueado: Cipher) {
        val token = almacen.leer(desbloqueado)
        if (token == null) {
            desactivarBiometria(R.string.biometria_invalidada)
        } else {
            // Un token de refresco rechazado (vencido o revocado) no sirve más: se borra y queda la contraseña.
            abrir({ api.renovarSesion(SolicitudRenovacion(token)) }) {
                almacen.borrar()
                estado.copy(biometriaActiva = false, mensaje = R.string.biometria_vencida)
            }
        }
    }

    /** @param rechazado estado si el BFF responde 401: contraseña errada o token de refresco inválido. */
    private fun abrir(llamada: suspend () -> Sesion, rechazado: () -> EstadoIngreso) {
        estado = estado.copy(enviando = true, mensaje = null)
        viewModelScope.launch {
            val resultado = llamarBff(llamada)
            estado = when {
                resultado is Resultado.Exito -> abierta(resultado.valor)
                resultado is Resultado.Falla && resultado.http == HTTP_NO_AUTORIZADO ->
                    rechazado().copy(enviando = false)
                resultado is Resultado.Falla -> estado.copy(enviando = false, mensaje = R.string.error_generico)
                else -> estado.copy(enviando = false, mensaje = R.string.error_sin_conexion)
            }
        }
    }

    /**
     * La rotación invalida el token anterior: el nuevo se guarda si la biometría sigue activa. Si no
     * se puede guardar, la huella se desactiva y el aviso se muestra en Inicio, adonde se navega.
     */
    private fun abierta(nueva: Sesion): EstadoIngreso {
        sesion.sesion = nueva
        val rotado = almacen.rotar(nueva.tokenRefresco)
        if (!rotado) sesion.avisarBiometriaDesactivada()
        return estado.copy(
            enviando = false,
            ingresado = nueva.cliente,
            biometriaActiva = estado.biometriaActiva && rotado,
        )
    }

    private fun desactivarBiometria(@StringRes motivo: Int) {
        almacen.borrar()
        estado = estado.copy(biometriaActiva = false, mensaje = motivo)
    }

    private companion object {
        const val HTTP_NO_AUTORIZADO = 401
    }
}
