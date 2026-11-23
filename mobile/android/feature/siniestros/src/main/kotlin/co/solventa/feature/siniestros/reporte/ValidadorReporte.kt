package co.solventa.feature.siniestros.reporte

import androidx.annotation.StringRes
import co.solventa.core.network.TipoEventoSiniestro
import co.solventa.feature.siniestros.R
import java.time.LocalDate

/** Límites de `SolicitudSiniestro` en contratos/bff-movil; la descripción se cuenta sin espacios al borde. */
val DESCRIPCION = 10..2000

/** Hasta 999.999.999.999 pesos: cabe en un Long y en el entero del núcleo. */
const val DIGITOS_MONTO = 12

/** Pesos enteros, solo dígitos o con punto de miles (1500000 o 1.500.000); sin decimales. */
private val MONTO = Regex("^(\\d+|\\d{1,3}(\\.\\d{3})+)$")

/** Campo del asistente; [contrato] es el nombre que usa el BFF en `ErrorValidacion.campos`. */
enum class Campo(val contrato: String) {
    POLIZA("numero_poliza"),
    TIPO("tipo_evento"),
    FECHA("ocurrido_el"),
    DESCRIPCION("descripcion"),
    MONTO("monto_estimado"),
    ;

    companion object {
        /** El BFF nombra los campos anidados con punto (`monto_estimado.valor`). */
        fun delContrato(nombre: String): Campo? = entries.firstOrNull { it.contrato == nombre.substringBefore('.') }
    }
}

enum class ProblemaCampo(@StringRes val mensaje: Int) {
    POLIZA_SIN_ELEGIR(R.string.error_poliza),
    TIPO_SIN_ELEGIR(R.string.error_tipo),
    FECHA_REQUERIDA(R.string.error_fecha),
    FECHA_FUTURA(R.string.error_fecha_futura),
    DESCRIPCION_REQUERIDA(R.string.error_descripcion_requerida),
    DESCRIPCION_LONGITUD(R.string.error_descripcion_longitud),
    MONTO_INVALIDO(R.string.error_monto),
}

data class DatosReporte(
    val numeroPoliza: String? = null,
    val tipoEvento: TipoEventoSiniestro? = null,
    val fecha: LocalDate? = null,
    val descripcion: String = "",
    /** Lo que escribió el cliente, en pesos; vacío si no estima un monto. */
    val monto: String = "",
) {
    fun tieneValor(campo: Campo): Boolean = when (campo) {
        Campo.POLIZA -> numeroPoliza != null
        Campo.TIPO -> tipoEvento != null
        Campo.FECHA -> fecha != null
        Campo.DESCRIPCION -> descripcion.isNotEmpty()
        Campo.MONTO -> monto.isNotEmpty()
    }
}

/**
 * Pasos del asistente. La web junta la póliza con la información; en la app va aparte porque la
 * lista no cabe cómoda junto al formulario. La evidencia (MOB-F05) es opcional y no tiene campos.
 */
enum class PasoReporte(val campos: List<Campo>) {
    POLIZA(listOf(Campo.POLIZA)),
    INFORMACION(listOf(Campo.TIPO, Campo.FECHA, Campo.DESCRIPCION, Campo.MONTO)),
    EVIDENCIA(emptyList()),
    REVISION(emptyList()),
    ;

    companion object {
        fun de(campo: Campo): PasoReporte = entries.first { campo in it.campos }
    }
}

/** Mismas reglas que `SolicitudSiniestro` en contratos/bff-movil. */
object ValidadorReporte {
    fun validar(campo: Campo, datos: DatosReporte, hoy: LocalDate): ProblemaCampo? = when (campo) {
        Campo.POLIZA -> ProblemaCampo.POLIZA_SIN_ELEGIR.si(datos.numeroPoliza == null)
        Campo.TIPO -> ProblemaCampo.TIPO_SIN_ELEGIR.si(datos.tipoEvento == null)
        Campo.FECHA -> ProblemaCampo.FECHA_REQUERIDA.si(datos.fecha == null)
            ?: ProblemaCampo.FECHA_FUTURA.si(datos.fecha?.isAfter(hoy) == true)
        Campo.DESCRIPCION -> ProblemaCampo.DESCRIPCION_REQUERIDA.si(datos.descripcion.isBlank())
            ?: ProblemaCampo.DESCRIPCION_LONGITUD.si(datos.descripcion.trim().length !in DESCRIPCION)
        Campo.MONTO -> ProblemaCampo.MONTO_INVALIDO.si(datos.monto.isNotBlank() && montoEnPesos(datos.monto) == null)
    }

    /** Problema que se muestra para un campo que el BFF rechazó en un 422. */
    fun rechazoDe(campo: Campo, codigo: String): ProblemaCampo = when (campo) {
        Campo.POLIZA -> ProblemaCampo.POLIZA_SIN_ELEGIR
        Campo.TIPO -> ProblemaCampo.TIPO_SIN_ELEGIR
        Campo.FECHA -> if (codigo == "fecha_futura") ProblemaCampo.FECHA_FUTURA else ProblemaCampo.FECHA_REQUERIDA
        Campo.DESCRIPCION -> ProblemaCampo.DESCRIPCION_LONGITUD
        Campo.MONTO -> ProblemaCampo.MONTO_INVALIDO
    }

    /**
     * El monto en pesos, o null si está vacío o no es un entero válido. Nunca se reinterpreta: un
     * texto con decimales o con más de [DIGITOS_MONTO] dígitos es un error, no otro monto.
     */
    fun montoEnPesos(monto: String): Long? {
        val texto = monto.trim()
        if (!MONTO.matches(texto)) return null
        return texto.replace(".", "").takeIf { it.length <= DIGITOS_MONTO }?.toLongOrNull()
    }

    private fun ProblemaCampo.si(condicion: Boolean) = takeIf { condicion }
}
