package co.solventa.feature.siniestros.reporte

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.core.network.BffApi
import co.solventa.core.network.Dinero
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.PolizaResumen
import co.solventa.core.network.Resultado
import co.solventa.core.network.Siniestro
import co.solventa.core.network.SolicitudSiniestro
import co.solventa.core.network.TipoEventoSiniestro
import co.solventa.core.network.llamarBff
import co.solventa.feature.siniestros.R
import co.solventa.feature.siniestros.evidencia.FuentesEvidencia
import co.solventa.feature.siniestros.evidencia.GestorEvidencia
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.launch

/** Mensaje general del asistente tras un rechazo del BFF que no es de un campo. */
sealed interface Aviso {
    /** 409 `evento_fuera_de_vigencia`: no quedó registro; las fechas son las de la póliza. */
    data class FueraDeVigencia(val desde: LocalDate?, val hasta: LocalDate?) : Aviso

    /** 404 `poliza_no_encontrada`: la lista se refrescó y hay que elegir de nuevo. */
    data object PolizaNoEncontrada : Aviso

    /** 409 `clave_idempotencia_en_uso`: ya hay una clave nueva y el cliente confirma el envío otra vez. */
    data object ConfirmarDeNuevo : Aviso

    /** Sin red o tiempo agotado: el reintento usa la misma clave y no duplica el reporte. */
    data object SinConexion : Aviso

    data object Generico : Aviso
}

data class EstadoReporte(
    val hoy: LocalDate,
    val paso: PasoReporte = PasoReporte.POLIZA,
    val polizas: List<PolizaResumen> = emptyList(),
    val cargandoPolizas: Boolean = true,
    @StringRes val errorCarga: Int? = null,
    val datos: DatosReporte = DatosReporte(),
    /** Campos cuyo error ya se muestra: tras salir del campo o al intentar avanzar (P06). */
    val visibles: Set<Campo> = emptySet(),
    val erroresServidor: Map<Campo, ProblemaCampo> = emptyMap(),
    /** `Idempotency-Key` del reporte: nace al entrar a la revisión y se reutiliza en cada reintento. */
    val claveIdempotencia: String? = null,
    val enviando: Boolean = false,
    /**
     * Hubo un envío sin respuesta (sin red, tiempo agotado o error del servidor): quizá quedó
     * registrado. Los datos quedan fijos hasta conocer el resultado, porque reenviar otros datos
     * con la misma clave daría `clave_idempotencia_en_uso` y, con una clave nueva, un duplicado.
     */
    val envioPendiente: Boolean = false,
    val aviso: Aviso? = null,
    /** Reporte aceptado; la pantalla muestra la confirmación. */
    val siniestro: Siniestro? = null,
    /** El BFF respondió 401: la pantalla delega al flujo de sesión. */
    val sesionVencida: Boolean = false,
) {
    fun error(campo: Campo): ProblemaCampo? =
        erroresServidor[campo] ?: ValidadorReporte.validar(campo, datos, hoy).takeIf { campo in visibles }

    /** Sin pólizas no hay qué reportar: la app lo explica y no muestra el formulario (criterio 5). */
    val sinPolizas: Boolean
        get() = !cargandoPolizas && errorCarga == null && polizas.isEmpty()

    val polizaElegida: PolizaResumen?
        get() = polizas.firstOrNull { it.numero == datos.numeroPoliza }

    val puedeCorregir: Boolean
        get() = !enviando && !envioPendiente
}

/**
 * Asistente de reporte (WEB-F05 en la app): póliza, información y revisión. Lo ingresado, la
 * clave y la confirmación se guardan en [borrador], así sobreviven a la rotación y a la muerte
 * del proceso en segundo plano (criterio 6).
 */
class ReporteViewModel(
    private val api: BffApi,
    private val borrador: SavedStateHandle = SavedStateHandle(),
    private val hoy: () -> LocalDate = LocalDate::now,
    private val nuevaClave: () -> String = { UUID.randomUUID().toString() },
    fuentes: FuentesEvidencia = FuentesEvidencia(),
) : ViewModel() {
    private var actual by mutableStateOf(restaurar(borrador, hoy()))

    var estado: EstadoReporte
        get() = actual
        private set(valor) {
            actual = valor
            guardar(borrador, valor)
        }

    /** El paso de evidencia y su carga, que empieza cuando el siniestro queda reportado (MOB-F05). */
    val evidencia = GestorEvidencia(api, fuentes, viewModelScope, borrador)

    init {
        val reportado = estado.siniestro
        if (reportado == null) cargarPolizas() else evidencia.reanudar(reportado.id)
    }

    fun cargarPolizas() {
        estado = estado.copy(cargandoPolizas = true, errorCarga = null)
        viewModelScope.launch {
            val resultado = llamarBff { api.listarPolizas() }
            estado = when (resultado) {
                is Resultado.Exito -> {
                    val polizas = resultado.valor
                    // Una póliza restaurada que ya no está en la lista deja de estar elegida.
                    val numero = estado.datos.numeroPoliza?.takeIf { n -> polizas.any { it.numero == n } }
                    val datos = estado.datos.copy(numeroPoliza = numero)
                    estado.copy(cargandoPolizas = false, polizas = polizas, datos = datos)
                }
                is Resultado.Falla -> estado.copy(
                    cargandoPolizas = false,
                    errorCarga = R.string.error_generico,
                    sesionVencida = resultado.http == HTTP_NO_AUTENTICADO,
                )
                Resultado.SinConexion -> estado.copy(cargandoPolizas = false, errorCarga = R.string.error_sin_conexion)
            }
        }
    }

    /** Cambia un dato; un error del servidor sobre ese campo deja de aplicar. */
    fun actualizar(campo: Campo, cambio: (DatosReporte) -> DatosReporte) {
        if (!estado.puedeCorregir) return
        val errores = estado.erroresServidor - campo
        estado = estado.copy(datos = cambio(estado.datos), erroresServidor = errores, hoy = hoy())
    }

    fun salirDe(campo: Campo) {
        if (campo !in estado.visibles && estado.datos.tieneValor(campo)) {
            estado = estado.copy(visibles = estado.visibles + campo)
        }
    }

    /** Valida lo ingresado hasta el paso actual; si está completo avanza o, en la revisión, envía. */
    fun continuar() {
        if (estado.enviando) return
        val paso = estado.paso
        estado = estado.copy(visibles = estado.visibles + paso.campos, hoy = hoy())
        when {
            irAlPrimerPendiente(hasta = paso) -> Unit
            paso == PasoReporte.REVISION -> enviar()
            else -> {
                if (paso == PasoReporte.EVIDENCIA) evidencia.descartarNoCaben()
                val siguiente = PasoReporte.entries[paso.ordinal + 1]
                val clave = estado.claveIdempotencia ?: if (siguiente == PasoReporte.REVISION) nuevaClave() else null
                estado = estado.copy(paso = siguiente, claveIdempotencia = clave, aviso = null)
            }
        }
    }

    /**
     * @return false si la pantalla debe salir del asistente: desde el primer paso, o con un envío
     * pendiente, cuyos datos ya no se pueden corregir.
     */
    fun atras(): Boolean {
        val anterior = PasoReporte.entries.getOrNull(estado.paso.ordinal - 1)
        if (anterior == null || estado.envioPendiente) return false
        irA(anterior)
        return true
    }

    /** Desde la revisión, vuelve a un paso anterior para corregirlo. */
    fun irA(paso: PasoReporte) {
        if (!estado.puedeCorregir || paso.ordinal >= estado.paso.ordinal) return
        estado = estado.copy(paso = paso, aviso = null)
    }

    fun sesionAtendida() {
        estado = estado.copy(sesionVencida = false)
    }

    /** Lleva al paso del primer campo con error, incluidos los de pasos anteriores. */
    private fun irAlPrimerPendiente(hasta: PasoReporte): Boolean {
        val campos = PasoReporte.entries.filter { it.ordinal <= hasta.ordinal }.flatMap { it.campos }
        val pendiente = campos.firstOrNull { ValidadorReporte.validar(it, estado.datos, estado.hoy) != null }
            ?: campos.firstOrNull { it in estado.erroresServidor }
            ?: return false
        val paso = PasoReporte.de(pendiente)
        estado = estado.copy(paso = paso, visibles = estado.visibles + paso.campos)
        return true
    }

    private fun enviar() {
        val clave = estado.claveIdempotencia ?: nuevaClave()
        val solicitud = estado.datos.aSolicitud()
        estado = estado.copy(enviando = true, aviso = null, claveIdempotencia = clave)
        viewModelScope.launch {
            val resultado = llamarBff { api.reportarSiniestro(clave, solicitud) }
            estado = when (resultado) {
                is Resultado.Exito -> estado.copy(siniestro = resultado.valor, envioPendiente = false).also {
                    // Después del reporte: un fallo de evidencia no lo deshace.
                    evidencia.subir(resultado.valor.id)
                }
                is Resultado.Falla -> rechazado(resultado)
                Resultado.SinConexion -> estado.copy(aviso = Aviso.SinConexion, envioPendiente = true)
            }.copy(enviando = false)
            if (resultado is Resultado.Falla && resultado.codigo == POLIZA_NO_ENCONTRADA) cargarPolizas()
        }
    }

    /**
     * Los rechazos con código del contrato son definitivos: el núcleo valida la fecha, la póliza y
     * la vigencia antes de registrar, así que esa clave no dejó un reporte. Un error sin código
     * conocido (p. ej. 503) es incierto y deja el envío pendiente.
     */
    private fun rechazado(falla: Resultado.Falla): EstadoReporte {
        val definitivo = estado.copy(envioPendiente = false)
        return when {
            falla.http == HTTP_NO_AUTENTICADO -> estado.copy(sesionVencida = true)
            falla.codigo == "evento_fuera_de_vigencia" -> definitivo.copy(
                aviso = Aviso.FueraDeVigencia(fecha(falla.error?.vigenteDesde), fecha(falla.error?.vigenteHasta)),
            )
            falla.codigo == POLIZA_NO_ENCONTRADA -> definitivo.copy(
                paso = PasoReporte.POLIZA,
                datos = estado.datos.copy(numeroPoliza = null),
                aviso = Aviso.PolizaNoEncontrada,
            )
            // La clave ya cubre otro reporte: con una nueva, el cliente confirma este envío.
            falla.codigo == "clave_idempotencia_en_uso" ->
                definitivo.copy(claveIdempotencia = nuevaClave(), aviso = Aviso.ConfirmarDeNuevo)
            falla.codigo == "clave_idempotencia_invalida" ->
                definitivo.copy(claveIdempotencia = nuevaClave(), aviso = Aviso.Generico)
            else -> definitivo.conCamposRechazados(falla) ?: estado.copy(aviso = Aviso.Generico, envioPendiente = true)
        }
    }
}

private const val HTTP_NO_AUTENTICADO = 401
private const val POLIZA_NO_ENCONTRADA = "poliza_no_encontrada"

private fun fecha(texto: String?): LocalDate? = texto?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** Un 422 por campo (incluido `fecha_futura`) vuelve al paso del primer campo afectado. */
private fun EstadoReporte.conCamposRechazados(falla: Resultado.Falla): EstadoReporte? {
    val porCampo = falla.error?.campos.orEmpty().mapNotNull { error ->
        Campo.delContrato(error.campo)?.let { it to ValidadorReporte.rechazoDe(it, error.codigo) }
    }
    val fechaFutura = falla.codigo == "fecha_futura"
    val sinCampos = if (fechaFutura) listOf(Campo.FECHA to ProblemaCampo.FECHA_FUTURA) else emptyList()
    val campos = porCampo.ifEmpty { sinCampos }.toMap()
    val primero = campos.keys.minByOrNull { it.ordinal } ?: return null
    return copy(paso = PasoReporte.de(primero), erroresServidor = campos)
}

private fun DatosReporte.aSolicitud() = SolicitudSiniestro(
    numeroPoliza = requireNotNull(numeroPoliza),
    tipoEvento = requireNotNull(tipoEvento),
    ocurridoEl = requireNotNull(fecha).toString(),
    descripcion = descripcion.trim(),
    montoEstimado = ValidadorReporte.montoEnPesos(monto)?.let(Dinero::pesos),
)

// ── Borrador en SavedStateHandle ──────────────────────────────────────────────────────────────
// Solo tipos que admite un Bundle; las pólizas se vuelven a consultar.

private const val CLAVE_PASO = "paso"
private const val CLAVE_POLIZA = "poliza"
private const val CLAVE_TIPO = "tipo"
private const val CLAVE_FECHA = "fecha"
private const val CLAVE_DESCRIPCION = "descripcion"
private const val CLAVE_MONTO = "monto"
private const val CLAVE_VISIBLES = "visibles"
private const val CLAVE_IDEMPOTENCIA = "idempotencia"
private const val CLAVE_PENDIENTE = "pendiente"
private const val CLAVE_SINIESTRO = "siniestro"

private fun restaurar(borrador: SavedStateHandle, hoy: LocalDate): EstadoReporte {
    val pendiente = borrador[CLAVE_PENDIENTE] ?: false
    val siniestro = borrador.get<String>(CLAVE_SINIESTRO)?.let { json ->
        runCatching { NetworkClient.json.decodeFromString(Siniestro.serializer(), json) }.getOrNull()
    }
    return EstadoReporte(
        hoy = hoy,
        paso = borrador.get<String>(CLAVE_PASO)?.let { nombre -> PasoReporte.entries.firstOrNull { it.name == nombre } }
            ?: PasoReporte.POLIZA,
        datos = DatosReporte(
            numeroPoliza = borrador[CLAVE_POLIZA],
            tipoEvento = borrador.get<String>(CLAVE_TIPO)?.let { nombre ->
                TipoEventoSiniestro.entries.firstOrNull { it.name == nombre }
            },
            fecha = fecha(borrador[CLAVE_FECHA]),
            descripcion = borrador[CLAVE_DESCRIPCION] ?: "",
            monto = borrador[CLAVE_MONTO] ?: "",
        ),
        visibles = borrador.get<ArrayList<String>>(CLAVE_VISIBLES).orEmpty()
            .mapNotNull { nombre -> Campo.entries.firstOrNull { it.name == nombre } }
            .toSet(),
        claveIdempotencia = borrador[CLAVE_IDEMPOTENCIA],
        envioPendiente = pendiente,
        // Tras la muerte del proceso se explica por qué los datos siguen fijos.
        aviso = Aviso.Generico.takeIf { pendiente },
        siniestro = siniestro,
        cargandoPolizas = siniestro == null,
    )
}

private fun guardar(borrador: SavedStateHandle, estado: EstadoReporte) {
    val datos = estado.datos
    borrador[CLAVE_PASO] = estado.paso.name
    borrador[CLAVE_POLIZA] = datos.numeroPoliza
    borrador[CLAVE_TIPO] = datos.tipoEvento?.name
    borrador[CLAVE_FECHA] = datos.fecha?.toString()
    borrador[CLAVE_DESCRIPCION] = datos.descripcion
    borrador[CLAVE_MONTO] = datos.monto
    borrador[CLAVE_VISIBLES] = ArrayList(estado.visibles.map { it.name })
    borrador[CLAVE_IDEMPOTENCIA] = estado.claveIdempotencia
    borrador[CLAVE_PENDIENTE] = estado.envioPendiente
    borrador[CLAVE_SINIESTRO] = estado.siniestro?.let { NetworkClient.json.encodeToString(Siniestro.serializer(), it) }
}
