package co.solventa.mobile.navigation

import co.solventa.core.network.BffApi
import co.solventa.core.network.Cliente
import co.solventa.core.network.EstadoIdentidad
import co.solventa.core.network.Sesion
import co.solventa.feature.onboarding.DependenciasOnboarding
import co.solventa.feature.onboarding.biometria.AlmacenToken
import co.solventa.feature.onboarding.biometria.Biometria
import co.solventa.feature.onboarding.biometria.DisponibilidadBiometrica
import co.solventa.feature.onboarding.biometria.PropositoBiometrico
import co.solventa.feature.onboarding.biometria.ResultadoBiometrico
import co.solventa.feature.onboarding.sesion.GestorSesion
import java.lang.reflect.Proxy
import java.time.Instant
import javax.crypto.Cipher
import org.junit.Assert.assertEquals
import org.junit.Test

class RutasEntradaTest {
    private fun cliente(estado: EstadoIdentidad) = Cliente("CLI-1", "Ana", "ana@ejemplo.co", estado)

    private fun dependencias(
        estado: EstadoIdentidad,
        disponibilidad: DisponibilidadBiometrica = DisponibilidadBiometrica.DISPONIBLE,
        tokenGuardado: Boolean = false,
    ): DependenciasOnboarding {
        val sesion = GestorSesion().apply { sesion = Sesion("ACC", "REF", "2026-10-17T10:15:00Z", cliente(estado)) }
        val almacen = object : AlmacenToken {
            override fun hayToken() = tokenGuardado
            override fun guardar(token: String) = true
            override fun prepararLectura(): Cipher? = null
            override fun leer(desbloqueado: Cipher): String? = null
            override fun borrar() = Unit
        }
        val biometria = object : Biometria {
            override fun disponibilidad() = disponibilidad
            override fun autenticar(
                cipher: Cipher,
                proposito: PropositoBiometrico,
                alTerminar: (ResultadoBiometrico) -> Unit,
            ) = Unit
        }
        // Las rutas no llaman al BFF: un proxy basta como BffApi.
        val api = Proxy.newProxyInstance(BffApi::class.java.classLoader, arrayOf(BffApi::class.java)) { _, _, _ ->
            error("no se usa")
        } as BffApi
        return DependenciasOnboarding(api, sesion, almacen, biometria)
    }

    @Test
    fun `la ruta del OTP lleva el registro y el instante de reenvio`() {
        assertEquals("otp/REG-1/1789725630", RutasEntrada.otp("REG-1", Instant.ofEpochSecond(1_789_725_630)))
    }

    @Test
    fun `un cliente sin identidad verificada va a su verificacion`() {
        listOf(EstadoIdentidad.PENDIENTE_VERIFICACION, EstadoIdentidad.EN_REVISION, EstadoIdentidad.RECHAZADA).forEach {
            assertEquals(RutasEntrada.IDENTIDAD, destinoTrasIngreso(cliente(it), dependencias(it)))
        }
    }

    @Test
    fun `un verificado con sensor y sin biometria activa recibe la oferta`() {
        val verificado = EstadoIdentidad.VERIFICADA
        assertEquals(RutasEntrada.BIOMETRIA, destinoTrasIngreso(cliente(verificado), dependencias(verificado)))
        assertEquals(
            RutasEntrada.INICIO,
            destinoTrasIngreso(cliente(verificado), dependencias(verificado, tokenGuardado = true)),
        )
        assertEquals(
            RutasEntrada.INICIO,
            destinoTrasIngreso(cliente(verificado), dependencias(verificado, DisponibilidadBiometrica.SIN_SENSOR)),
        )
    }
}
