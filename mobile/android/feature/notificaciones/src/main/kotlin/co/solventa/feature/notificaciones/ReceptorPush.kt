package co.solventa.feature.notificaciones

import co.solventa.core.network.BffApi
import co.solventa.core.network.Notificacion
import co.solventa.core.network.Resultado
import co.solventa.core.network.SesionBff
import co.solventa.core.network.SolicitudDispositivo
import co.solventa.core.network.llamarBff
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Lo que el receptor guarda en el teléfono. */
interface AlmacenPush {
    /** Identificador estable de la instalación: es el `token_push` mientras no haya Firebase. */
    fun tokenInstalacion(): String

    /** Ids ya atendidos de la bandeja de [cliente]; null si esta instalación aún no la ha leído. */
    fun vistas(cliente: String): Set<String>?

    fun guardarVistas(cliente: String, ids: Set<String>)

    /** Ya se pidió el permiso de notificaciones (o el cliente dijo «Ahora no»). */
    fun permisoPedido(): Boolean

    fun marcarPermisoPedido()
}

/** Muestra la notificación local; false si el cliente negó el permiso o las apagó. */
fun interface Notificador {
    fun mostrar(notificacion: Notificacion): Boolean
}

/**
 * Recepción del push con el proveedor simulado (MOB-F06, ver "Push" en `contratos/bff-movil`):
 *
 * - Con sesión, registra el dispositivo una vez por proceso y cliente (criterio 4). Si no hay red, lo
 *   vuelve a intentar en la siguiente vuelta.
 * - Mientras el proceso vive, también en segundo plano, lee la bandeja cada [intervalo] y muestra como
 *   notificación local cada una sin leer que esta instalación no haya atendido (criterio 1).
 * - La primera lectura de la bandeja en esta instalación no muestra nada: solo marca lo que ya había,
 *   para no inundar al cliente con avisos viejos al reinstalar. El estado se ve en el seguimiento.
 * - Sin permiso no se muestra, pero la notificación igual queda atendida: no aparece de golpe al darlo.
 *
 * @param sesion la de la app: la vuelta termina en cuanto cambia su `clienteId`, y su token de acceso da
 * de baja el dispositivo al cerrarla.
 */
class ReceptorPush(
    private val api: BffApi,
    private val almacen: AlmacenPush,
    private val notificador: Notificador,
    private val sesion: SesionBff,
    private val alcance: CoroutineScope,
    private val intervalo: Duration = INTERVALO,
) {
    // Un vencimiento de sesión llega desde un hilo de OkHttp y detiene la vuelta.
    @Volatile private var activo: String? = null
    @Volatile private var vuelta: Job? = null
    @Volatile private var dispositivo: String? = null
    private val ahora = Channel<Unit>(Channel.CONFLATED)

    /** Empieza a recibir para [cliente]; si ya recibía para él, lee la bandeja enseguida (p. ej. al volver). */
    fun activar(cliente: String) {
        if (activo == cliente && vuelta?.isActive == true) {
            ahora.trySend(Unit)
            return
        }
        detener()
        activo = cliente
        vuelta = alcance.launch {
            while (isActive && sesion.clienteId == cliente) {
                if (dispositivo == null) registrar()
                sincronizar(cliente)
                withTimeoutOrNull(intervalo.toMillis()) { ahora.receive() }
            }
        }
    }

    /** Al cambiar de cuenta o cerrar sesión: no se lee más la bandeja de la anterior. */
    fun detener() {
        vuelta?.cancel()
        vuelta = null
        activo = null
    }

    /**
     * Al cerrar sesión, antes de olvidarla: da de baja el dispositivo con el token que aún sirve, para que
     * la cuenta no reciba más push en este teléfono. Si falla (sin red), el registro siguiente lo reasigna.
     */
    fun darDeBaja() {
        val id = dispositivo
        val token = sesion.tokenAcceso
        dispositivo = null
        detener()
        if (id == null || token == null) return
        alcance.launch { llamarBff { api.darDeBajaDispositivo(id, "Bearer $token") } }
    }

    /** El cliente tocó la notificación: queda leída. Si falla, la app la vuelve a ignorar por estar vista. */
    fun marcarLeida(idNotificacion: String) {
        alcance.launch { llamarBff { api.marcarNotificacionLeida(idNotificacion) } }
    }

    private suspend fun registrar() {
        val resultado = llamarBff { api.registrarDispositivo(SolicitudDispositivo(almacen.tokenInstalacion())) }
        if (resultado is Resultado.Exito) dispositivo = resultado.valor.id
    }

    /** Una lectura de la bandeja. Las nuevas se muestran de la más antigua a la más reciente. */
    internal suspend fun sincronizar(cliente: String) {
        val bandeja = (llamarBff { api.listarNotificaciones() } as? Resultado.Exito)?.valor ?: return
        if (sesion.clienteId != cliente) return
        val vistas = almacen.vistas(cliente)
        if (vistas != null) {
            bandeja.asReversed()
                .filter { !it.leida && it.id !in vistas }
                .forEach { notificador.mostrar(it) }
        }
        // La bandeja trae las más recientes: lo que sale de ella ya no vuelve, así que basta guardarla.
        almacen.guardarVistas(cliente, bandeja.mapTo(mutableSetOf()) { it.id })
    }

    companion object {
        /** El contrato pide «cada pocos minutos»; con la app abierta conviene que se note pronto. */
        val INTERVALO: Duration = Duration.ofSeconds(30)
    }
}
