package co.solventa.feature.siniestros.seguimiento

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.core.network.BffApi
import co.solventa.core.network.Resultado
import co.solventa.core.network.Siniestro
import co.solventa.core.network.llamarBff
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Por qué no se pudo consultar: sin red se dice distinto que un fallo del BFF. */
enum class FalloConsulta { SIN_RED, GENERICO }

data class EstadoMisSiniestros(
    /** null mientras no haya una primera respuesta. */
    val siniestros: List<Siniestro>? = null,
    val cargando: Boolean = true,
    val error: FalloConsulta? = null,
    /** El BFF respondió 401 y la sesión no se pudo renovar: la pantalla delega al flujo de sesión. */
    val sesionVencida: Boolean = false,
) {
    val vacia: Boolean
        get() = siniestros?.isEmpty() == true
}

/**
 * «Mis siniestros» (WEB-F06): los casos del cliente desde cualquier canal, del más reciente al más
 * antiguo. Los datos viven aquí, no en disco: la rotación no vuelve a consultar y, si Android cerró el
 * proceso, se consultan de nuevo (criterios 12 y 13).
 */
class MisSiniestrosViewModel(private val api: BffApi) : ViewModel() {
    var estado by mutableStateOf(EstadoMisSiniestros())
        private set
    private var consulta: Job? = null

    init {
        actualizar()
    }

    /** Primera carga, «Actualizar», deslizar hacia abajo y «Reintentar». */
    fun actualizar() {
        if (consulta?.isActive == true) return
        estado = estado.copy(cargando = true, error = null)
        consulta = viewModelScope.launch {
            val resultado = llamarBff { api.listarSiniestros() }
            estado = when (resultado) {
                // El BFF ya los ordena; se asegura aquí para no depender de ello.
                is Resultado.Exito -> estado.copy(
                    siniestros = resultado.valor.sortedByDescending { instante(it.reportadoEn) },
                    cargando = false,
                )
                is Resultado.Falla -> estado.copy(
                    cargando = false,
                    error = FalloConsulta.GENERICO,
                    sesionVencida = resultado.http == HTTP_NO_AUTENTICADO,
                )
                Resultado.SinConexion -> estado.copy(cargando = false, error = FalloConsulta.SIN_RED)
            }
        }
    }

    fun sesionAtendida() {
        estado = estado.copy(sesionVencida = false)
    }
}

internal const val HTTP_NO_AUTENTICADO = 401

/** Fecha del contrato como instante, para ordenar aunque lleguen con zonas distintas. */
internal fun instante(iso: String): Instant =
    runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrDefault(Instant.EPOCH)
