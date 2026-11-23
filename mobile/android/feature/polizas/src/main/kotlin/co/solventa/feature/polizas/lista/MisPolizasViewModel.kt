package co.solventa.feature.polizas.lista

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.core.network.BffApi
import co.solventa.core.network.EstadoPoliza
import co.solventa.core.network.PolizaResumen
import co.solventa.core.network.Resultado
import co.solventa.core.network.llamarBff
import co.solventa.feature.polizas.billetera.BilleteraGuardada
import co.solventa.feature.polizas.billetera.ResultadoSincronizacion
import co.solventa.feature.polizas.billetera.SincronizadorBilletera
import co.solventa.feature.polizas.comun.FalloConsulta
import co.solventa.feature.polizas.comun.HTTP_NO_AUTENTICADO
import co.solventa.feature.polizas.comun.Pestana
import co.solventa.feature.polizas.comun.fallo
import co.solventa.feature.polizas.comun.sinConexion
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** De dónde salen las pólizas en pantalla (MOB-F03, §4). */
enum class ModoPolizas {
    /** Del BFF, como en #190. */
    EN_LINEA,

    /** De la billetera: no hay red. Franja «Sin conexión · datos del …». */
    SIN_CONEXION,

    /** De la billetera: hay red pero el BFF no respondió a tiempo, o falló la sincronización al reconectar. */
    SIN_ACTUALIZAR,

    /** Sin conexión y sin billetera guardada: «Necesitas conexión una vez…». */
    SIN_BILLETERA,
}

data class EstadoMisPolizas(
    /** Las del BFF; null mientras no haya una primera respuesta. */
    val polizas: List<PolizaResumen>? = null,
    val cargando: Boolean = true,
    /** La consulta falló; si ya había una lista, se sigue mostrando con el aviso y «Reintentar». */
    val error: FalloConsulta? = null,
    /** El BFF respondió 401 y la sesión no se pudo renovar: la pantalla delega al flujo de sesión. */
    val sesionVencida: Boolean = false,
    val pestana: Pestana = Pestana.ACTIVAS,
    val modo: ModoPolizas = ModoPolizas.EN_LINEA,
    /** La billetera del cliente; null si nunca hubo una sincronización completa. */
    val billetera: BilleteraGuardada? = null,
    /** Ya se leyó la billetera del disco: antes no se sabe si está disponible sin conexión. */
    val billeteraLeida: Boolean = false,
    /** Volvió la conexión y se está sincronizando: la lista guardada sigue visible con el indicador. */
    val sincronizando: Boolean = false,
    /** La fecha del teléfono, para la vigencia sin conexión (§5). */
    val hoy: LocalDate = LocalDate.now(),
) {
    /** Se muestra la billetera guardada en lugar de la respuesta del BFF. */
    val sinConexion: Boolean
        get() = modo == ModoPolizas.SIN_CONEXION || modo == ModoPolizas.SIN_ACTUALIZAR

    /**
     * Las que se muestran, en el orden del BFF (de la más reciente a la más antigua). Sin conexión, con el
     * estado según la última sincronización (§5).
     */
    val mostradas: List<PolizaResumen>?
        get() = when (modo) {
            ModoPolizas.EN_LINEA -> polizas
            ModoPolizas.SIN_CONEXION, ModoPolizas.SIN_ACTUALIZAR -> billetera?.polizas?.map { it.sinConexion(hoy) }
            ModoPolizas.SIN_BILLETERA -> null
        }

    /** Las de la pestaña elegida. */
    val visibles: List<PolizaResumen>
        get() = mostradas.orEmpty().filter(pestana::incluye)

    /** Ya hay una lista y se está consultando otra: se muestra la anterior con un indicador. */
    val actualizando: Boolean
        get() = cargando && mostradas != null

    /** Con conexión, bajo el título: desde cuándo está disponible sin conexión, o que aún no lo está. */
    val muestraLineaSincronizacion: Boolean
        get() = modo == ModoPolizas.EN_LINEA && polizas != null && billeteraLeida

    /** «Ver credencial» solo en las que se muestran vigentes. */
    fun ofreceCredencial(poliza: PolizaResumen): Boolean = poliza.estado == EstadoPoliza.VIGENTE
}

/**
 * «Mis pólizas» (WEB-F04) con la billetera sin conexión (MOB-F03). Con conexión los datos vienen del BFF
 * y, cada vez que la lista llega bien, se sincroniza la billetera. Sin conexión se muestra la billetera
 * guardada. Rotar la pantalla o volver del segundo plano no consulta (criterio 11 de #190).
 *
 * Política de refresco (§2 de #190): cada vez que el cliente entra a la pestaña Pólizas desde la barra
 * inferior, la app sube un contador de [entradaInicial] y lo pasa a [alEntrar], que consulta otra vez si
 * cambió. La pantalla se recompone sin cambiarlo (rotación, regreso del detalle), así que no consulta.
 */
class MisPolizasViewModel(
    private val api: BffApi,
    private val billetera: SincronizadorBilletera,
    entradaInicial: Int = 0,
    private val reloj: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    var estado by mutableStateOf(EstadoMisPolizas(hoy = LocalDate.now(reloj)))
        private set
    private var consulta: Job? = null
    private var ultimaEntrada = entradaInicial
    private val vigia = billetera.vigia()

    init {
        // La fecha de sincronización se actualiza sola cuando termina una sincronización en segundo plano.
        viewModelScope.launch {
            billetera.estado.collect { estado = estado.copy(billetera = it.guardada) }
        }
        viewModelScope.launch {
            billetera.cargar()
            estado = estado.copy(billeteraLeida = true)
        }
        actualizar()
    }

    /** Primera carga, «Actualizar», deslizar hacia abajo y «Reintentar». */
    fun actualizar() {
        if (consulta?.isActive == true) return
        estado = estado.copy(cargando = true, error = null, hoy = LocalDate.now(reloj))
        consulta = viewModelScope.launch {
            val resultado = llamarBff { api.listarPolizas() }
            estado = when {
                // Se respeta el orden del BFF: de la más reciente a la más antigua, como en la web.
                resultado is Resultado.Exito -> {
                    sincronizar(resultado.valor)
                    estado.copy(polizas = resultado.valor, cargando = false, modo = ModoPolizas.EN_LINEA)
                }
                resultado is Resultado.Falla && resultado.http == HTTP_NO_AUTENTICADO ->
                    estado.copy(cargando = false, sesionVencida = true)
                resultado == Resultado.SinConexion -> sinRespuesta()
                // 5xx o respuesta ilegible: lo de #190, sin la billetera.
                else -> estado.copy(
                    cargando = false,
                    error = resultado.fallo(),
                    modo = ModoPolizas.EN_LINEA,
                    polizas = estado.polizas.takeIf { estado.modo == ModoPolizas.EN_LINEA },
                )
            }
        }
    }

    /** El cliente entró a la pestaña desde la barra inferior: si es una entrada nueva, se consulta. */
    fun alEntrar(entrada: Int) {
        if (entrada == ultimaEntrada) return
        ultimaEntrada = entrada
        actualizar()
    }

    /**
     * Mientras la pantalla esté visible: si se pierde la red se pasa a la billetera, y al volver con sesión
     * se sincroniza sola (§2). Un cambio que llega con una consulta en curso espera a que termine.
     */
    suspend fun vigilarConexion() = vigia.vigilar(
        alPerder = {
            consulta?.join()
            alPerder()
        },
        alReconectar = {
            consulta?.join()
            alReconectar()
        },
    )

    /**
     * Se perdió la red con la lista del BFF en pantalla: si hay billetera, se muestra con la franja «Sin
     * conexión · datos del …» (§4). Sin billetera queda la lista como estaba.
     */
    fun alPerder() {
        if (consulta?.isActive == true || estado.modo == ModoPolizas.SIN_BILLETERA) return
        consulta = viewModelScope.launch {
            val guardada = billetera.cargar() ?: return@launch
            estado = estado.copy(
                modo = ModoPolizas.SIN_CONEXION,
                billetera = guardada,
                billeteraLeida = true,
                error = null,
                cargando = false,
                hoy = LocalDate.now(reloj),
            )
        }
    }

    /**
     * Volvió la conexión. Con la billetera en pantalla se sincroniza y, mientras tanto, se sigue mostrando
     * con el indicador; si falla, queda la billetera con «No pudimos actualizar». Sin billetera, o con la
     * lista del BFF en pantalla, se consulta la lista como siempre, que también sincroniza.
     */
    fun alReconectar() {
        if (consulta?.isActive == true) return
        when {
            !estado.sinConexion -> actualizar()
            else -> {
                estado = estado.copy(sincronizando = true, hoy = LocalDate.now(reloj))
                consulta = viewModelScope.launch {
                    val resultado = billetera.sincronizar()
                    estado = when (resultado) {
                        is ResultadoSincronizacion.Completa -> estado.copy(
                            polizas = resultado.billetera.polizas,
                            billetera = resultado.billetera,
                            modo = ModoPolizas.EN_LINEA,
                            error = null,
                            sincronizando = false,
                        )
                        is ResultadoSincronizacion.Fallida -> estado.copy(
                            modo = ModoPolizas.SIN_ACTUALIZAR,
                            sesionVencida = resultado.sesionVencida,
                            sincronizando = false,
                        )
                        ResultadoSincronizacion.Omitida -> estado.copy(sincronizando = false)
                    }
                }
            }
        }
    }

    fun elegir(pestana: Pestana) {
        estado = estado.copy(pestana = pestana)
    }

    fun sesionAtendida() {
        estado = estado.copy(sesionVencida = false)
    }

    /**
     * `SinConexion`: sin red o tiempo agotado (`llamarBff` no los distingue); el monitor dice cuál (§4).
     * Con la lista del BFF ya en pantalla y sin billetera se queda la lista con el aviso, como en #190.
     */
    private suspend fun sinRespuesta(): EstadoMisPolizas {
        val guardada = billetera.cargar()
        val base = estado.copy(cargando = false, billetera = guardada, billeteraLeida = true, error = null)
        return when {
            guardada != null -> base.copy(
                modo = if (billetera.conectado.value) ModoPolizas.SIN_ACTUALIZAR else ModoPolizas.SIN_CONEXION,
            )
            estado.modo == ModoPolizas.EN_LINEA && estado.polizas != null -> base.copy(error = FalloConsulta.SIN_RED)
            else -> base.copy(modo = ModoPolizas.SIN_BILLETERA)
        }
    }

    /** Cada vez que la lista llega bien se sincroniza la billetera, sin bloquear la pantalla. */
    private fun sincronizar(lista: List<PolizaResumen>) {
        viewModelScope.launch { billetera.sincronizar(lista) }
    }
}
