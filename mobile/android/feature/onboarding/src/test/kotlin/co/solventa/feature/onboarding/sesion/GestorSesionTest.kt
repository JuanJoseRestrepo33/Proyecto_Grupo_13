package co.solventa.feature.onboarding.sesion

import co.solventa.core.network.EstadoIdentidad
import co.solventa.feature.onboarding.AlmacenFalso
import co.solventa.feature.onboarding.sesionPrueba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestorSesionTest {

    @Test
    fun `la sesion actualiza la identidad del cliente en memoria`() {
        val gestor = GestorSesion()
        gestor.actualizarIdentidad(EstadoIdentidad.VERIFICADA)
        assertNull("sin sesión no hay nada que actualizar", gestor.sesion)
        assertFalse(gestor.identidadVerificada)
        gestor.sesion = sesionPrueba()
        gestor.actualizarIdentidad(EstadoIdentidad.VERIFICADA)
        assertTrue(gestor.identidadVerificada)
        assertEquals("ACC-1", gestor.tokenAcceso)
        assertEquals("REF-1", gestor.tokenRefresco)
    }

    @Test
    fun `una renovacion reemplaza la sesion y rota el token guardado para la huella`() {
        val almacen = AlmacenFalso("REF-1")
        val gestor = GestorSesion(almacen).apply { sesion = sesionPrueba() }
        gestor.alRenovar(sesionPrueba(refresco = "REF-2"))
        assertEquals("REF-2", gestor.tokenRefresco)
        assertEquals("REF-2", almacen.token)
        assertFalse(gestor.biometriaDesactivada)
    }

    @Test
    fun `sin huella activa la renovacion no guarda ningun token`() {
        val almacen = AlmacenFalso()
        GestorSesion(almacen).alRenovar(sesionPrueba(refresco = "REF-2"))
        assertNull(almacen.token)
    }

    @Test
    fun `si el token rotado no se puede guardar se desactiva la huella y se avisa`() {
        val almacen = AlmacenFalso("REF-1").apply { llaveCreable = false }
        val gestor = GestorSesion(almacen)
        gestor.alRenovar(sesionPrueba(refresco = "REF-2"))
        assertNull("el token viejo ya no sirve: se borra", almacen.token)
        assertTrue(gestor.biometriaDesactivada)
        assertEquals("la sesión renovada sigue vigente", "REF-2", gestor.tokenRefresco)
        gestor.avisoBiometriaAtendido()
        assertFalse(gestor.biometriaDesactivada)
    }

    @Test
    fun `un token de refresco rechazado cierra la sesion y borra el token de la huella`() {
        val almacen = AlmacenFalso("REF-1")
        val gestor = GestorSesion(almacen).apply { sesion = sesionPrueba() }
        gestor.alVencer(tokenRechazado = true)
        assertNull(gestor.sesion)
        assertNull(almacen.token)
        assertTrue(gestor.vencida)
        gestor.vencimientoAtendido()
        assertFalse(gestor.vencida)
    }

    @Test
    fun `una renovacion fallida por otra causa conserva la huella para volver a entrar`() {
        val almacen = AlmacenFalso("REF-1")
        val gestor = GestorSesion(almacen).apply { sesion = sesionPrueba() }
        gestor.alVencer(tokenRechazado = false)
        assertNull(gestor.tokenAcceso)
        assertEquals("REF-1", almacen.token)
        assertTrue(gestor.vencida)
    }
}
