package co.solventa.feature.polizas.credencial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.core.network.EstadoPoliza
import co.solventa.core.network.PolizaDetalle
import co.solventa.feature.polizas.billetera.BilleteraGuardada
import co.solventa.feature.polizas.billetera.ResultadoSincronizacion
import co.solventa.feature.polizas.billetera.SincronizadorBilletera
import co.solventa.feature.polizas.comun.estadoSinConexion
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Por qué no se muestra la credencial. */
enum class SinCredencial {
    /** La póliza no está en la billetera y no hay red para sincronizar. */
    NECESITA_CONEXION,

    /** Se sincronizó y la póliza no está guardada (no existe o ya no está vigente ni por iniciar). */
    NO_GUARDADA,

    /** Según la última sincronización, la póliza ya no se muestra vigente (§5). */
    NO_VIGENTE,
}

data class EstadoCredencial(
    val cargando: Boolean = true,
    /** El detalle guardado, con el estado según la última sincronización. */
    val detalle: PolizaDetalle? = null,
    /** `nombre_completo` de la sesión que sincronizó. */
    val titular: String? = null,
    val sincronizadaEn: Instant? = null,
    /** No hay una red validada: se suma la franja «Sin conexión». */
    val sinConexion: Boolean = false,
    /** Volvió la conexión y se está sincronizando. */
    val sincronizando: Boolean = false,
    /** Falló la sincronización al volver la conexión: franja «No pudimos actualizar» con «Reintentar» (§2). */
    val sinActualizar: Boolean = false,
    val motivo: SinCredencial? = null,
    val sesionVencida: Boolean = false,
)

/**
 * Credencial digital (MOB-F03, §6): una copia informativa que un tercero puede contrastar con la
 * aseguradora, no una prueba criptográfica de cobertura. Con o sin conexión siempre lee la billetera; si
 * la póliza todavía no está guardada, con conexión sincroniza primero y sin red explica que la necesita.
 */
class CredencialViewModel(
    estadoGuardado: SavedStateHandle,
    private val billetera: SincronizadorBilletera,
    private val reloj: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    val numero: String = requireNotNull(estadoGuardado.get<String>(ARGUMENTO_NUMERO)) { "Falta el número de la póliza" }

    var estado by mutableStateOf(EstadoCredencial(sinConexion = !billetera.conectado.value))
        private set
    private var carga: Job? = null
    private val vigia = billetera.vigia()

    init {
        viewModelScope.launch {
            billetera.conectado.collect { estado = estado.copy(sinConexion = !it) }
        }
        // Una sincronización en segundo plano (al reconectar) actualiza la credencial y su fecha.
        viewModelScope.launch {
            billetera.estado.collect { actual ->
                actual.guardada?.takeIf { it.detalle(numero) != null }?.let { mostrar(it) }
            }
        }
        cargar()
    }

    /** Primera carga y «Reintentar». */
    fun cargar() {
        if (carga?.isActive == true) return
        estado = estado.copy(cargando = true, motivo = null)
        carga = viewModelScope.launch {
            val guardada = billetera.cargar()
            when {
                guardada?.detalle(numero) != null -> mostrar(guardada)
                billetera.conectado.value -> sincronizarYMostrar()
                else -> estado = estado.copy(cargando = false, motivo = SinCredencial.NECESITA_CONEXION)
            }
        }
    }

    /**
     * Mientras la pantalla esté visible: al volver la conexión con sesión, se sincroniza sola (§2). La
     * franja «Sin conexión» sigue al monitor, así que perder la red no necesita nada más.
     */
    suspend fun vigilarConexion() = vigia.vigilar(
        alPerder = {},
        alReconectar = {
            carga?.join()
            alReconectar()
        },
    )

    /** Al volver la conexión y con «Reintentar» de la franja «No pudimos actualizar». */
    fun alReconectar() {
        if (carga?.isActive == true) return
        estado = estado.copy(sincronizando = true)
        carga = viewModelScope.launch {
            sincronizarYMostrar()
            estado = estado.copy(sincronizando = false)
        }
    }

    fun sesionAtendida() {
        estado = estado.copy(sesionVencida = false)
    }

    private suspend fun sincronizarYMostrar() {
        when (val resultado = billetera.sincronizar()) {
            is ResultadoSincronizacion.Completa -> {
                estado = estado.copy(sinActualizar = false)
                if (resultado.billetera.detalle(numero) != null) {
                    mostrar(resultado.billetera)
                } else {
                    estado = estado.copy(cargando = false, detalle = null, motivo = SinCredencial.NO_GUARDADA)
                }
            }
            is ResultadoSincronizacion.Fallida -> estado = estado.copy(
                cargando = false,
                // Con una credencial en pantalla se queda la que había, con «No pudimos actualizar».
                sinActualizar = estado.detalle != null,
                sesionVencida = resultado.sesionVencida,
                // Con una credencial en pantalla, se queda la que había.
                motivo = estado.motivo.takeIf { estado.detalle != null } ?: motivoSinDetalle(),
            )
            ResultadoSincronizacion.Omitida -> estado = estado.copy(cargando = false, motivo = motivoSinDetalle())
        }
    }

    private fun motivoSinDetalle(): SinCredencial? =
        if (estado.detalle == null) SinCredencial.NECESITA_CONEXION else estado.motivo

    private fun mostrar(guardada: BilleteraGuardada) {
        val guardado = guardada.detalle(numero) ?: return
        val detalle = guardado.copy(estado = estadoSinConexion(guardado.resumen, LocalDate.now(reloj)))
        estado = estado.copy(
            cargando = false,
            detalle = detalle,
            titular = guardada.nombreCompleto,
            sincronizadaEn = guardada.momento,
            motivo = SinCredencial.NO_VIGENTE.takeIf { detalle.estado != EstadoPoliza.VIGENTE },
        )
    }

    companion object {
        /** Argumento de la ruta de la credencial; la navegación lo deja en el SavedStateHandle. */
        const val ARGUMENTO_NUMERO = "numero"
    }
}
