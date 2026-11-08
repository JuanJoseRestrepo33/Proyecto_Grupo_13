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
import co.solventa.feature.siniestros.borrador.AdjuntoBorrador
import co.solventa.feature.siniestros.borrador.Borrador
import co.solventa.feature.siniestros.borrador.EnviadorBorradores
import co.solventa.feature.siniestros.borrador.EstadoBorrador
import co.solventa.feature.siniestros.borrador.RechazoBorrador
import co.solventa.feature.siniestros.borrador.ResultadoEnvio
import co.solventa.feature.siniestros.borrador.Tarjeta
import co.solventa.feature.siniestros.evidencia.Adjunto
import co.solventa.feature.siniestros.evidencia.EstadoAdjunto
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

    /** 409 `clave_idempotencia_en_uso` y la conciliación no encontró el reporte: se confirma otra vez. */
    data object ConfirmarDeNuevo : Aviso

    /** No se pudo escribir el borrador: no se envió nada y los datos siguen aquí (M15). */
    data object NoPudimosGuardar : Aviso

    /** Un archivo ya no estaba en el teléfono y se quitó: el cliente decide antes de enviar. */
    data object ArchivoPerdido : Aviso

    data object Generico : Aviso
}

/** Después de «Enviar reporte», sin confirmación del BFF: el reporte quedó guardado en el teléfono (M4). */
enum class ReporteGuardado {
    /** Se sabía que no había red (M4a). */
    SIN_CONEXION,

    /** Se perdió la respuesta, falló el BFF o hay que confirmar un intento anterior (M4b). */
    SIN_CONFIRMAR,
}

data class EstadoReporte(
    val hoy: LocalDate,
    val paso: PasoReporte = PasoReporte.POLIZA,
    val polizas: List<PolizaResumen> = emptyList(),
    val cargandoPolizas: Boolean = true,
    @StringRes val errorCarga: Int? = null,
    /** Sin conexión, las pólizas son las de la última consulta, con su fecha (M1). */
    val polizasDel: String? = null,
    /** Sin conexión y sin pólizas guardadas: hace falta conexión una vez (M2). */
    val necesitaConexion: Boolean = false,
    val sinConexion: Boolean = false,
    val datos: DatosReporte = DatosReporte(),
    /** Campos cuyo error ya se muestra: tras salir del campo o al intentar avanzar (P06). */
    val visibles: Set<Campo> = emptySet(),
    val erroresServidor: Map<Campo, ProblemaCampo> = emptyMap(),
    /** `Idempotency-Key` del reporte: nace al entrar a la revisión y se reutiliza en cada reintento. */
    val claveIdempotencia: String? = null,
    val enviando: Boolean = false,
    val aviso: Aviso? = null,
    /**
     * Identificador opaco del borrador cifrado. Una vez guardado, el asistente ya no conserva los datos
     * en claro en su estado guardado (criterio 6): solo este identificador.
     */
    val idBorrador: String? = null,
    /** Se corrige un borrador rechazado: al enviar, sus datos se reemplazan y salen con una clave nueva. */
    val corrigiendo: Boolean = false,
    /** Archivos que el borrador ya tiene (al corregirlo); los nuevos se suman. */
    val adjuntosBorrador: List<AdjuntoBorrador> = emptyList(),
    val guardado: ReporteGuardado? = null,
    /** Archivos que quedaron en el borrador: el texto de «Reporte guardado» los cuenta (M4a). */
    val archivosGuardados: Int = 0,
    /** Reporte aceptado; la pantalla muestra la confirmación. */
    val siniestro: Siniestro? = null,
    /** El BFF respondió 401: la pantalla delega al flujo de sesión. */
    val sesionVencida: Boolean = false,
) {
    fun error(campo: Campo): ProblemaCampo? =
        erroresServidor[campo] ?: ValidadorReporte.validar(campo, datos, hoy).takeIf { campo in visibles }

    /** Sin pólizas no hay qué reportar: la app lo explica y no muestra el formulario (criterio 5). */
    val sinPolizas: Boolean
        get() = !cargandoPolizas && errorCarga == null && !necesitaConexion && polizas.isEmpty()

    val polizaElegida: PolizaResumen?
        get() = polizas.firstOrNull { it.numero == datos.numeroPoliza }

    val puedeCorregir: Boolean
        get() = !enviando
}

/**
 * Asistente de reporte (WEB-F05 en la app): póliza, información, evidencia y revisión. «Enviar reporte»
 * guarda primero un borrador cifrado y lo envía con el [enviador] (MOB-F05-02); con red y respuesta, la
 * confirmación es la de #169. Lo que se está escribiendo vive en [borrador] (rotación y muerte del
 * proceso); una vez guardado, solo el identificador del borrador.
 *
 * @param idCorregir borrador rechazado que se abre desde Inicio para corregirlo (M9).
 */
@Suppress("TooManyFunctions", "LongParameterList")
class ReporteViewModel(
    private val api: BffApi,
    private val borrador: SavedStateHandle = SavedStateHandle(),
    private val hoy: () -> LocalDate = LocalDate::now,
    private val nuevaClave: () -> String = { UUID.randomUUID().toString() },
    fuentes: FuentesEvidencia = FuentesEvidencia(),
    private val enviador: EnviadorBorradores,
    idCorregir: String? = null,
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

    /**
     * En la confirmación, la evidencia del borrador que sube el enviador en segundo plano. Se recuerda lo
     * último visto: al terminar, el borrador se borra y solo queda su registro.
     */
    var evidenciaBorrador by mutableStateOf(EvidenciaBorrador())
        private set

    init {
        viewModelScope.launch {
            enviador.conectado.collect { conectado -> estado = estado.copy(sinConexion = !conectado) }
        }
        viewModelScope.launch { enviador.tarjetas.collect(::seguirBorrador) }
        val reportado = estado.siniestro
        val id = estado.idBorrador
        when {
            reportado != null -> evidencia.consultar(reportado.id)
            estado.guardado != null -> Unit
            id != null && estado.corrigiendo -> recargarBorrador(id)
            // El proceso murió entre el guardado y la respuesta: el borrador ya es del enviador.
            id != null -> estado = estado.copy(guardado = ReporteGuardado.SIN_CONFIRMAR, cargandoPolizas = false)
            idCorregir != null -> abrirParaCorregir(idCorregir)
            else -> cargarPolizas()
        }
    }

    fun cargarPolizas() {
        estado = estado.copy(cargandoPolizas = true, errorCarga = null, necesitaConexion = false)
        val cliente = enviador.clienteActual()
        viewModelScope.launch {
            val resultado = llamarBff { api.listarPolizas() }
            estado = when (resultado) {
                is Resultado.Exito -> {
                    enviador.guardarPolizas(resultado.valor, cliente)
                    conPolizas(resultado.valor, del = null)
                }
                is Resultado.Falla -> estado.copy(
                    cargandoPolizas = false,
                    errorCarga = R.string.error_generico,
                    sesionVencida = resultado.http == HTTP_NO_AUTENTICADO,
                )
                // Sin red se usa la última lista guardada; la vigencia la valida el BFF al enviar (M1, M2).
                Resultado.SinConexion -> enviador.leerPolizas()?.let { conPolizas(it.polizas, it.actualizadasEn) }
                    ?: estado.copy(cargandoPolizas = false, necesitaConexion = true)
            }
        }
    }

    private fun conPolizas(polizas: List<PolizaResumen>, del: String?): EstadoReporte {
        // Una póliza restaurada que ya no está en la lista deja de estar elegida.
        val numero = estado.datos.numeroPoliza?.takeIf { n -> polizas.any { it.numero == n } }
        return estado.copy(
            cargandoPolizas = false,
            polizas = polizas,
            polizasDel = del,
            necesitaConexion = false,
            datos = estado.datos.copy(numeroPoliza = numero),
        )
    }

    /** Reintenta lo que falló: lo del borrador con el enviador, lo agregado después con el gestor. */
    fun reintentarEvidencia() {
        val id = estado.idBorrador
        if (id != null && evidenciaBorrador.adjuntos.any { it.reintentable }) enviador.reintentar(id)
        val siniestro = estado.siniestro
        if (siniestro != null && evidencia.estado.puedeReintentar) evidencia.reintentar(siniestro.id)
    }

    private fun seguirBorrador(tarjetas: List<Tarjeta>, forzarConsulta: Boolean = false) {
        val id = estado.idBorrador ?: return
        val anterior = evidenciaBorrador
        evidenciaBorrador = when (val tarjeta = tarjetas.firstOrNull { it.id == id }) {
            is Tarjeta.DeBorrador -> EvidenciaBorrador(
                adjuntos = tarjeta.borrador.adjuntos.map(AdjuntoBorrador::comoAdjunto),
                enviando = tarjeta.enCurso && tarjeta.borrador.adjuntosPendientes.isNotEmpty(),
            )
            is Tarjeta.Enviada -> anterior.copy(
                adjuntos = anterior.adjuntos.map { it.copy(estado = EstadoAdjunto.ENVIADO, fallo = null) },
                enviando = false,
            )
            else -> anterior.copy(enviando = false)
        }
        // Cambió algo y ya no se está enviando: la lista del siniestro refleja lo que llegó. Se compara con
        // lo último visto, no con una transición, porque el StateFlow puede saltarse estados intermedios.
        val siniestro = estado.siniestro ?: return
        val cambio = forzarConsulta || evidenciaBorrador != anterior
        if (!evidenciaBorrador.enviando && cambio) evidencia.consultar(siniestro.id)
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

    /** @return false si la pantalla debe salir del asistente: desde el primer paso. */
    fun atras(): Boolean {
        val anterior = PasoReporte.entries.getOrNull(estado.paso.ordinal - 1) ?: return false
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

    /**
     * Siempre el mismo camino, con o sin red: primero el borrador cifrado y después el envío. Si no se
     * puede guardar, no se envía nada y el cliente sigue en la revisión con sus datos (M15).
     */
    private fun enviar() {
        if (evidencia.quitarPerdidos() > 0) {
            estado = estado.copy(paso = PasoReporte.EVIDENCIA, aviso = Aviso.ArchivoPerdido)
            return
        }
        val clave = estado.claveIdempotencia ?: nuevaClave()
        val solicitud = estado.datos.aSolicitud()
        val idCorregir = estado.idBorrador.takeIf { estado.corrigiendo }
        val nuevos = evidencia.paraBorrador()
        val archivos = nuevos.size + estado.adjuntosBorrador.size
        estado = estado.copy(enviando = true, aviso = null, claveIdempotencia = clave)
        viewModelScope.launch {
            val resultado = enviador.enviarDesdeAsistente(
                solicitud,
                clave,
                nuevos,
                idCorregir,
                alGuardar = { id ->
                    // Los archivos ya son del almacén cifrado. La revisión sigue a la vista mientras se
                    // envía, pero en el estado guardado queda solo el id (ver `guardar`).
                    evidenciaBorrador = EvidenciaBorrador(nuevos.map { it.adjunto.comoAdjunto() })
                    evidencia.transferidos()
                    estado = estado.copy(
                        idBorrador = id,
                        corrigiendo = false,
                        adjuntosBorrador = emptyList(),
                        archivosGuardados = archivos,
                    )
                },
            )
            estado = alResultado(resultado).copy(enviando = false)
            // El enviador pudo avanzar con la evidencia antes de que llegara la confirmación.
            if (resultado is ResultadoEnvio.Reportado) seguirBorrador(enviador.tarjetas.value, forzarConsulta = true)
            val rechazo = (resultado as? ResultadoEnvio.Rechazado)?.borrador?.rechazo
            if (rechazo?.codigo == POLIZA_NO_ENCONTRADA) cargarPolizas()
        }
    }

    private fun alResultado(resultado: ResultadoEnvio): EstadoReporte = when (resultado) {
        is ResultadoEnvio.Reportado -> estado.copy(siniestro = resultado.siniestro)
        ResultadoEnvio.SinRed -> estado.copy(guardado = ReporteGuardado.SIN_CONEXION)
        ResultadoEnvio.Incierto, ResultadoEnvio.PorConciliar -> estado.copy(guardado = ReporteGuardado.SIN_CONFIRMAR)
        // El borrador espera al ingreso; la pantalla delega al flujo de sesión.
        ResultadoEnvio.SinSesion -> estado.copy(guardado = ReporteGuardado.SIN_CONFIRMAR, sesionVencida = true)
        ResultadoEnvio.NoGuardado -> estado.copy(aviso = Aviso.NoPudimosGuardar)
        // Rechazo definitivo y sin intento incierto: el mismo aviso de #169, y se corrige con clave nueva.
        is ResultadoEnvio.Rechazado -> paraCorregir(resultado.borrador)
    }

    /** M9 «Revisar»: el asistente se abre en el paso afectado con los datos del borrador. */
    private fun abrirParaCorregir(id: String) {
        estado = estado.copy(cargandoPolizas = true)
        viewModelScope.launch {
            val leido = enviador.leer(id)?.takeIf { it.estado == EstadoBorrador.REQUIERE_ATENCION }
            if (leido == null) {
                estado = estado.copy(cargandoPolizas = false, errorCarga = R.string.borrador_no_disponible)
                return@launch
            }
            estado = paraCorregir(leido)
            cargarPolizas()
        }
    }

    /**
     * Tras la muerte del proceso en una corrección: los datos no quedaron en claro en el estado guardado,
     * así que se vuelven a leer del borrador cifrado (se pierde lo que se estaba corrigiendo).
     */
    private fun recargarBorrador(id: String) {
        viewModelScope.launch {
            val leido = enviador.leer(id)
            estado = if (leido == null) {
                estado.copy(corrigiendo = false, cargandoPolizas = false, errorCarga = R.string.borrador_no_disponible)
            } else {
                estado.copy(datos = leido.solicitud.aDatos(), adjuntosBorrador = leido.adjuntos)
            }
            if (leido != null) cargarPolizas()
        }
    }

    private fun paraCorregir(rechazado: Borrador): EstadoReporte {
        val base = estado.copy(
            idBorrador = rechazado.id,
            corrigiendo = true,
            datos = rechazado.solicitud.aDatos(),
            adjuntosBorrador = rechazado.adjuntos,
            claveIdempotencia = nuevaClave(),
            paso = PasoReporte.REVISION,
            visibles = Campo.entries.toSet(),
            guardado = null,
        )
        return base.conRechazo(rechazado.rechazo)
    }
}

/** La evidencia del borrador en la confirmación, con la forma que dibuja `SeccionEvidencia`. */
data class EvidenciaBorrador(val adjuntos: List<Adjunto> = emptyList(), val enviando: Boolean = false)

private fun AdjuntoBorrador.comoAdjunto() = Adjunto(
    id = id,
    ruta = "",
    nombre = nombre,
    formato = formato,
    tamano = tamano,
    tomadaEn = tomadaEn,
    latitud = latitud,
    longitud = longitud,
    lugar = lugar,
    estado = estado,
    fallo = fallo,
)

private const val HTTP_NO_AUTENTICADO = 401
private const val POLIZA_NO_ENCONTRADA = "poliza_no_encontrada"

private fun fecha(texto: String?): LocalDate? = texto?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** El aviso de #169 para el rechazo guardado en el borrador, en el paso afectado. */
private fun EstadoReporte.conRechazo(rechazo: RechazoBorrador?): EstadoReporte = when {
    rechazo == null -> copy(aviso = Aviso.Generico)
    rechazo.codigo == "evento_fuera_de_vigencia" -> copy(
        aviso = Aviso.FueraDeVigencia(fecha(rechazo.vigenteDesde), fecha(rechazo.vigenteHasta)),
    )
    rechazo.codigo == POLIZA_NO_ENCONTRADA -> copy(
        paso = PasoReporte.POLIZA,
        datos = datos.copy(numeroPoliza = null),
        aviso = Aviso.PolizaNoEncontrada,
    )
    rechazo.codigo == "clave_idempotencia_en_uso" -> copy(aviso = Aviso.ConfirmarDeNuevo)
    else -> conCamposRechazados(rechazo) ?: copy(aviso = Aviso.Generico)
}

/** Un 422 por campo (incluido `fecha_futura`) vuelve al paso del primer campo afectado. */
private fun EstadoReporte.conCamposRechazados(rechazo: RechazoBorrador): EstadoReporte? {
    val porCampo = rechazo.campos.mapNotNull { error ->
        Campo.delContrato(error.campo)?.let { it to ValidadorReporte.rechazoDe(it, error.codigo) }
    }
    val fechaFutura = rechazo.codigo == "fecha_futura"
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

private fun SolicitudSiniestro.aDatos() = DatosReporte(
    numeroPoliza = numeroPoliza,
    tipoEvento = tipoEvento,
    fecha = fecha(ocurridoEl),
    descripcion = descripcion,
    monto = montoEstimado?.valor?.toString().orEmpty(),
)

// ── Estado guardado (SavedStateHandle) ────────────────────────────────────────────────────────
// Solo tipos que admite un Bundle; las pólizas se vuelven a consultar. Con un borrador guardado (y no
// en corrección) solo queda su identificador: los datos están cifrados en el almacén.

private const val CLAVE_PASO = "paso"
private const val CLAVE_POLIZA = "poliza"
private const val CLAVE_TIPO = "tipo"
private const val CLAVE_FECHA = "fecha"
private const val CLAVE_DESCRIPCION = "descripcion"
private const val CLAVE_MONTO = "monto"
private const val CLAVE_VISIBLES = "visibles"
private const val CLAVE_IDEMPOTENCIA = "idempotencia"
private const val CLAVE_SINIESTRO = "siniestro"
private const val CLAVE_BORRADOR = "borrador"
private const val CLAVE_CORRIGIENDO = "corrigiendo"
private const val CLAVE_GUARDADO = "guardado"
private const val CLAVE_ARCHIVOS = "archivos_guardados"

private fun restaurar(borrador: SavedStateHandle, hoy: LocalDate): EstadoReporte {
    val siniestro = borrador.get<String>(CLAVE_SINIESTRO)?.let { json ->
        runCatching { NetworkClient.json.decodeFromString(Siniestro.serializer(), json) }.getOrNull()
    }
    val guardado = borrador.get<String>(CLAVE_GUARDADO)?.let { nombre ->
        ReporteGuardado.entries.firstOrNull { it.name == nombre }
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
        idBorrador = borrador[CLAVE_BORRADOR],
        corrigiendo = borrador[CLAVE_CORRIGIENDO] ?: false,
        guardado = guardado,
        archivosGuardados = borrador[CLAVE_ARCHIVOS] ?: 0,
        siniestro = siniestro,
        cargandoPolizas = siniestro == null && guardado == null,
    )
}

private fun guardar(borrador: SavedStateHandle, estado: EstadoReporte) {
    // Con un borrador (guardado o en corrección) ni la póliza ni la descripción quedan en claro (criterio 6).
    val datos = if (estado.idBorrador != null) DatosReporte() else estado.datos
    borrador[CLAVE_PASO] = estado.paso.name
    borrador[CLAVE_POLIZA] = datos.numeroPoliza
    borrador[CLAVE_TIPO] = datos.tipoEvento?.name
    borrador[CLAVE_FECHA] = datos.fecha?.toString()
    borrador[CLAVE_DESCRIPCION] = datos.descripcion
    borrador[CLAVE_MONTO] = datos.monto
    borrador[CLAVE_VISIBLES] = ArrayList(estado.visibles.map { it.name })
    borrador[CLAVE_IDEMPOTENCIA] = estado.claveIdempotencia.takeIf { estado.idBorrador == null || estado.corrigiendo }
    borrador[CLAVE_BORRADOR] = estado.idBorrador
    borrador[CLAVE_CORRIGIENDO] = estado.corrigiendo
    borrador[CLAVE_GUARDADO] = estado.guardado?.name
    borrador[CLAVE_ARCHIVOS] = estado.archivosGuardados
    // La confirmación solo muestra referencia, estado y siguiente paso: lo demás no se guarda en claro.
    borrador[CLAVE_SINIESTRO] = estado.siniestro
        ?.copy(numeroPoliza = "", descripcion = "", montoEstimado = null)
        ?.let { NetworkClient.json.encodeToString(Siniestro.serializer(), it) }
}
