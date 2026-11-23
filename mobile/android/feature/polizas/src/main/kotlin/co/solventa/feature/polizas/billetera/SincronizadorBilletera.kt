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

    /** La entrada sin sesión (§7) se desbloqueó; la revocan [cerrarSinSesion] y cualquier cambio de cuenta. */
    @Volatile
    private var desbloqueadaSinSesion = false

    /** El cliente abrió la billetera sin sesión: al ingresar se sincroniza (§7). */
    @Volatile
    private var sincronizarAlIngresar = false

    /** Impide ofrecer la copia mientras termina el borrado solicitado al cerrar sesión. */
    @Volatile
    private var borradoPendiente = false
    private val _estado = MutableStateFlow(EstadoBilletera())
    val estado: StateFlow<EstadoBilletera> = _estado.asStateFlow()

    /** Si hay una red validada; con red, un `SinConexion` del BFF es un tiempo agotado. */
    val conectado: StateFlow<Boolean> get() = monitor.conectado

    private var enCurso: EnCurso? = null
    private val candadoEnCurso = Any()

    /** La sincronización en curso y la cuenta (cliente y generación) para la que empezó. */
    private class EnCurso(val cliente: String?, val generacion: Int, val trabajo: Deferred<ResultadoSincronizacion>)

    /** Lee la billetera del cliente de la sesión. Una de otro dueño se borra y no se muestra. */
    suspend fun cargar(): BilleteraGuardada? {
        val cliente = cuenta.clienteId ?: return null
        val inicio = generacion.get()
        val billetera = almacen.leer { it == cliente }
        // Solo se publica y se devuelve si la cuenta no cambió mientras se leía (§3).
        val sigue = { generacion.get() == inicio && cuenta.clienteId == cliente }
        val publicada = publicarSi(sigue) { it.copy(guardada = billetera) }
        return billetera.takeIf { publicada && sigue() }
    }

    /**
     * Sincroniza con [lista] si la pantalla ya la trajo (así no se pide dos veces) o pidiéndola. Si ya hay
     * una sincronización en curso **de la misma cuenta**, devuelve su resultado. Una de la cuenta anterior
     * no se comparte: va a descartar lo que traiga, y la cuenta nueva necesita la suya.
     */
    suspend fun sincronizar(lista: List<PolizaResumen>? = null): ResultadoSincronizacion {
        val trabajo = synchronized(candadoEnCurso) {
            val cliente = cuenta.clienteId
            val actual = generacion.get()
            enCurso?.takeIf { it.trabajo.isActive && it.cliente == cliente && it.generacion == actual }?.trabajo
                ?: alcance.async { ejecutar(lista) }.also { enCurso = EnCurso(cliente, actual, it) }
        }
        return trabajo.await()
    }

    /**
     * Una vigía por pantalla (la pestaña Pólizas, un detalle o una credencial): recuerda la última red que
     * esa pantalla vio, también mientras está fuera de composición. Así, si la red volvió mientras el
     * cliente miraba otra pantalla, al regresar se trata como una reconexión.
     */
    fun vigia(): VigiaConexion = VigiaConexion(monitor.conectado) { cuenta.clienteId != null }

    /** `ObservadorCuenta.antes`: corre antes de que cambie la sesión (otra cuenta, cierre o vencimiento). */
    fun cambioDeCuenta() {
        generacion.incrementAndGet()
        desbloqueadaSinSesion = false
    }

    // ── Entrada sin sesión (MOB-F03, entrega 2, §7) ──────────────────────────────────────────

    /**
     * Si Ingreso puede ofrecer «Ver mis pólizas sin conexión»: no hay sesión y hay una billetera legible. Un
     * archivo ilegible se borra al leerlo, como siempre.
     */
    suspend fun hayBilleteraSinSesion(): Boolean {
        if (borradoPendiente || cuenta.clienteId != null) return false
        val guardada = almacen.leer { true } != null
        return guardada && !borradoPendiente && cuenta.clienteId == null
    }

    /**
     * El cliente superó el desbloqueo del teléfono. Solo sin sesión: con una sesión abierta, la billetera se
     * ve en la pestaña Pólizas. @return true si quedó desbloqueada.
     */
    fun abrirSinSesion(): Boolean {
        if (borradoPendiente || cuenta.clienteId != null) return false
        desbloqueadaSinSesion = true
        sincronizarAlIngresar = true
        return true
    }

    /** Se volvió a Ingreso: la próxima entrada sin sesión pide otra vez el desbloqueo. */
    fun cerrarSinSesion() {
        desbloqueadaSinSesion = false
    }

    /** Si la entrada sin sesión está desbloqueada; la navegación no muestra la billetera si no lo está. */
    val sinSesionDesbloqueada: Boolean get() = desbloqueadaSinSesion && !borradoPendiente && cuenta.clienteId == null

    /**
     * La billetera del último cliente que sincronizó, sin sesión y solo si el desbloqueo se superó. En el
     * teléfono hay una sola billetera, la de ese cliente: su dueño es el del archivo. La de una cuenta que
     * cerró sesión ya no está, porque cerrar sesión la borra.
     */
    suspend fun cargarSinSesion(): BilleteraGuardada? {
        if (!sinSesionDesbloqueada) return null
        val billetera = almacen.leer { true }
        return billetera.takeIf { sinSesionDesbloqueada }
    }

    /**
     * `ObservadorCuenta.despues`: al entrar otra cuenta se borra una billetera de otro dueño antes de
     * mostrar nada. Con null (cierre o vencimiento) no se borra: el vencimiento y el cierre del proceso
     * son justo los casos que cubre la billetera; el cierre de sesión borra con [cerrarSesion].
     */
    fun cuentaCambiada(cliente: String?) {
        _estado.update { if (cliente != null && it.guardada?.clienteId == cliente) it else it.copy(guardada = null) }
        if (cliente == null) return
        // Al ingresar desde la billetera sin sesión se sincroniza enseguida (§7), después del borrado.
        val sincronizar = sincronizarAlIngresar.also { sincronizarAlIngresar = false }
        alcance.launch {
            almacen.borrarSiNoEsDe(cliente)
            if (sincronizar) sincronizar()
        }
    }

    /** El cliente cierra sesión: sus pólizas dejan de estar disponibles sin conexión en este teléfono. */
    fun cerrarSesion() {
        borradoPendiente = true
        generacion.incrementAndGet()
        desbloqueadaSinSesion = false
        sincronizarAlIngresar = false
        _estado.update { it.copy(guardada = null) }
        alcance.launch {
            almacen.borrar()
            borradoPendiente = false
        }
    }

    private suspend fun ejecutar(dada: List<PolizaResumen>?): ResultadoSincronizacion {
        val cliente = cuenta.clienteId ?: return ResultadoSincronizacion.Omitida
        val inicio = generacion.get()
        _estado.update { it.copy(sincronizando = true) }
        return try {
            val sigue = { generacion.get() == inicio && cuenta.clienteId == cliente }
            val lista = dada ?: pedir(sigue) { api.listarPolizas() }
            // De a uno; el primer fallo (5xx, sin red o un 404 de una póliza que desapareció entre las dos
            // llamadas) deja la billetera anterior. Si la cuenta cambia, no se piden los que faltan.
            val detalles = lista.filter(BilleteraGuardada::guardaDetalle)
                .map { pedir(sigue) { api.obtenerPoliza(it.numero) } }
            guardar(cliente, inicio, lista, detalles)
        } catch (ignored: FalloAlTraer) {
            fallida(ignored.resultado)
        } catch (ignored: CuentaCambiada) {
            ResultadoSincronizacion.Omitida
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
        // La cuenta pudo cambiar entre la escritura y este punto: entonces la billetera ya no se publica (la
        // borra el observador, que llega después) y nadie de la cuenta nueva la ve.
        val publicada = guardada && publicarSi(vigente) { it.copy(guardada = billetera) }
        return when {
            publicada -> ResultadoSincronizacion.Completa(billetera)
            !vigente() -> ResultadoSincronizacion.Omitida
            else -> ResultadoSincronizacion.Fallida()
        }
    }

    /**
     * Aplica [cambio] solo si [vigente] sigue siendo cierta. La comprobación va dentro del `update`: si el
     * observador de cuenta cambia el estado entre la comprobación y la escritura, el `update` reintenta y
     * vuelve a comprobar. @return true si se aplicó.
     */
    private fun publicarSi(vigente: () -> Boolean, cambio: (EstadoBilletera) -> EstadoBilletera): Boolean {
        var aplicado = false
        _estado.update { actual ->
            aplicado = vigente()
            if (aplicado) cambio(actual) else actual
        }
        return aplicado
    }

    private suspend fun <T> pedir(sigue: () -> Boolean, llamada: suspend () -> T): T {
        if (!sigue()) throw CuentaCambiada()
        return when (val resultado = llamarBff(llamada)) {
            is Resultado.Exito -> resultado.valor
            else -> throw FalloAlTraer(resultado)
        }
    }

    /** Una llamada de la sincronización no llegó bien: corta el resto. */
    private class FalloAlTraer(val resultado: Resultado<*>) : Exception()

    /** La cuenta cambió a mitad de la sincronización: no se piden los detalles que faltan. */
    private class CuentaCambiada : Exception()

    private fun fallida(resultado: Resultado<*>) = ResultadoSincronizacion.Fallida(
        sesionVencida = resultado is Resultado.Falla && resultado.http == HTTP_NO_AUTENTICADO,
    )
}

/**
 * Lo último que una pantalla vio de la red (MOB-F03, §2 y §4). [vigilar] avisa cuando la red se pierde y
 * cuando vuelve con una sesión vigente. Lo visto se conserva entre composiciones: una pantalla que vuelve a
 * componerse después de que la red cambió recibe el aviso que se perdió.
 */
class VigiaConexion internal constructor(
    private val conectado: StateFlow<Boolean>,
    private val conSesion: () -> Boolean,
) {
    private var visto = conectado.value

    suspend fun vigilar(alPerder: suspend () -> Unit, alReconectar: suspend () -> Unit) {
        conectado.collect { ahora ->
            val antes = visto
            visto = ahora
            when {
                antes && !ahora -> alPerder()
                !antes && ahora && conSesion() -> alReconectar()
            }
        }
    }
}
