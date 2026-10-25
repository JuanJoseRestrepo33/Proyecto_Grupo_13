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
    @StringRes val mensajeError: Int? = null,
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
     * true cuando una mutación terminó con respuesta perdida o error de red: se exige
     * refrescar la lista antes de permitir repetir la misma mutación.
     */
    val requiereRefresco: Boolean = false,
) {
    /** El botón de otorgar solo se habilita con al menos una entidad y la versión aceptada. */
    val puedeOtorgar: Boolean
        get() = entidadesSeleccionadas.isNotEmpty() && versionAceptada && !mutando
}

// ── ViewModel ────────────────────────────────────────────────────────────────────────────────

class ConsentimientoViewModel(private val api: BffApi) : ViewModel() {

    var estado by mutableStateOf(EstadoConsentimientoUi())
        private set

    // ── Carga ─────────────────────────────────────────────────────────────────────────────────

    /** Carga catálogo y consentimientos; usada en init, ON_RESUME y pull-to-refresh. */
    fun cargar() {
        if (estado.cargando) return
        estado = estado.copy(cargando = true, mensajeError = null)
        viewModelScope.launch {
            val rFinalidades = llamarBff { api.listarFinalidades() }
            val rConsentimientos = llamarBff { api.listarConsentimientos() }

            when {
                rFinalidades is Resultado.Exito && rConsentimientos is Resultado.Exito -> {
                    estado = estado.copy(
                        cargando = false,
                        errorCarga = false,
                        finalidades = combinar(rFinalidades.valor, rConsentimientos.valor),
                        requiereRefresco = false,
                    )
                }
                rFinalidades is Resultado.SinConexion || rConsentimientos is Resultado.SinConexion -> {
                    estado = estado.copy(
                        cargando = false,
                        errorCarga = estado.finalidades.isEmpty(),
                        mensajeError = R.string.error_sin_conexion,
                    )
                }
                else -> {
                    estado = estado.copy(
                        cargando = false,
                        errorCarga = estado.finalidades.isEmpty(),
                        mensajeError = R.string.error_generico,
                    )
                }
            }
        }
    }

    // ── Otorgar ───────────────────────────────────────────────────────────────────────────────

    fun abrirOtorgar(finalidad: FinalidadConsentimiento) {
        estado = estado.copy(
            otorgando = finalidad,
            entidadesSeleccionadas = emptySet(),
            versionAceptada = false,
            mensajeError = null,
        )
    }

    fun cerrarOtorgar() {
        estado = estado.copy(otorgando = null)
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
        if (estado.requiereRefresco) {
            estado = estado.copy(mensajeError = R.string.consentimiento_requiere_refresco)
            return
        }
        estado = estado.copy(mutando = true, mensajeError = null)
        viewModelScope.launch {
            val resultado = llamarBff {
                api.otorgarConsentimiento(
                    SolicitudConsentimiento(
                        finalidad = finalidad.codigo,
                        entidadesOrigen = estado.entidadesSeleccionadas.toList(),
                        versionTexto = finalidad.versionTexto,
                    ),
                )
            }
            when (resultado) {
                is Resultado.Exito -> {
                    estado = estado.copy(mutando = false, otorgando = null)
                    cargar()
                }
                is Resultado.Falla -> {
                    estado = estado.copy(
                        mutando = false,
                        mensajeError = errorOtorgar(resultado),
                        requiereRefresco = resultado.requiereRefresco(),
                    )
                    if (resultado.debeRecargarCatalogo()) cargar()
                }
                Resultado.SinConexion -> {
                    estado = estado.copy(
                        mutando = false,
                        mensajeError = R.string.error_sin_conexion,
                        requiereRefresco = true,
                    )
                }
            }
        }
    }

    // ── Revocar ───────────────────────────────────────────────────────────────────────────────

    fun abrirRevocar(idConsentimiento: String, finalidad: CodigoFinalidad) {
        estado = estado.copy(
            revocando = idConsentimiento,
            finalidadRevocando = finalidad,
            mensajeError = null,
        )
    }

    fun cerrarRevocar() {
        estado = estado.copy(revocando = null, finalidadRevocando = null)
    }

    fun revocar() {
        val id = estado.revocando ?: return
        if (estado.mutando) return
        if (estado.requiereRefresco) {
            estado = estado.copy(mensajeError = R.string.consentimiento_requiere_refresco)
            return
        }
        estado = estado.copy(mutando = true, mensajeError = null)
        viewModelScope.launch {
            when (val resultado = llamarBff { api.revocarConsentimiento(id) }) {
                is Resultado.Exito -> {
                    estado = estado.copy(mutando = false, revocando = null, finalidadRevocando = null)
                    cargar()
                }
                is Resultado.Falla -> {
                    estado = estado.copy(
                        mutando = false,
                        mensajeError = errorRevocar(resultado),
                        requiereRefresco = resultado.requiereRefresco(),
                    )
                    if (resultado.debeRecargar()) cargar()
                }
                Resultado.SinConexion -> {
                    estado = estado.copy(
                        mutando = false,
                        mensajeError = R.string.error_sin_conexion,
                        requiereRefresco = true,
                    )
                }
            }
        }
    }

    // ── Renovar ───────────────────────────────────────────────────────────────────────────────

    fun renovar(idConsentimiento: String) {
        if (estado.mutando) return
        if (estado.requiereRefresco) {
            estado = estado.copy(mensajeError = R.string.consentimiento_requiere_refresco)
            return
        }
        estado = estado.copy(mutando = true, renovando = idConsentimiento, mensajeError = null)
        viewModelScope.launch {
            when (val resultado = llamarBff { api.renovarConsentimiento(idConsentimiento) }) {
                is Resultado.Exito -> {
                    estado = estado.copy(mutando = false, renovando = null)
                    cargar()
                }
                is Resultado.Falla -> {
                    estado = estado.copy(
                        mutando = false,
                        renovando = null,
                        mensajeError = errorRenovar(resultado),
                        requiereRefresco = resultado.requiereRefresco(),
                    )
                    if (resultado.debeRecargar()) cargar()
                }
                Resultado.SinConexion -> {
                    estado = estado.copy(
                        mutando = false,
                        renovando = null,
                        mensajeError = R.string.error_sin_conexion,
                        requiereRefresco = true,
                    )
                }
            }
        }
    }

    // ── Auxiliares ────────────────────────────────────────────────────────────────────────────

    @StringRes
    private fun errorOtorgar(falla: Resultado.Falla): Int = when (falla.codigo) {
        "consentimiento_ya_vigente" -> R.string.consentimiento_ya_vigente
        "version_texto_desactualizada" -> R.string.version_texto_desactualizada
        "entidades_invalidas" -> R.string.entidades_invalidas
        else -> if (falla.http == HTTP_NO_AUTENTICADO) R.string.error_sesion else R.string.error_generico
    }

    @StringRes
    private fun errorRevocar(falla: Resultado.Falla): Int = when (falla.codigo) {
        "consentimiento_no_vigente", "consentimiento_revocado" -> R.string.consentimiento_no_vigente
        else -> if (falla.http == HTTP_NO_AUTENTICADO) R.string.error_sesion
        else if (falla.http == HTTP_NO_ENCONTRADO) R.string.consentimiento_no_encontrado
        else R.string.error_generico
    }

    @StringRes
    private fun errorRenovar(falla: Resultado.Falla): Int = when (falla.codigo) {
        "consentimiento_ya_vigente" -> R.string.consentimiento_ya_vigente
        "consentimiento_revocado" -> R.string.consentimiento_revocado
        else -> if (falla.http == HTTP_NO_AUTENTICADO) R.string.error_sesion
        else if (falla.http == HTTP_NO_ENCONTRADO) R.string.consentimiento_no_encontrado
        else R.string.error_generico
    }

    private fun Resultado.Falla.requiereRefresco(): Boolean =
        http == HTTP_SIN_CONEXION_PROXY || codigo == null

    private fun Resultado.Falla.debeRecargar(): Boolean =
        codigo in setOf("consentimiento_no_vigente", "consentimiento_revocado", "consentimiento_ya_vigente")

    private fun Resultado.Falla.debeRecargarCatalogo(): Boolean =
        codigo in setOf("version_texto_desactualizada", "entidades_invalidas")

    private companion object {
        const val HTTP_NO_AUTENTICADO = 401
        const val HTTP_NO_ENCONTRADO = 404
        const val HTTP_SIN_CONEXION_PROXY = 0
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
