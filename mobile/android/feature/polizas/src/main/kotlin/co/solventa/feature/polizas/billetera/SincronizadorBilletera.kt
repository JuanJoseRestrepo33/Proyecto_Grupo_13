package co.solventa.feature.polizas.billetera

import co.solventa.core.almacen.MonitorConectividad
import co.solventa.core.network.BffApi
import co.solventa.core.network.PolizaDetalle
import co.solventa.core.network.PolizaResumen
import co.solventa.core.network.Resultado
import co.solventa.core.network.llamarBff
import co.solventa.feature.polizas.comun.HTTP_NO_AUTENTICADO
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** La cuenta de la sesión vigente; en la app, la de `GestorSesion`. */
interface CuentaVigente {
    val clienteId: String?
    val nombreCompleto: String?
}

/** Lo que las pantallas ven de la billetera. */
data class EstadoBilletera(
    /** La última leída o guardada, siempre del cliente de la sesión; null si no hay o aún no se leyó. */
    val guardada: BilleteraGuardada? = null,
    val sincronizando: Boolean = false,
)

sealed interface ResultadoSincronizacion {
    /** La lista y todos los detalles llegaron: la billetera se reemplazó. */
    data class Completa(val billetera: BilleteraGuardada) : ResultadoSincronizacion

    /** Algo falló: la billetera guardada quedó como estaba. [sesionVencida]: un 401 que no se pudo renovar. */
    data class Fallida(val sesionVencida: Boolean = false) : ResultadoSincronizacion

    /** Sin sesión, o la cuenta cambió durante la sincronización y se descartó lo que traía. */
    data object Omitida : ResultadoSincronizacion
}

/**
 * Sincroniza la billetera (MOB-F03, §2 y §3). Vive lo que el proceso: una sincronización empezada sigue
 * aunque el cliente cambie de pantalla.
 *
 * - **Una a la vez:** si ya hay una en curso, otro disparo espera esa misma en lugar de lanzar otra.
 * - **Todo o nada:** la billetera se reemplaza solo si llegaron la lista y todos los detalles de las
 *   pólizas `vigente` y `por_iniciar`; el resumen guardado se toma del detalle, que se pidió después.
 * - **Cuentas:** [cambioDeCuenta] sube la generación antes de que cambie la sesión. Justo antes de
 *   escribir, dentro del candado del almacén, se comprueba que la generación y el cliente no cambiaron.
 */
@Suppress("TooManyFunctions")
class SincronizadorBilletera(
    private val api: BffApi,
    private val almacen: AlmacenBilletera,
    private val monitor: MonitorConectividad,
    private val cuenta: CuentaVigente,
    private val alcance: CoroutineScope,
    private val reloj: Clock = Clock.systemUTC(),
) {
    private val generacion = AtomicInteger()
    private val _estado = MutableStateFlow(EstadoBilletera())
    val estado: StateFlow<EstadoBilletera> = _estado.asStateFlow()

    /** Si hay una red validada; con red, un `SinConexion` del BFF es un tiempo agotado. */
    val conectado: StateFlow<Boolean> get() = monitor.conectado

    private var enCurso: Deferred<ResultadoSincronizacion>? = null
    private val candadoEnCurso = Any()

    /** Lee la billetera del cliente de la sesión. Una de otro dueño se borra y no se muestra. */
    suspend fun cargar(): BilleteraGuardada? {
        val cliente = cuenta.clienteId
        val billetera = if (cliente == null) null else almacen.leer { it == cliente }
        // Solo se publica si la cuenta no cambió mientras se leía.
        if (cuenta.clienteId == cliente) _estado.update { it.copy(guardada = billetera) }
        return billetera
    }

    /**
     * Sincroniza con [lista] si la pantalla ya la trajo (así no se pide dos veces) o pidiéndola. Si ya hay
     * una sincronización en curso, devuelve su resultado.
     */
    suspend fun sincronizar(lista: List<PolizaResumen>? = null): ResultadoSincronizacion {
        val trabajo = synchronized(candadoEnCurso) {
            enCurso?.takeIf { it.isActive } ?: alcance.async { ejecutar(lista) }.also { enCurso = it }
        }
        return trabajo.await()
    }

    /**
     * Mientras quien llama esté activo (la pestaña Pólizas, un detalle o una credencial en pantalla), cada
     * vez que [MonitorConectividad.conectado] pasa de false a true con una sesión vigente, llama a
     * [alReconectar]. Sin sesión no hay a quién sincronizar.
     */
    suspend fun vigilarReconexion(alReconectar: suspend () -> Unit) {
        var anterior = monitor.conectado.value
        monitor.conectado.collect { ahora ->
            val reconecta = !anterior && ahora
            anterior = ahora
            if (reconecta && cuenta.clienteId != null) alReconectar()
        }
    }

    /** `ObservadorCuenta.antes`: corre antes de que cambie la sesión (otra cuenta, cierre o vencimiento). */
    fun cambioDeCuenta() {
        generacion.incrementAndGet()
    }

    /**
     * `ObservadorCuenta.despues`: al entrar otra cuenta se borra una billetera de otro dueño antes de
     * mostrar nada. Con null (cierre o vencimiento) no se borra: el vencimiento y el cierre del proceso
     * son justo los casos que cubre la billetera; el cierre de sesión borra con [cerrarSesion].
     */
    fun cuentaCambiada(cliente: String?) {
        _estado.update { if (it.guardada?.clienteId == cliente) it else it.copy(guardada = null) }
        if (cliente != null) alcance.launch { almacen.borrarSiNoEsDe(cliente) }
    }

    /** El cliente cierra sesión: sus pólizas dejan de estar disponibles sin conexión en este teléfono. */
    fun cerrarSesion() {
        generacion.incrementAndGet()
        _estado.update { it.copy(guardada = null) }
        alcance.launch { almacen.borrar() }
    }

    private suspend fun ejecutar(dada: List<PolizaResumen>?): ResultadoSincronizacion {
        val cliente = cuenta.clienteId ?: return ResultadoSincronizacion.Omitida
        val inicio = generacion.get()
        _estado.update { it.copy(sincronizando = true) }
        return try {
            val lista = dada ?: pedir { api.listarPolizas() }
            // De a uno; el primer fallo (5xx, sin red o un 404 de una póliza que desapareció entre las dos
            // llamadas) deja la billetera anterior.
            val detalles = lista.filter(BilleteraGuardada::guardaDetalle)
                .map { pedir { api.obtenerPoliza(it.numero) } }
            guardar(cliente, inicio, lista, detalles)
        } catch (ignored: FalloAlTraer) {
            fallida(ignored.resultado)
        } finally {
            _estado.update { it.copy(sincronizando = false) }
        }
    }

    /** Todo o nada: se guarda solo si la generación y el cliente no cambiaron (§3). */
    private suspend fun guardar(
        cliente: String,
        inicio: Int,
        lista: List<PolizaResumen>,
        detalles: List<PolizaDetalle>,
    ): ResultadoSincronizacion {
        val porNumero = detalles.associateBy(PolizaDetalle::numero)
        val billetera = BilleteraGuardada(
            clienteId = cliente,
            nombreCompleto = cuenta.nombreCompleto.orEmpty(),
            // El resumen guardado se toma del detalle, que se pidió después.
            polizas = lista.map { porNumero[it.numero]?.resumen ?: it },
            detalles = detalles,
            sincronizadaEn = reloj.instant().toString(),
        )
        val vigente = { generacion.get() == inicio && cuenta.clienteId == cliente }
        val guardada = almacen.guardarSi(billetera, vigente)
        if (guardada) _estado.update { it.copy(guardada = billetera) }
        return when {
            guardada -> ResultadoSincronizacion.Completa(billetera)
            !vigente() -> ResultadoSincronizacion.Omitida
            else -> ResultadoSincronizacion.Fallida()
        }
    }

    private suspend fun <T> pedir(llamada: suspend () -> T): T = when (val resultado = llamarBff(llamada)) {
        is Resultado.Exito -> resultado.valor
        else -> throw FalloAlTraer(resultado)
    }

    /** Una llamada de la sincronización no llegó bien: corta el resto. */
    private class FalloAlTraer(val resultado: Resultado<*>) : Exception()

    private fun fallida(resultado: Resultado<*>) = ResultadoSincronizacion.Fallida(
        sesionVencida = resultado is Resultado.Falla && resultado.http == HTTP_NO_AUTENTICADO,
    )
}
