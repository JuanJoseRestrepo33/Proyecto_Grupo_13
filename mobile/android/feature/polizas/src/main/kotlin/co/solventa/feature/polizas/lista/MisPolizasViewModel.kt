package co.solventa.feature.polizas.lista

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.core.network.BffApi
import co.solventa.core.network.PolizaResumen
import co.solventa.core.network.Resultado
import co.solventa.core.network.llamarBff
import co.solventa.feature.polizas.comun.FalloConsulta
import co.solventa.feature.polizas.comun.HTTP_NO_AUTENTICADO
import co.solventa.feature.polizas.comun.Pestana
import co.solventa.feature.polizas.comun.fallo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class EstadoMisPolizas(
    /** null mientras no haya una primera respuesta. */
    val polizas: List<PolizaResumen>? = null,
    val cargando: Boolean = true,
    /** La consulta falló; si ya había una lista, se sigue mostrando con el aviso y «Reintentar». */
    val error: FalloConsulta? = null,
    /** El BFF respondió 401 y la sesión no se pudo renovar: la pantalla delega al flujo de sesión. */
    val sesionVencida: Boolean = false,
    val pestana: Pestana = Pestana.ACTIVAS,
) {
    /** Las de la pestaña elegida, en el orden del BFF (de la más reciente a la más antigua). */
    val visibles: List<PolizaResumen>
        get() = polizas.orEmpty().filter(pestana::incluye)

    /** Ya hay una lista y se está consultando otra: se muestra la anterior con un indicador. */
    val actualizando: Boolean
        get() = cargando && polizas != null
}

/**
 * «Mis pólizas» (WEB-F04): las pólizas del cliente, las mismas que ve en la web. Los datos viven aquí y
 * no en disco: rotar la pantalla o volver del segundo plano no consulta (criterio 11).
 *
 * Política de refresco (§2): cada vez que el cliente entra a la pestaña Pólizas desde la barra inferior,
 * la app sube un contador de [entradaInicial] y lo pasa a [alEntrar], que consulta otra vez si cambió.
 * La pantalla se recompone sin cambiarlo (rotación, regreso del detalle), así que no consulta.
 */
class MisPolizasViewModel(private val api: BffApi, entradaInicial: Int = 0) : ViewModel() {
    var estado by mutableStateOf(EstadoMisPolizas())
        private set
    private var consulta: Job? = null
    private var ultimaEntrada = entradaInicial

    init {
        actualizar()
    }

    /** Primera carga, «Actualizar», deslizar hacia abajo y «Reintentar». */
    fun actualizar() {
        if (consulta?.isActive == true) return
        estado = estado.copy(cargando = true, error = null)
        consulta = viewModelScope.launch {
            val resultado = llamarBff { api.listarPolizas() }
            estado = when {
                // Se respeta el orden del BFF: de la más reciente a la más antigua, como en la web.
                resultado is Resultado.Exito -> estado.copy(polizas = resultado.valor, cargando = false)
                resultado is Resultado.Falla && resultado.http == HTTP_NO_AUTENTICADO ->
                    estado.copy(cargando = false, sesionVencida = true)
                // Si falla, se conserva la lista anterior.
                else -> estado.copy(cargando = false, error = resultado.fallo())
            }
        }
    }

    /** El cliente entró a la pestaña desde la barra inferior: si es una entrada nueva, se consulta. */
    fun alEntrar(entrada: Int) {
        if (entrada == ultimaEntrada) return
        ultimaEntrada = entrada
        actualizar()
    }

    fun elegir(pestana: Pestana) {
        estado = estado.copy(pestana = pestana)
    }

    fun sesionAtendida() {
        estado = estado.copy(sesionVencida = false)
    }
}
