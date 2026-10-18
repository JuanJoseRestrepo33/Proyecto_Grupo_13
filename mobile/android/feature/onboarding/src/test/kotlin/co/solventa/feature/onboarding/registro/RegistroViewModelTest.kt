package co.solventa.feature.onboarding.registro

import co.solventa.core.network.ErrorCampo
import co.solventa.core.network.EstadoIdentidad
import co.solventa.core.network.Registro
import co.solventa.core.network.SolicitudRegistro
import co.solventa.feature.onboarding.BffFalso
import co.solventa.feature.onboarding.R
import co.solventa.feature.onboarding.ReglaMain
import co.solventa.feature.onboarding.fallaBff
import co.solventa.feature.onboarding.otp
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RegistroViewModelTest {
    @get:Rule
    val main = ReglaMain()

    private val api = BffFalso()
    private val viewModel = RegistroViewModel(api)

    private fun escribir(campo: Campo, cambio: (DatosRegistro) -> DatosRegistro) = viewModel.actualizar(campo, cambio)

    private fun completarHastaContrasena() {
        escribir(Campo.NOMBRE) { it.copy(nombre = "  Juan Pérez ") }
        escribir(Campo.CORREO) { it.copy(correo = "juan@ejemplo.co ") }
        escribir(Campo.CELULAR) { it.copy(celular = "3109876543") }
        viewModel.continuar()
        escribir(Campo.DOCUMENTO) { it.copy(numeroDocumento = "1098765432") }
        viewModel.continuar()
        escribir(Campo.CONTRASENA) { it.copy(contrasena = "Clave2026") }
        escribir(Campo.CONFIRMACION) { it.copy(confirmacion = "Clave2026") }
        escribir(Campo.TERMINOS) { it.copy(aceptaTerminos = true) }
    }

    @Test
    fun `continuar con el paso incompleto muestra los errores y no avanza`() {
        viewModel.continuar()
        assertEquals(PasoRegistro.DATOS, viewModel.estado.paso)
        assertEquals(ProblemaCampo.REQUERIDO, viewModel.estado.error(Campo.NOMBRE))
        assertEquals(ProblemaCampo.REQUERIDO, viewModel.estado.error(Campo.CELULAR))
        assertNull("los pasos siguientes no se validan todavía", viewModel.estado.error(Campo.DOCUMENTO))
    }

    @Test
    fun `el error de un campo aparece al salir de el y se actualiza mientras se corrige`() {
        escribir(Campo.CORREO) { it.copy(correo = "juan@") }
        assertNull("no se muestra mientras escribe por primera vez", viewModel.estado.error(Campo.CORREO))
        viewModel.salirDe(Campo.CORREO)
        assertEquals(ProblemaCampo.CORREO_FORMATO, viewModel.estado.error(Campo.CORREO))
        escribir(Campo.CORREO) { it.copy(correo = "juan@ejemplo.co") }
        assertNull(viewModel.estado.error(Campo.CORREO))
    }

    @Test
    fun `salir de un campo vacio no lo marca como error`() {
        viewModel.salirDe(Campo.NOMBRE)
        assertNull(viewModel.estado.error(Campo.NOMBRE))
    }

    @Test
    fun `el asistente avanza paso a paso y crea la cuenta pendiente de verificacion`() {
        var enviada: SolicitudRegistro? = null
        api.registro = { enviada = it; Registro("REG-1", EstadoIdentidad.PENDIENTE_VERIFICACION, otp()) }
        completarHastaContrasena()
        assertEquals(PasoRegistro.CONTRASENA, viewModel.estado.paso)
        viewModel.continuar()

        assertEquals("REG-1", viewModel.estado.registro?.id)
        assertEquals(EstadoIdentidad.PENDIENTE_VERIFICACION, viewModel.estado.registro?.estadoIdentidad)
        assertFalse(viewModel.estado.enviando)
        val solicitud = requireNotNull(enviada)
        assertEquals("Juan Pérez", solicitud.nombreCompleto)
        assertEquals("juan@ejemplo.co", solicitud.correo)
        assertEquals(VERSION_TERMINOS, solicitud.versionTerminos)
        viewModel.registroAtendido()
        assertNull(viewModel.estado.registro)
    }

    @Test
    fun `sin aceptar los terminos no se envia el registro`() {
        completarHastaContrasena()
        escribir(Campo.TERMINOS) { it.copy(aceptaTerminos = false) }
        viewModel.continuar()
        assertEquals(ProblemaCampo.TERMINOS_SIN_ACEPTAR, viewModel.estado.error(Campo.TERMINOS))
        assertTrue(api.llamadas.isEmpty())
    }

    @Test
    fun `un correo ya registrado devuelve al primer paso con el error en el campo`() {
        api.registro = { throw fallaBff(409, "correo_ya_registrado") }
        completarHastaContrasena()
        viewModel.continuar()
        assertEquals(PasoRegistro.DATOS, viewModel.estado.paso)
        assertEquals(ProblemaCampo.CORREO_YA_REGISTRADO, viewModel.estado.error(Campo.CORREO))
        escribir(Campo.CORREO) { it.copy(correo = "otro@ejemplo.co") }
        assertNull("al editar, el error del servidor deja de aplicar", viewModel.estado.error(Campo.CORREO))
    }

    @Test
    fun `un documento ya registrado devuelve al paso del documento`() {
        api.registro = { throw fallaBff(409, "documento_ya_registrado") }
        completarHastaContrasena()
        viewModel.continuar()
        assertEquals(PasoRegistro.DOCUMENTO, viewModel.estado.paso)
        assertEquals(ProblemaCampo.DOCUMENTO_YA_REGISTRADO, viewModel.estado.error(Campo.DOCUMENTO))
    }

    @Test
    fun `los campos rechazados por el BFF se marcan y se vuelve al primero`() {
        api.registro = {
            throw fallaBff(422, "solicitud_invalida", campos = listOf(ErrorCampo("celular", "formato_invalido")))
        }
        completarHastaContrasena()
        viewModel.continuar()
        assertEquals(PasoRegistro.DATOS, viewModel.estado.paso)
        assertEquals(ProblemaCampo.CELULAR_FORMATO, viewModel.estado.error(Campo.CELULAR))
    }

    @Test
    fun `un error sin campos o sin conexion se muestra como mensaje general`() {
        api.registro = { throw fallaBff(500, "error_interno") }
        completarHastaContrasena()
        viewModel.continuar()
        assertEquals(R.string.error_generico, viewModel.estado.errorGeneral)
        assertEquals(PasoRegistro.CONTRASENA, viewModel.estado.paso)

        api.registro = { throw IOException("sin red") }
        viewModel.continuar()
        assertEquals(R.string.error_sin_conexion, viewModel.estado.errorGeneral)
    }

    @Test
    fun `atras retrocede un paso y en el primero pide salir del asistente`() {
        completarHastaContrasena()
        assertTrue(viewModel.atras())
        assertEquals(PasoRegistro.DOCUMENTO, viewModel.estado.paso)
        assertTrue(viewModel.atras())
        assertFalse(viewModel.atras())
        assertEquals("los datos se conservan al volver", "3109876543", viewModel.estado.datos.celular)
    }
}
