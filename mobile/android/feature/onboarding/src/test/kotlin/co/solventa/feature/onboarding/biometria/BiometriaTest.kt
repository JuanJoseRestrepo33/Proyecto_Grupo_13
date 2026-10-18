package co.solventa.feature.onboarding.biometria

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import co.solventa.core.network.EstadoIdentidad
import co.solventa.feature.onboarding.AlmacenFalso
import co.solventa.feature.onboarding.R
import co.solventa.feature.onboarding.cliente
import co.solventa.feature.onboarding.sesion.GestorSesion
import co.solventa.feature.onboarding.sesionPrueba
import javax.crypto.Cipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometriaTest {
    private val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val resultados = mutableListOf<ResultadoBiometrico>()
    private var cancelaciones = 0
    private val sesionBiometrica =
        SesionBiometrica(cancelar = { cancelaciones += 1 }, alTerminar = { resultados += it })

    @Test
    fun `al tercer fallo cancela el dialogo y vuelve a la contrasena una sola vez`() {
        repeat(2) { sesionBiometrica.fallo() }
        assertTrue("dos fallos dejan el diálogo abierto", resultados.isEmpty())
        sesionBiometrica.fallo()
        // El sistema avisa la cancelación como error; no debe producir un segundo resultado.
        sesionBiometrica.error(BiometricPrompt.ERROR_CANCELED)
        sesionBiometrica.fallo()
        assertEquals(listOf<ResultadoBiometrico>(ResultadoBiometrico.Fallido), resultados)
        assertEquals(1, cancelaciones)
    }

    @Test
    fun `una lectura correcta entrega el cipher desbloqueado`() {
        sesionBiometrica.fallo()
        sesionBiometrica.exito(cipher)
        assertEquals(listOf(ResultadoBiometrico.Desbloqueado(cipher)), resultados)
    }

    @Test
    fun `un exito sin cipher se trata como biometria no disponible`() {
        sesionBiometrica.exito(null)
        assertEquals(listOf<ResultadoBiometrico>(ResultadoBiometrico.NoDisponible), resultados)
    }

    @Test
    fun `los errores del sistema se traducen al respaldo correcto`() {
        val casos = mapOf(
            BiometricPrompt.ERROR_NEGATIVE_BUTTON to ResultadoBiometrico.Cancelado,
            BiometricPrompt.ERROR_USER_CANCELED to ResultadoBiometrico.Cancelado,
            BiometricPrompt.ERROR_LOCKOUT to ResultadoBiometrico.Fallido,
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT to ResultadoBiometrico.Fallido,
            BiometricPrompt.ERROR_HW_UNAVAILABLE to ResultadoBiometrico.NoDisponible,
            BiometricPrompt.ERROR_NO_BIOMETRICS to ResultadoBiometrico.NoDisponible,
        )
        casos.forEach { (codigo, esperado) ->
            val obtenidos = mutableListOf<ResultadoBiometrico>()
            SesionBiometrica(cancelar = {}, alTerminar = { obtenidos += it }).error(codigo)
            assertEquals("código $codigo", listOf(esperado), obtenidos)
        }
    }

    @Test
    fun `la disponibilidad distingue sensor listo, sin enrolar y sin sensor`() {
        val casos = mapOf(
            BiometricManager.BIOMETRIC_SUCCESS to DisponibilidadBiometrica.DISPONIBLE,
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED to DisponibilidadBiometrica.SIN_ENROLAR,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE to DisponibilidadBiometrica.SIN_SENSOR,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE to DisponibilidadBiometrica.SIN_SENSOR,
        )
        casos.forEach { (estado, esperada) -> assertEquals("estado $estado", esperada, disponibilidadDe(estado)) }
    }

    @Test
    fun `solo se ofrece a verificados con sensor y sin biometria activa`() {
        val verificado = cliente(EstadoIdentidad.VERIFICADA)
        val disponible = DisponibilidadBiometrica.DISPONIBLE
        assertTrue(debeOfrecerBiometria(verificado, disponible, AlmacenFalso()))
        assertFalse(debeOfrecerBiometria(cliente(), disponible, AlmacenFalso()))
        assertFalse(debeOfrecerBiometria(cliente(EstadoIdentidad.EN_REVISION), disponible, AlmacenFalso()))
        assertFalse(debeOfrecerBiometria(verificado, DisponibilidadBiometrica.SIN_SENSOR, AlmacenFalso()))
        assertFalse(debeOfrecerBiometria(verificado, DisponibilidadBiometrica.SIN_ENROLAR, AlmacenFalso()))
        assertFalse(debeOfrecerBiometria(verificado, disponible, AlmacenFalso("REF-0")))
    }

    @Test
    fun `activar guarda el token de la sesion y lo confirma con la biometria`() {
        val almacen = AlmacenFalso()
        val gestor = GestorSesion().apply { sesion = sesionPrueba(refresco = "REF-7") }
        val viewModel = OfertaBiometriaViewModel(gestor, almacen)
        val desbloquear = requireNotNull(viewModel.prepararActivacion())
        assertTrue(viewModel.estado.activando)
        assertEquals("REF-7", almacen.token)
        viewModel.resultadoBiometrico(ResultadoBiometrico.Desbloqueado(desbloquear))
        assertTrue(viewModel.estado.activada)
        assertFalse(viewModel.estado.activando)
    }

    @Test
    fun `cancelar la activacion borra el token y lo explica`() {
        val almacen = AlmacenFalso()
        val viewModel = OfertaBiometriaViewModel(GestorSesion().apply { sesion = sesionPrueba() }, almacen)
        viewModel.prepararActivacion()
        viewModel.resultadoBiometrico(ResultadoBiometrico.Cancelado)
        assertNull(almacen.token)
        assertFalse(viewModel.estado.activada)
        assertEquals(R.string.oferta_no_activada, viewModel.estado.mensaje)
    }

    @Test
    fun `sin llave o sin sesion no se puede activar`() {
        val sinLlave = AlmacenFalso().apply { llaveCreable = false }
        val conSesion = GestorSesion().apply { sesion = sesionPrueba() }
        val viewModel = OfertaBiometriaViewModel(conSesion, sinLlave)
        assertNull(viewModel.prepararActivacion())
        assertEquals(R.string.oferta_no_activada, viewModel.estado.mensaje)
        assertNull(OfertaBiometriaViewModel(GestorSesion(), AlmacenFalso()).prepararActivacion())
    }
}
