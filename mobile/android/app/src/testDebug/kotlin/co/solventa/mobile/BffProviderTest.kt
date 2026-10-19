package co.solventa.mobile

import co.solventa.core.network.Credenciales
import co.solventa.core.network.EstadoIdentidad
import co.solventa.mobile.mock.CONTRASENA_DEMO
import co.solventa.mobile.mock.CORREO_DEMO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BffProviderTest {
    @Test
    fun `el proveedor debug comparte un mock con el cliente demo y envia el token`() = runBlocking {
        val sesion = BffProvider.create().iniciarSesion(Credenciales(CORREO_DEMO, CONTRASENA_DEMO))
        val cliente = BffProvider.create(tokenAcceso = { sesion.tokenAcceso }).obtenerSesion()
        assertEquals(EstadoIdentidad.VERIFICADA, cliente.estadoIdentidad)
    }
}
