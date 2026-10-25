package co.solventa.feature.onboarding.sesion

import co.solventa.core.network.EstadoIdentidad
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
    }
}
