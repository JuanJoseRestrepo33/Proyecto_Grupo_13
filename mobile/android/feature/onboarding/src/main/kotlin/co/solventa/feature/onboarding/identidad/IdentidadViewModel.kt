package co.solventa.feature.onboarding.identidad

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.core.network.BffApi
import co.solventa.core.network.EstadoIdentidad
import co.solventa.core.network.EstadoVerificacion
import co.solventa.core.network.VerificacionIdentidad
import co.solventa.core.network.Resultado
import co.solventa.core.network.llamarBff
import co.solventa.feature.onboarding.sesion.GestorSesion
import kotlinx.coroutines.launch

sealed interface EstadoIdentidadUi {
    data object Cargando : EstadoIdentidadUi
    data class Listo(val verificacion: VerificacionIdentidad) : EstadoIdentidadUi
    data object Error : EstadoIdentidadUi
}

/**
 * Consulta la última verificación KYC/AML y, si no hay ninguna, la solicita. Una respuesta
 * `en_proceso` no deja al usuario en una carga indefinida: la pantalla la explica y reconsulta.
 */
class IdentidadViewModel(private val api: BffApi, private val sesion: GestorSesion) : ViewModel() {
    var estado by mutableStateOf<EstadoIdentidadUi>(EstadoIdentidadUi.Cargando)
        private set

    init {
        consultar()
    }

    fun consultar() = cargar { consultarOSolicitar() }

    fun reintentar() = cargar { solicitar() }

    private fun cargar(operacion: suspend () -> Resultado<VerificacionIdentidad>) {
        estado = EstadoIdentidadUi.Cargando
        viewModelScope.launch {
            val resultado = operacion()
            estado = if (resultado is Resultado.Exito) {
                sesion.actualizarIdentidad(resultado.valor.estado.comoIdentidad())
                EstadoIdentidadUi.Listo(resultado.valor)
            } else {
                EstadoIdentidadUi.Error
            }
        }
    }

    private suspend fun consultarOSolicitar(): Resultado<VerificacionIdentidad> {
        val actual = llamarBff { api.obtenerVerificacionIdentidad() }
        return if (actual is Resultado.Falla && actual.http == HTTP_NO_ENCONTRADO) solicitar() else actual
    }

    /** Si la identidad ya quedó verificada (409), basta con leer el resultado vigente. */
    private suspend fun solicitar(): Resultado<VerificacionIdentidad> {
        val solicitud = llamarBff { api.solicitarVerificacionIdentidad() }
        return if (solicitud is Resultado.Falla && solicitud.http == HTTP_CONFLICTO) {
            llamarBff { api.obtenerVerificacionIdentidad() }
        } else {
            solicitud
        }
    }

    private companion object {
        const val HTTP_NO_ENCONTRADO = 404
        const val HTTP_CONFLICTO = 409
    }
}

internal fun EstadoVerificacion.comoIdentidad(): EstadoIdentidad = when (this) {
    EstadoVerificacion.APROBADA -> EstadoIdentidad.VERIFICADA
    EstadoVerificacion.RECHAZADA -> EstadoIdentidad.RECHAZADA
    EstadoVerificacion.EN_REVISION -> EstadoIdentidad.EN_REVISION
    EstadoVerificacion.EN_PROCESO -> EstadoIdentidad.PENDIENTE_VERIFICACION
}
