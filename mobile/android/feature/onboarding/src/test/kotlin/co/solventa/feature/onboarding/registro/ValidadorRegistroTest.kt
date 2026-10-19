package co.solventa.feature.onboarding.registro

import co.solventa.core.network.TipoDocumento
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ValidadorRegistroTest {
    private val validos = DatosRegistro(
        nombre = "Ana María Rojas",
        correo = "ana@ejemplo.co",
        celular = "3001234567",
        numeroDocumento = "1020304050",
        contrasena = "Clave2026",
        confirmacion = "Clave2026",
        aceptaTerminos = true,
    )

    private fun problema(campo: Campo, datos: DatosRegistro) = ValidadorRegistro.validar(campo, datos)

    @Test
    fun `datos completos no tienen problemas en ningun campo`() {
        Campo.entries.forEach { assertNull(it.name, problema(it, validos)) }
    }

    @Test
    fun `los campos vacios son obligatorios`() {
        val vacios = DatosRegistro()
        listOf(Campo.NOMBRE, Campo.CORREO, Campo.CELULAR, Campo.DOCUMENTO, Campo.CONTRASENA, Campo.CONFIRMACION)
            .forEach { assertEquals(it.name, ProblemaCampo.REQUERIDO, problema(it, vacios)) }
        assertEquals(ProblemaCampo.TERMINOS_SIN_ACEPTAR, problema(Campo.TERMINOS, vacios))
    }

    @Test
    fun `nombre correo y celular siguen las reglas del contrato`() {
        assertEquals(ProblemaCampo.NOMBRE_LONGITUD, problema(Campo.NOMBRE, validos.copy(nombre = " Al ")))
        assertEquals(ProblemaCampo.CORREO_FORMATO, problema(Campo.CORREO, validos.copy(correo = "ana@ejemplo")))
        assertEquals(ProblemaCampo.CELULAR_FORMATO, problema(Campo.CELULAR, validos.copy(celular = "6011234567")))
        assertEquals(ProblemaCampo.CELULAR_FORMATO, problema(Campo.CELULAR, validos.copy(celular = "300123456")))
    }

    @Test
    fun `la cedula solo admite digitos y los demas documentos letras y numeros`() {
        val cedula = validos.copy(numeroDocumento = "AB12345")
        assertEquals(ProblemaCampo.CEDULA_FORMATO, problema(Campo.DOCUMENTO, cedula))
        assertEquals(ProblemaCampo.CEDULA_FORMATO, problema(Campo.DOCUMENTO, validos.copy(numeroDocumento = "12345")))
        assertNull(problema(Campo.DOCUMENTO, cedula.copy(tipoDocumento = TipoDocumento.PASAPORTE)))
        val corto = validos.copy(tipoDocumento = TipoDocumento.CEDULA_EXTRANJERIA, numeroDocumento = "AB1")
        assertEquals(ProblemaCampo.DOCUMENTO_FORMATO, problema(Campo.DOCUMENTO, corto))
    }

    @Test
    fun `la contrasena exige letras y numeros y la confirmacion debe coincidir`() {
        val debil = ProblemaCampo.CONTRASENA_DEBIL
        assertEquals(debil, problema(Campo.CONTRASENA, validos.copy(contrasena = "solo-letras")))
        assertEquals(debil, problema(Campo.CONTRASENA, validos.copy(contrasena = "Ab1")))
        val distinta = validos.copy(confirmacion = "Otra2026")
        assertEquals(ProblemaCampo.CONFIRMACION_DISTINTA, problema(Campo.CONFIRMACION, distinta))
    }

    @Test
    fun `los nombres del contrato se traducen a campos del asistente`() {
        assertEquals(Campo.DOCUMENTO, Campo.delContrato("numero_documento"))
        assertEquals(Campo.TERMINOS, Campo.delContrato("version_terminos"))
        assertNull(Campo.delContrato(""))
        assertNull(Campo.delContrato("desconocido"))
        assertEquals(PasoRegistro.CONTRASENA, PasoRegistro.de(Campo.TERMINOS))
        val pasaporte = TipoDocumento.PASAPORTE
        assertEquals(ProblemaCampo.DOCUMENTO_FORMATO, ValidadorRegistro.formatoDe(Campo.DOCUMENTO, pasaporte))
        assertEquals(ProblemaCampo.CONTRASENA_DEBIL, ValidadorRegistro.formatoDe(Campo.CONFIRMACION, pasaporte))
    }

    @Test
    fun `los datos no exponen la contrasena al imprimirse`() {
        assertEquals(false, validos.toString().contains("Clave2026"))
    }
}
