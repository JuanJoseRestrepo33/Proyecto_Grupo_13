package co.solventa.feature.onboarding.registro

import androidx.annotation.StringRes
import co.solventa.core.network.TipoDocumento
import co.solventa.feature.onboarding.R

/** Versión de los términos y la política de datos que muestra el asistente. */
const val VERSION_TERMINOS = "2026-09"

private val CORREO = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
private val CELULAR = Regex("^3\\d{9}$")
private val CEDULA = Regex("^\\d{6,10}$")
private val DOCUMENTO = Regex("^[A-Za-z0-9]{5,15}$")
private val NOMBRE = 3..120
private val CONTRASENA = 8..64

/** Campo del asistente; [contrato] es el nombre que usa el BFF en `ErrorValidacion.campos`. */
enum class Campo(val contrato: String) {
    NOMBRE("nombre_completo"),
    CORREO("correo"),
    CELULAR("celular"),
    DOCUMENTO("numero_documento"),
    CONTRASENA("contrasena"),
    CONFIRMACION(""),
    TERMINOS("version_terminos"),
    ;

    companion object {
        fun delContrato(nombre: String): Campo? =
            entries.firstOrNull { it.contrato.isNotEmpty() && it.contrato == nombre }
    }
}

enum class ProblemaCampo(@StringRes val mensaje: Int) {
    REQUERIDO(R.string.error_requerido),
    NOMBRE_LONGITUD(R.string.error_nombre),
    CORREO_FORMATO(R.string.error_correo),
    CELULAR_FORMATO(R.string.error_celular),
    CEDULA_FORMATO(R.string.error_cedula),
    DOCUMENTO_FORMATO(R.string.error_documento),
    CONTRASENA_DEBIL(R.string.error_contrasena),
    CONFIRMACION_DISTINTA(R.string.error_confirmacion),
    TERMINOS_SIN_ACEPTAR(R.string.error_terminos),
    CORREO_YA_REGISTRADO(R.string.error_correo_registrado),
    DOCUMENTO_YA_REGISTRADO(R.string.error_documento_registrado),
}

data class DatosRegistro(
    val nombre: String = "",
    val correo: String = "",
    val celular: String = "",
    val tipoDocumento: TipoDocumento = TipoDocumento.CEDULA_CIUDADANIA,
    val numeroDocumento: String = "",
    val contrasena: String = "",
    val confirmacion: String = "",
    val aceptaTerminos: Boolean = false,
) {
    fun valor(campo: Campo): String = when (campo) {
        Campo.NOMBRE -> nombre
        Campo.CORREO -> correo
        Campo.CELULAR -> celular
        Campo.DOCUMENTO -> numeroDocumento
        Campo.CONTRASENA -> contrasena
        Campo.CONFIRMACION -> confirmacion
        Campo.TERMINOS -> if (aceptaTerminos) "si" else ""
    }

    // La contraseña nunca debe terminar en un log.
    override fun toString() = "DatosRegistro(correo=$correo, numeroDocumento=$numeroDocumento)"
}

/** Pasos del asistente (P04): cada uno valida sus campos antes de avanzar. */
enum class PasoRegistro(val campos: List<Campo>) {
    DATOS(listOf(Campo.NOMBRE, Campo.CORREO, Campo.CELULAR)),
    DOCUMENTO(listOf(Campo.DOCUMENTO)),
    CONTRASENA(listOf(Campo.CONTRASENA, Campo.CONFIRMACION, Campo.TERMINOS)),
    ;

    companion object {
        fun de(campo: Campo): PasoRegistro = entries.first { campo in it.campos }
    }
}

/** Mismas reglas que `SolicitudRegistro` en contratos/bff-movil; la cédula es más estricta. */
object ValidadorRegistro {
    fun validar(campo: Campo, datos: DatosRegistro): ProblemaCampo? = when (campo) {
        Campo.NOMBRE -> requerido(datos.nombre)
            ?: ProblemaCampo.NOMBRE_LONGITUD.si(datos.nombre.trim().length !in NOMBRE)
        Campo.CORREO -> requerido(datos.correo) ?: ProblemaCampo.CORREO_FORMATO.si(!CORREO.matches(datos.correo.trim()))
        Campo.CELULAR -> requerido(datos.celular) ?: ProblemaCampo.CELULAR_FORMATO.si(!CELULAR.matches(datos.celular))
        Campo.DOCUMENTO -> requerido(datos.numeroDocumento) ?: documento(datos)
        Campo.CONTRASENA -> requerido(datos.contrasena) ?: ProblemaCampo.CONTRASENA_DEBIL.si(!segura(datos.contrasena))
        Campo.CONFIRMACION -> requerido(datos.confirmacion)
            ?: ProblemaCampo.CONFIRMACION_DISTINTA.si(datos.confirmacion != datos.contrasena)
        Campo.TERMINOS -> ProblemaCampo.TERMINOS_SIN_ACEPTAR.si(!datos.aceptaTerminos)
    }

    /** Problema genérico de formato para un campo que el BFF rechazó en un 422. */
    fun formatoDe(campo: Campo, tipo: TipoDocumento): ProblemaCampo = when (campo) {
        Campo.NOMBRE -> ProblemaCampo.NOMBRE_LONGITUD
        Campo.CORREO -> ProblemaCampo.CORREO_FORMATO
        Campo.CELULAR -> ProblemaCampo.CELULAR_FORMATO
        Campo.DOCUMENTO -> if (tipo == TipoDocumento.CEDULA_CIUDADANIA) {
            ProblemaCampo.CEDULA_FORMATO
        } else {
            ProblemaCampo.DOCUMENTO_FORMATO
        }
        Campo.CONTRASENA, Campo.CONFIRMACION -> ProblemaCampo.CONTRASENA_DEBIL
        Campo.TERMINOS -> ProblemaCampo.TERMINOS_SIN_ACEPTAR
    }

    private fun documento(datos: DatosRegistro): ProblemaCampo? {
        val cedula = datos.tipoDocumento == TipoDocumento.CEDULA_CIUDADANIA
        val patron = if (cedula) CEDULA else DOCUMENTO
        return formatoDe(Campo.DOCUMENTO, datos.tipoDocumento).si(!patron.matches(datos.numeroDocumento))
    }

    private fun segura(contrasena: String) =
        contrasena.length in CONTRASENA && contrasena.any(Char::isLetter) && contrasena.any(Char::isDigit)

    private fun requerido(valor: String) = ProblemaCampo.REQUERIDO.si(valor.isBlank())

    private fun ProblemaCampo.si(condicion: Boolean) = takeIf { condicion }
}
