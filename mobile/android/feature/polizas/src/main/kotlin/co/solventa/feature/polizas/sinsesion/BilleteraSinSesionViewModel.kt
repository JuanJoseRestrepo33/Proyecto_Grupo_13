package co.solventa.feature.polizas.sinsesion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.core.network.EstadoPoliza
import co.solventa.feature.polizas.billetera.BilleteraGuardada
import co.solventa.feature.polizas.billetera.SincronizadorBilletera
import co.solventa.feature.polizas.comun.Pestana
import co.solventa.feature.polizas.comun.estadoSinConexion
import co.solventa.feature.polizas.credencial.EstadoCredencial
import co.solventa.feature.polizas.credencial.SinCredencial
import co.solventa.feature.polizas.detalle.EstadoDetallePoliza
import co.solventa.feature.polizas.lista.EstadoMisPolizas
import co.solventa.feature.polizas.lista.ModoPolizas
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.launch

data class EstadoSinSesion(
    val cargando: Boolean = true,
    /** La billetera guardada; null si no hay o si la entrada no se desbloqueó. */
    val billetera: BilleteraGuardada? = null,
    /** Volvió una red validada: la franja cambia a «Ya tienes conexión · Ingresa para actualizar». */
    val conectado: Boolean = false,
    val pestana: Pestana = Pestana.ACTIVAS,
    val hoy: LocalDate = LocalDate.now(),
)

/**
 * La billetera sin sesión (MOB-F03, entrega 2, §7): solo lectura, después del desbloqueo del teléfono.
 * No recibe el cliente del BFF: en este modo no hay sesión, así que no hay nada que pedir ni sincronizar.
 * Da a las pantallas de la lista, el detalle y la credencial el mismo estado que tendrían sin conexión.
 */
class BilleteraSinSesionViewModel(
    private val billetera: SincronizadorBilletera,
    private val reloj: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    var estado by mutableStateOf(EstadoSinSesion(conectado = billetera.conectado.value, hoy = LocalDate.now(reloj)))
        private set

    init {
        viewModelScope.launch {
            billetera.conectado.collect { estado = estado.copy(conectado = it) }
        }
        viewModelScope.launch {
            estado = estado.copy(cargando = false, billetera = billetera.cargarSinSesion(), hoy = LocalDate.now(reloj))
        }
    }

    fun elegir(pestana: Pestana) {
        estado = estado.copy(pestana = pestana)
    }

    /** «Mis pólizas» con la billetera guardada, como sin conexión. */
    fun lista(): EstadoMisPolizas = EstadoMisPolizas(
        cargando = estado.cargando,
        pestana = estado.pestana,
        modo = if (estado.cargando || estado.billetera != null) ModoPolizas.SIN_CONEXION else ModoPolizas.SIN_BILLETERA,
        billetera = estado.billetera,
        billeteraLeida = !estado.cargando,
        hoy = estado.hoy,
    )

    /** El detalle guardado; si la póliza no tiene detalle (ya llegó vencida), «Necesitas conexión…». */
    fun detalle(numero: String): EstadoDetallePoliza {
        val guardado = estado.billetera?.detalle(numero)
        return EstadoDetallePoliza(
            detalle = guardado?.copy(estado = estadoSinConexion(guardado.resumen, estado.hoy)),
            cargando = estado.cargando,
            sinConexion = true,
            sincronizadaEn = estado.billetera?.momento,
            detalleNoGuardado = !estado.cargando && guardado == null,
        )
    }

    /** La credencial guardada. Sin sesión no se puede sincronizar: si no está guardada, no hay credencial. */
    fun credencial(numero: String): EstadoCredencial {
        val guardada = estado.billetera
        val guardado = guardada?.detalle(numero)
        val detalle = guardado?.copy(estado = estadoSinConexion(guardado.resumen, estado.hoy))
        return EstadoCredencial(
            cargando = estado.cargando,
            detalle = detalle,
            titular = guardada?.nombreCompleto,
            sincronizadaEn = guardada?.momento,
            sinConexion = true,
            motivo = when {
                estado.cargando -> null
                detalle == null -> SinCredencial.NO_GUARDADA
                detalle.estado != EstadoPoliza.VIGENTE -> SinCredencial.NO_VIGENTE
                else -> null
            },
        )
    }
}
