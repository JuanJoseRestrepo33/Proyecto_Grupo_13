package co.solventa.feature.consentimiento

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.solventa.core.network.BffApi
import co.solventa.core.network.CodigoFinalidad
import co.solventa.core.network.Consentimiento
import co.solventa.core.network.EstadoConsentimiento
import co.solventa.core.network.FinalidadConsentimiento
import co.solventa.core.network.Resultado
import co.solventa.core.network.SolicitudConsentimiento
import co.solventa.core.network.llamarBff
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ── Estado de la pantalla ─────────────────────────────────────────────────────────────────────

/**
 * Vista de una finalidad combinada con su consentimiento más relevante (vigente primero;
 * si no existe, el más reciente por [Consentimiento.otorgadoEn]).
 */
data class FinalidadUi(
    val finalidad: FinalidadConsentimiento,
    /** null = sin consentimiento para esta finalidad. */
    val consentimiento: Consentimiento?,
)

/** Estado global de la pantalla de consentimientos. */
data class EstadoConsentimientoUi(
    val cargando: Boolean = false,
    val finalidades: List<FinalidadUi> = emptyList(),
    /** true cuando la carga falló y todavía no se ha vuelto a cargar con éxito. */
    val errorCarga: Boolean = false,
    /** Error de la última consulta; separado de [mensajeError] para que recargar no lo borre. */
    @StringRes val mensajeCarga: Int? = null,
    /** Error de la última mutación; se conserva mientras la lista se recupera. */
    @StringRes val mensajeError: Int? = null,
    /** Confirmación tras una respuesta satisfactoria del servidor; nunca optimista. */
    @StringRes val mensajeExito: Int? = null,
    /** `vence_en` devuelta por el servidor al renovar. */
    val nuevaVenceEn: String? = null,
    // ── Otorgar ───────────────────────────────────────────────────────────────────────────────
    /** Finalidad para la que se está otorgando. null = diálogo cerrado. */
    val otorgando: FinalidadConsentimiento? = null,
    /** IDs de entidades seleccionadas por el usuario. Ninguna marcada al abrir. */
    val entidadesSeleccionadas: Set<String> = emptySet(),
    /** El usuario aceptó explícitamente la versión legal vigente. */
    val versionAceptada: Boolean = false,
    // ── Revocar ───────────────────────────────────────────────────────────────────────────────
    /** ID del consentimiento cuya revocación se está confirmando. null = diálogo cerrado. */
    val revocando: String? = null,
    /** Finalidad del consentimiento que se revoca; para mostrar advertencia de cotización. */
    val finalidadRevocando: CodigoFinalidad? = null,
    // ── Renovar ───────────────────────────────────────────────────────────────────────────────
    /** ID del consentimiento que se está renovando (en tránsito). null = sin renovación activa. */
    val renovando: String? = null,
    // ── Control de mutaciones ─────────────────────────────────────────────────────────────────
    /** Mutación en curso: deshabilita todas las acciones para evitar doble envío. */
    val mutando: Boolean = false,
    /**
     * true cuando una mutación terminó sin saber si el servidor la aplicó: se exige una
     * consulta iniciada después de ese resultado antes de permitir otra mutación.
     */
    val requiereRefresco: Boolean = false,
    /** El BFF respondió 401: la pantalla delega al flujo de sesión, sin reenviar la mutación. */
    val sesionVencida: Boolean = false,
) {
    /** Sin acciones sobre datos que se están cargando, mutando o cuyo estado es incierto. */
    val accionesHabilitadas: Boolean
        get() = !mutando && !cargando && !requiereRefresco

    /** El botón de otorgar solo se habilita con al menos una entidad y la versión aceptada. */
    val puedeOtorgar: Boolean
        get() = entidadesSeleccionadas.isNotEmpty() && versionAceptada && accionesHabilitadas
}

// ── ViewModel ────────────────────────────────────────────────────────────────────────────────

@Suppress("TooManyFunctions")
class ConsentimientoViewModel(private val api: BffApi) : ViewModel() {

    var estado by mutableStateOf(EstadoConsentimientoUi())
        private set

    private var carga: Job? = null

    /**
     * Cambia al iniciar y al terminar cada mutación. Una consulta solo se aplica si la
     * generación no cambió mientras estaba en vuelo; así una respuesta anterior a la mutación
     * no puede presentarse como estado actual ni levantar [EstadoConsentimientoUi.requiereRefresco].
     */
    private var generacion = 0

    // ── Carga ─────────────────────────────────────────────────────────────────────────────────

    /** Carga catálogo y consentimientos; usada al entrar, en ON_RESUME y pull-to-refresh. */
    fun cargar() {
        carga?.cancel()
        val inicio = generacion
        estado = estado.copy(cargando = true, mensajeCarga = null)
        carga = viewModelScope.launch {
            val rFinalidades = llamarBff { api.listarFinalidades() }
            // Sin catálogo no hay lista que mostrar: un 401 o un fallo se atienden de inmediato.
            val rConsentimientos = if (rFinalidades is Resultado.Exito) {
                llamarBff { api.listarConsentimientos() }
            } else {
                null
            }
            estado = when {
                inicio != generacion -> estado.copy(cargando = false)
                rFinalidades is Resultado.Exito && rConsentimientos is Resultado.Exito -> {
                    val finalidades = combinar(rFinalidades.valor, rConsentimientos.valor)
                    estado.copy(
                        cargando = false,
                        errorCarga = false,
                        finalidades = finalidades,
                        requiereRefresco = false,
                    ).reconciliarDialogos(finalidades)
                }
                else -> {
                    val fallo = rConsentimientos ?: rFinalidades
                    // Si el estado conocido ya es obsoleto, el formulario no puede reenviarse con él:
                    // se cierra y la lista ofrece actualizar.
                    val base = if (estado.requiereRefresco) estado.sinDialogos() else estado
                    base.copy(
                        cargando = false,
                        errorCarga = estado.finalidades.isEmpty(),
                        sesionVencida = fallo is Resultado.Falla && fallo.http == HTTP_NO_AUTENTICADO,
                        mensajeCarga = if (fallo is Resultado.SinConexion) {
                            R.string.error_sin_conexion
                        } else {
                            R.string.error_generico
                        },
                    )
                }
            }
        }
    }

    // ── Otorgar ───────────────────────────────────────────────────────────────────────────────

    fun abrirOtorgar(finalidad: FinalidadConsentimiento) {
        if (!estado.accionesHabilitadas) return
        estado = estado.copy(
            otorgando = finalidad,
            entidadesSeleccionadas = emptySet(),
            versionAceptada = false,
            mensajeError = null,
            mensajeExito = null,
        )
    }

    fun cerrarOtorgar() {
        if (estado.mutando) return
        estado = estado.copy(otorgando = null, mensajeError = null)
    }

    fun toggleEntidad(id: String) {
        val nuevas = if (id in estado.entidadesSeleccionadas) {
            estado.entidadesSeleccionadas - id
        } else {
            estado.entidadesSeleccionadas + id
        }
        estado = estado.copy(entidadesSeleccionadas = nuevas)
    }

    fun toggleVersionAceptada() {
        estado = estado.copy(versionAceptada = !estado.versionAceptada)
    }

    fun otorgar() {
        val finalidad = estado.otorgando ?: return
        if (!estado.puedeOtorgar) return
        val solicitud = SolicitudConsentimiento(
            finalidad = finalidad.codigo,
            entidadesOrigen = estado.entidadesSeleccionadas.toList(),
            versionTexto = finalidad.versionTexto,
        )
        mutar(
            llamada = { api.otorgarConsentimiento(solicitud) },
            alExito = { otorgado ->
                estado.conConsentimiento(otorgado)
                    .copy(otorgando = null, mensajeExito = R.string.otorgar_confirmado)
            },
            alFallar = { falla ->
                when (falla.codigo) {
                    // El formulario sigue abierto: la recarga trae la versión y las entidades vigentes.
                    VERSION_DESACTUALIZADA -> estado.copy(versionAceptada = false)
                    ENTIDADES_INVALIDAS -> estado
                    else -> estado.copy(otorgando = null)
                }
            },
        )
    }

    // ── Revocar ───────────────────────────────────────────────────────────────────────────────

    fun abrirRevocar(idConsentimiento: String, finalidad: CodigoFinalidad) {
        if (!estado.accionesHabilitadas) return
        estado = estado.copy(
            revocando = idConsentimiento,
            finalidadRevocando = finalidad,
            mensajeError = null,
            mensajeExito = null,
        )
    }

    fun cerrarRevocar() {
        if (estado.mutando) return
        estado = estado.copy(revocando = null, finalidadRevocando = null)
    }

    fun revocar() {
        val id = estado.revocando ?: return
        if (!estado.accionesHabilitadas) return
        mutar(
            llamada = { api.revocarConsentimiento(id) },
            alExito = { revocado ->
                estado.conConsentimiento(revocado).sinRevocacion().copy(mensajeExito = R.string.revocar_confirmado)
            },
            alFallar = { estado.sinRevocacion() },
        )
    }

    // ── Renovar ───────────────────────────────────────────────────────────────────────────────

    fun renovar(idConsentimiento: String) {
        if (!estado.accionesHabilitadas) return
        estado = estado.copy(renovando = idConsentimiento, mensajeError = null, mensajeExito = null)
        mutar(
            llamada = { api.renovarConsentimiento(idConsentimiento) },
            alExito = { renovado ->
                estado.conConsentimiento(renovado).copy(
                    renovando = null,
                    mensajeExito = R.string.renovar_confirmado,
                    nuevaVenceEn = renovado.venceEn,
                )
            },
            alFallar = { estado.copy(renovando = null) },
        )
    }

    /** El flujo de sesión ya tomó el 401; la pantalla no vuelve a delegarlo. */
    fun sesionAtendida() {
        estado = estado.copy(sesionVencida = false)
    }

    // ── Auxiliares ────────────────────────────────────────────────────────────────────────────

    /**
     * Ejecuta una mutación sin cambios optimistas. Tras la respuesta del servidor, o un error
     * con código, refresca la lista. Si el resultado es incierto (sin conexión, respuesta
     * perdida o fallo del servidor) no repite ni encola: exige consultar antes de permitir otra.
     */
    private fun mutar(
        llamada: suspend () -> Consentimiento,
        alExito: (Consentimiento) -> EstadoConsentimientoUi,
        alFallar: (Resultado.Falla) -> EstadoConsentimientoUi,
    ) {
        generacion++
        estado = estado.copy(mutando = true, mensajeError = null, mensajeExito = null, nuevaVenceEn = null)
        viewModelScope.launch {
            val resultado = llamarBff(llamada)
            generacion++
            estado = when (resultado) {
                is Resultado.Exito -> alExito(resultado.valor).copy(mutando = false)
                is Resultado.Falla -> alFallar(resultado).copy(
                    mutando = false,
                    mensajeError = mensajeDe(resultado),
                    // Incierta o ya conocida como obsoleta: no se actúa hasta una consulta exitosa.
                    requiereRefresco = resultado.esIncierta() || resultado.debeRefrescar(),
                    sesionVencida = resultado.http == HTTP_NO_AUTENTICADO,
                )
                Resultado.SinConexion -> estado.sinDialogos().copy(
                    mutando = false,
                    renovando = null,
                    mensajeError = R.string.error_sin_conexion,
                    requiereRefresco = true,
                )
            }
            if (resultado.debeRefrescar()) cargar()
        }
    }

    private fun EstadoConsentimientoUi.sinRevocacion() = copy(revocando = null, finalidadRevocando = null)

    private fun EstadoConsentimientoUi.sinDialogos() = sinRevocacion().copy(otorgando = null)

    /** Aplica de inmediato la respuesta del servidor; la recarga posterior la confirma. */
    private fun EstadoConsentimientoUi.conConsentimiento(c: Consentimiento) = copy(
        finalidades = finalidades.map { if (it.finalidad.codigo == c.finalidad) it.copy(consentimiento = c) else it },
    )

    /**
     * Ajusta los diálogos abiertos al estado recién consultado: el formulario de otorgar toma
     * la versión y entidades vigentes del catálogo (si la versión cambió, exige aceptar de nuevo)
     * y se cierra cualquier diálogo cuya acción ya no aplica.
     */
    private fun EstadoConsentimientoUi.reconciliarDialogos(lista: List<FinalidadUi>): EstadoConsentimientoUi {
        var nuevo = this
        otorgando?.let { abierta ->
            val actual = lista.firstOrNull { it.finalidad.codigo == abierta.codigo }
            nuevo = if (actual == null || actual.consentimiento?.estado == EstadoConsentimiento.VIGENTE) {
                nuevo.copy(otorgando = null)
            } else {
                val ids = actual.finalidad.entidadesOrigen.map { it.id }.toSet()
                nuevo.copy(
                    otorgando = actual.finalidad,
                    entidadesSeleccionadas = entidadesSeleccionadas intersect ids,
                    versionAceptada = versionAceptada && actual.finalidad.versionTexto == abierta.versionTexto,
                )
            }
        }
        revocando?.let { id ->
            val sigueVigente = lista.any {
                it.consentimiento?.id == id && it.consentimiento.estado == EstadoConsentimiento.VIGENTE
            }
            if (!sigueVigente) nuevo = nuevo.sinRevocacion()
        }
        return nuevo
    }

    @StringRes
    private fun mensajeDe(falla: Resultado.Falla): Int = when {
        falla.codigo == "consentimiento_ya_vigente" -> R.string.consentimiento_ya_vigente
        falla.codigo == "consentimiento_no_vigente" -> R.string.consentimiento_no_vigente
        falla.codigo == "consentimiento_revocado" -> R.string.consentimiento_revocado
        falla.codigo == VERSION_DESACTUALIZADA -> R.string.version_texto_desactualizada
        falla.codigo == ENTIDADES_INVALIDAS -> R.string.entidades_invalidas
        falla.http == HTTP_NO_AUTENTICADO -> R.string.error_sesion
        falla.http == HTTP_NO_ENCONTRADO -> R.string.consentimiento_no_encontrado
        else -> R.string.error_generico
    }

    /** Sin código estable o con fallo del servidor no se sabe si la mutación se aplicó. */
    private fun Resultado.Falla.esIncierta(): Boolean =
        http != HTTP_NO_AUTENTICADO && (codigo == null || http >= HTTP_ERROR_SERVIDOR)

    private fun Resultado<*>.debeRefrescar(): Boolean = when (this) {
        is Resultado.Exito -> true
        is Resultado.Falla -> http != HTTP_NO_AUTENTICADO && !esIncierta()
        Resultado.SinConexion -> false
    }


    private companion object {
        const val VERSION_DESACTUALIZADA = "version_texto_desactualizada"
        const val ENTIDADES_INVALIDAS = "entidades_invalidas"
        const val HTTP_NO_AUTENTICADO = 401
        const val HTTP_NO_ENCONTRADO = 404
        const val HTTP_ERROR_SERVIDOR = 500
    }
}

// ── Funciones de combinación ─────────────────────────────────────────────────────────────────

/**
 * Asocia cada finalidad con su consentimiento más relevante: vigente primero; si no existe,
 * el más reciente por [Consentimiento.otorgadoEn].
 */
internal fun combinar(
    finalidades: List<FinalidadConsentimiento>,
    consentimientos: List<Consentimiento>,
): List<FinalidadUi> = finalidades.map { f ->
    val porFinalidad = consentimientos.filter { it.finalidad == f.codigo }
    val vigente = porFinalidad.firstOrNull { it.estado == EstadoConsentimiento.VIGENTE }
    val relevante = vigente ?: porFinalidad.maxByOrNull { it.otorgadoEn }
    FinalidadUi(f, relevante)
}
