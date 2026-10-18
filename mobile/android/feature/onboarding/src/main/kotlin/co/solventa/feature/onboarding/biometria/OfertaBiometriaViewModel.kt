package co.solventa.feature.onboarding.biometria

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import co.solventa.core.network.Cliente
import co.solventa.core.network.EstadoIdentidad
import co.solventa.feature.onboarding.R
import co.solventa.feature.onboarding.sesion.GestorSesion
import javax.crypto.Cipher

/** La biometría se ofrece solo a usuarios verificados, con sensor listo y sin activarla antes (criterio 4). */
fun debeOfrecerBiometria(cliente: Cliente, disponibilidad: DisponibilidadBiometrica, almacen: AlmacenToken) =
    cliente.estadoIdentidad == EstadoIdentidad.VERIFICADA &&
        disponibilidad == DisponibilidadBiometrica.DISPONIBLE &&
        !almacen.hayToken()

data class EstadoOferta(
    val activando: Boolean = false,
    val activada: Boolean = false,
    @StringRes val mensaje: Int? = null,
)

/**
 * Activa el ingreso biométrico: guarda el token de refresco cifrado y lo lee de vuelta con la
 * huella o el rostro, lo que confirma que el sensor y la llave funcionan antes de darlo por activo.
 */
class OfertaBiometriaViewModel(private val sesion: GestorSesion, private val almacen: AlmacenToken) : ViewModel() {
    var estado by mutableStateOf(EstadoOferta())
        private set

    /** Cipher que la biometría debe desbloquear para confirmar la activación; null si no se pudo preparar. */
    fun prepararActivacion(): Cipher? {
        val token = sesion.sesion?.tokenRefresco
        val cipher = token?.takeIf(almacen::guardar)?.let { almacen.prepararLectura() }
        estado = if (cipher == null) {
            almacen.borrar()
            estado.copy(mensaje = R.string.oferta_no_activada)
        } else {
            estado.copy(activando = true, mensaje = null)
        }
        return cipher
    }

    fun resultadoBiometrico(resultado: ResultadoBiometrico) {
        val confirmado = resultado is ResultadoBiometrico.Desbloqueado &&
            almacen.leer(resultado.cipher) == sesion.sesion?.tokenRefresco
        estado = if (confirmado) {
            estado.copy(activando = false, activada = true)
        } else {
            almacen.borrar()
            estado.copy(activando = false, mensaje = R.string.oferta_no_activada)
        }
    }
}
