package co.solventa.feature.onboarding.ingreso

import co.solventa.core.network.EstadoIdentidad
import co.solventa.feature.onboarding.AlmacenFalso
import co.solventa.feature.onboarding.BffFalso
import co.solventa.feature.onboarding.R
import co.solventa.feature.onboarding.ReglaMain
import co.solventa.feature.onboarding.biometria.DisponibilidadBiometrica
import co.solventa.feature.onboarding.biometria.ResultadoBiometrico
import co.solventa.feature.onboarding.fallaBff
import co.solventa.feature.onboarding.sesion.GestorSesion
import co.solventa.feature.onboarding.sesionPrueba
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class IngresoViewModelTest {
    @get:Rule
    val main = ReglaMain()

    private val api = BffFalso()
    private val gestor = GestorSesion()

    private fun viewModel(
        almacen: AlmacenFalso = AlmacenFalso(),
        disponibilidad: DisponibilidadBiometrica = DisponibilidadBiometrica.DISPONIBLE,
    ) = IngresoViewModel(api, gestor, almacen, disponibilidad)

    private fun IngresoViewModel.ingresarCon(correo: String, contrasena: String) {
        cambiarCorreo(correo)
        cambiarContrasena(contrasena)
        ingresar()
    }

    @Test
    fun `sin correo valido o sin contrasena no se llama al BFF`() {
        val viewModel = viewModel()
        viewModel.ingresarCon("ana@", "Solventa2026")
        assertEquals(R.string.ingreso_datos_incompletos, viewModel.estado.mensaje)
        viewModel.ingresarCon("ana@ejemplo.co", "")
        assertTrue(api.llamadas.isEmpty())
    }

    @Test
    fun `credenciales erradas se pueden reintentar sin bloquear la cuenta`() {
        api.ingreso = { throw fallaBff(401, "credenciales_invalidas") }
        val viewModel = viewModel()
        repeat(5) { viewModel.ingresarCon("ana@ejemplo.co", "Errada$it") }
        assertEquals(R.string.ingreso_credenciales_invalidas, viewModel.estado.mensaje)
        assertEquals(5, api.llamadas.size)

        api.ingreso = { sesionPrueba(EstadoIdentidad.VERIFICADA) }
        viewModel.ingresarCon("ana@ejemplo.co", "Solventa2026")
        assertEquals(EstadoIdentidad.VERIFICADA, viewModel.estado.ingresado?.estadoIdentidad)
        assertEquals("ACC-1", gestor.tokenAcceso)
        viewModel.ingresoAtendido()
        assertNull(viewModel.estado.ingresado)
        assertEquals("la contraseña no queda en el estado", "", viewModel.estado.contrasena)
    }

    @Test
    fun `otros errores y la falta de conexion se explican`() {
        val viewModel = viewModel()
        api.ingreso = { throw fallaBff(503, "no_disponible") }
        viewModel.ingresarCon("ana@ejemplo.co", "Solventa2026")
        assertEquals(R.string.error_generico, viewModel.estado.mensaje)
        api.ingreso = { throw IOException("sin red") }
        viewModel.ingresarCon("ana@ejemplo.co", "Solventa2026")
        assertEquals(R.string.error_sin_conexion, viewModel.estado.mensaje)
    }

    @Test
    fun `la biometria se ofrece solo con token guardado y sensor disponible`() {
        assertFalse(viewModel(AlmacenFalso()).estado.biometriaActiva)
        assertFalse(viewModel(AlmacenFalso("REF-0"), DisponibilidadBiometrica.SIN_SENSOR).estado.biometriaActiva)
        assertFalse(viewModel(AlmacenFalso("REF-0"), DisponibilidadBiometrica.SIN_ENROLAR).estado.biometriaActiva)
        assertTrue(viewModel(AlmacenFalso("REF-0")).estado.biometriaActiva)
    }

    @Test
    fun `la huella desbloquea el token, renueva la sesion y guarda el token rotado`() {
        val almacen = AlmacenFalso("REF-0")
        api.renovacion = { sesionPrueba(EstadoIdentidad.VERIFICADA, refresco = "REF-NUEVO") }
        val viewModel = viewModel(almacen)
        val cipher = requireNotNull(viewModel.prepararBiometria())
        viewModel.resultadoBiometrico(ResultadoBiometrico.Desbloqueado(cipher))
        assertEquals(listOf("renovacion:REF-0"), api.llamadas)
        assertEquals("REF-NUEVO", almacen.token)
        assertEquals(EstadoIdentidad.VERIFICADA, viewModel.estado.ingresado?.estadoIdentidad)
    }

    @Test
    fun `entrar con contrasena con la biometria activa actualiza el token guardado`() {
        val almacen = AlmacenFalso("REF-0")
        api.ingreso = { sesionPrueba(refresco = "REF-CONTRASENA") }
        viewModel(almacen).ingresarCon("ana@ejemplo.co", "Solventa2026")
        assertEquals("REF-CONTRASENA", almacen.token)
    }

    @Test
    fun `entrar con contrasena sin biometria no guarda ningun token`() {
        val almacen = AlmacenFalso()
        api.ingreso = { sesionPrueba() }
        viewModel(almacen).ingresarCon("ana@ejemplo.co", "Solventa2026")
        assertNull(almacen.token)
    }

    @Test
    fun `un token de refresco rechazado se borra y queda la contrasena`() {
        val almacen = AlmacenFalso("REF-VENCIDO")
        api.renovacion = { throw fallaBff(401, "token_refresco_invalido") }
        val viewModel = viewModel(almacen)
        viewModel.resultadoBiometrico(ResultadoBiometrico.Desbloqueado(almacen.cipher))
        assertNull(almacen.token)
        assertFalse(viewModel.estado.biometriaActiva)
        assertEquals(R.string.biometria_vencida, viewModel.estado.mensaje)
    }

    @Test
    fun `tres lecturas fallidas o sensor caido llevan a la contrasena con una explicacion`() {
        val almacen = AlmacenFalso("REF-0")
        val viewModel = viewModel(almacen)
        viewModel.resultadoBiometrico(ResultadoBiometrico.Fallido)
        assertEquals(R.string.biometria_fallida, viewModel.estado.mensaje)
        viewModel.resultadoBiometrico(ResultadoBiometrico.NoDisponible)
        assertEquals(R.string.biometria_no_disponible, viewModel.estado.mensaje)
        assertTrue("fallar la lectura no desactiva la biometría", viewModel.estado.biometriaActiva)
        assertEquals("REF-0", almacen.token)
        assertTrue(api.llamadas.isEmpty())
    }

    @Test
    fun `cancelar el dialogo no muestra ningun error`() {
        val viewModel = viewModel(AlmacenFalso("REF-0"))
        viewModel.resultadoBiometrico(ResultadoBiometrico.Cancelado)
        assertNull(viewModel.estado.mensaje)
    }

    @Test
    fun `una llave invalidada por un nuevo enrolamiento desactiva la biometria`() {
        val almacen = AlmacenFalso("REF-0").apply { llaveInvalidada = true }
        val viewModel = viewModel(almacen)
        assertNull(viewModel.prepararBiometria())
        assertNull(almacen.token)
        assertFalse(viewModel.estado.biometriaActiva)
        assertEquals(R.string.biometria_invalidada, viewModel.estado.mensaje)
    }

    @Test
    fun `un sobre ilegible desactiva la biometria sin llamar al BFF`() {
        val almacen = object : co.solventa.feature.onboarding.biometria.AlmacenToken by AlmacenFalso("REF-0") {
            override fun leer(desbloqueado: javax.crypto.Cipher): String? = null
        }
        val viewModel = IngresoViewModel(api, gestor, almacen, DisponibilidadBiometrica.DISPONIBLE)
        viewModel.resultadoBiometrico(ResultadoBiometrico.Desbloqueado(AlmacenFalso().cipher))
        assertEquals(R.string.biometria_invalidada, viewModel.estado.mensaje)
        assertTrue(api.llamadas.isEmpty())
    }

    @Test
    fun `el estado no expone la contrasena al imprimirse`() {
        val viewModel = viewModel()
        viewModel.cambiarContrasena("Secreta2026")
        assertFalse(viewModel.estado.toString().contains("Secreta2026"))
    }
}
