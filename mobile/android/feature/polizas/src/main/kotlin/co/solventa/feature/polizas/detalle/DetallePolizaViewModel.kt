package co.solventa.feature.polizas.detalle

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.core.network.BffApi
import co.solventa.core.network.EstadoPoliza
import co.solventa.core.network.PolizaDetalle
import co.solventa.core.network.Resultado
import co.solventa.core.network.llamarBff
import co.solventa.feature.polizas.billetera.ResultadoSincronizacion
import co.solventa.feature.polizas.billetera.SincronizadorBilletera
import co.solventa.feature.polizas.comun.FalloConsulta
import co.solventa.feature.polizas.comun.HTTP_NO_AUTENTICADO
import co.solventa.feature.polizas.comun.HTTP_NO_ENCONTRADO
import co.solventa.feature.polizas.comun.fallo
import co.solventa.feature.polizas.comun.estadoSinConexion
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Aviso breve (snackbar) de la descarga del documento. */
enum class AvisoPoliza(
    /** El aviso ofrece «Reintentar» la descarga. */
    val reintentable: Boolean = false,
) {
    /** 404 `documento_no_disponible`: además se oculta el botón. */
    DOCUMENTO_NO_DISPONIBLE,

    /** Sin red o tiempo agotado. */
    DESCARGA_SIN_RED(reintentable = true),

    /** 5xx o una respuesta que no es del contrato. */
    DESCARGA_FALLIDA(reintentable = true),

    /** No hay una app para abrir el PDF. */
    SIN_VISOR,
}

data class EstadoDetallePoliza(
    val detalle: PolizaDetalle? = null,
    val cargando: Boolean = true,
    val error: FalloConsulta? = null,
    /** 404: no existe o es de otro cliente; se tratan igual. */
    val noEncontrada: Boolean = false,
    val sesionVencida: Boolean = false,
    val descargando: Boolean = false,
    /** El BFF respondió `documento_no_disponible` aunque el detalle traía `documento_url`. */
    val documentoNoDisponible: Boolean = false,
    val aviso: AvisoPoliza? = null,
    /** PDF recién descargado que la pantalla abre con el visor; lo consume [DetallePolizaViewModel.pdfAbierto]. */
    val pdf: String? = null,
    /**
     * Sin conexión (MOB-F03): el detalle viene de la billetera, con el estado según la última
     * sincronización. [sinActualizar]: hay red pero el BFF no respondió a tiempo.
     */
    val sinConexion: Boolean = false,
    val sinActualizar: Boolean = false,
    /** Fecha de la última sincronización completa, para la franja. */
    val sincronizadaEn: Instant? = null,
    /** Volvió la conexión y se está sincronizando. */
    val sincronizando: Boolean = false,
    /** Sin conexión y la póliza no tiene detalle guardado (una que ya llegó vencida del BFF). */
    val detalleNoGuardado: Boolean = false,
) {
    /** «Descargar PDF» solo si la póliza tiene documento y hay conexión. */
    val ofreceDescarga: Boolean
        get() = detalle?.documentoUrl != null && !documentoNoDisponible && !sinConexion

    /** Sin conexión, el botón queda inactivo con «Necesitas conexión para descargar el documento». */
    val descargaRequiereConexion: Boolean
        get() = sinConexion && detalle?.documentoUrl != null && !documentoNoDisponible

    /** «Ver credencial» solo si la póliza se muestra vigente. */
    val ofreceCredencial: Boolean
        get() = detalle?.estado == EstadoPoliza.VIGENTE
}

/**
 * Detalle de la póliza (WEB-F04): condiciones, coberturas y documento. Recibe el número por la ruta
 * (`policies/detalle/{numero}`), que la navegación deja en [estadoGuardado]. Sin conexión muestra el
 * detalle guardado en la billetera (MOB-F03), aunque la póliza haya vencido por fecha después de
 * sincronizar. El PDF que el cliente pide queda en la caché de la app.
 */
@Suppress("LongParameterList")
class DetallePolizaViewModel(
    private val api: BffApi,
    estadoGuardado: SavedStateHandle,
    private val documentos: DocumentosPoliza,
    private val billetera: SincronizadorBilletera,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val reloj: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    val numero: String = requireNotNull(estadoGuardado.get<String>(ARGUMENTO_NUMERO)) { "Falta el número de la póliza" }

    var estado by mutableStateOf(EstadoDetallePoliza())
        private set
    private var consulta: Job? = null
    private var descarga: Job? = null

    init {
        consultar()
    }

    /** Primera carga y «Reintentar». */
    fun consultar() {
        if (consulta?.isActive == true) return
        estado = estado.copy(cargando = true, error = null)
        consulta = viewModelScope.launch {
            val resultado = llamarBff { api.obtenerPoliza(numero) }
            estado = when {
                resultado is Resultado.Exito -> enLinea(resultado.valor)
                resultado is Resultado.Falla && resultado.http == HTTP_NO_AUTENTICADO ->
                    estado.copy(cargando = false, sesionVencida = true)
                resultado is Resultado.Falla && resultado.http == HTTP_NO_ENCONTRADO ->
                    estado.copy(cargando = false, noEncontrada = true)
                resultado == Resultado.SinConexion -> desdeBilletera()
                else -> estado.copy(cargando = false, error = resultado.fallo())
            }
        }
    }

    /** Mientras la pantalla esté visible: al volver la conexión con sesión, se sincroniza sola (§2). */
    suspend fun vigilarReconexion() = billetera.vigilarReconexion { alReconectar() }

    /**
     * Volvió la conexión. Sin conexión en pantalla se sincroniza la billetera mostrando lo guardado con el
     * indicador; si termina bien se vuelve a consultar el detalle, y si falla queda «No pudimos actualizar».
     */
    fun alReconectar() {
        if (consulta?.isActive == true) return
        if (!estado.sinConexion && !estado.detalleNoGuardado) {
            viewModelScope.launch { billetera.sincronizar() }
            return
        }
        estado = estado.copy(sincronizando = true)
        consulta = viewModelScope.launch {
            val resultado = billetera.sincronizar()
            estado = estado.copy(sincronizando = false)
            when (resultado) {
                is ResultadoSincronizacion.Completa -> {
                    consulta = null
                    consultar()
                }
                is ResultadoSincronizacion.Fallida -> estado = estado.copy(
                    sinActualizar = estado.sinConexion,
                    sesionVencida = resultado.sesionVencida,
                )
                ResultadoSincronizacion.Omitida -> Unit
            }
        }
    }

    private fun enLinea(detalle: PolizaDetalle) = estado.copy(
        detalle = detalle,
        cargando = false,
        noEncontrada = false,
        error = null,
        sinConexion = false,
        sinActualizar = false,
        detalleNoGuardado = false,
    )

    /** `SinConexion` (sin red o tiempo agotado): el detalle guardado, si lo hay (§4). */
    private suspend fun desdeBilletera(): EstadoDetallePoliza {
        val guardada = billetera.cargar()
        val detalle = guardada?.detalle(numero)
        return when {
            guardada == null || detalle == null -> estado.copy(
                cargando = false,
                // Si ya se veía el detalle del BFF, se queda con el aviso, como en #190.
                error = FalloConsulta.SIN_RED.takeIf { estado.detalle != null && !estado.sinConexion },
                detalleNoGuardado = estado.detalle == null || estado.sinConexion,
            )
            else -> estado.copy(
                detalle = detalle.copy(estado = estadoSinConexion(detalle.resumen, LocalDate.now(reloj))),
                cargando = false,
                error = null,
                sinConexion = true,
                sinActualizar = billetera.conectado.value,
                sincronizadaEn = guardada.momento,
                detalleNoGuardado = false,
            )
        }
    }

    /**
     * «Descargar PDF» (y «Reintentar» del aviso). Va por el BFF con la sesión del cliente, nunca con una
     * URL del almacén; mientras descarga, el botón no se puede volver a tocar.
     */
    fun descargar() {
        if (!estado.ofreceDescarga || descarga?.isActive == true) return
        estado = estado.copy(descargando = true, aviso = null)
        descarga = viewModelScope.launch {
            // La lectura del cuerpo es red: va entera en [io], con la escritura del archivo.
            val resultado = withContext(io) {
                llamarBff { api.descargarDocumentoPoliza(numero).use { documentos.guardar(numero, it.byteStream()) } }
            }
            estado = estado.copy(descargando = false)
            estado = when (resultado) {
                is Resultado.Exito -> estado.copy(pdf = resultado.valor)
                is Resultado.Falla -> conFallaDescarga(resultado)
                Resultado.SinConexion -> estado.copy(aviso = AvisoPoliza.DESCARGA_SIN_RED)
            }
        }
    }

    /** Tabla de errores de la descarga (§3): se decide por `codigo`, no solo por el estado HTTP. */
    private fun conFallaDescarga(falla: Resultado.Falla): EstadoDetallePoliza = when {
        falla.codigo == "documento_no_disponible" ->
            estado.copy(documentoNoDisponible = true, aviso = AvisoPoliza.DOCUMENTO_NO_DISPONIBLE)
        falla.http == HTTP_NO_ENCONTRADO -> estado.copy(noEncontrada = true)
        falla.http == HTTP_NO_AUTENTICADO -> estado.copy(sesionVencida = true)
        else -> estado.copy(aviso = AvisoPoliza.DESCARGA_FALLIDA)
    }

    /** La pantalla intentó abrir el PDF; sin un visor instalado se avisa. */
    fun pdfAbierto(abierto: Boolean) {
        estado = estado.copy(pdf = null, aviso = if (abierto) estado.aviso else AvisoPoliza.SIN_VISOR)
    }

    fun avisoMostrado() {
        estado = estado.copy(aviso = null)
    }

    fun sesionAtendida() {
        estado = estado.copy(sesionVencida = false)
    }

    companion object {
        /** Argumento de la ruta del detalle; la navegación lo deja en el SavedStateHandle. */
        const val ARGUMENTO_NUMERO = "numero"
    }
}
