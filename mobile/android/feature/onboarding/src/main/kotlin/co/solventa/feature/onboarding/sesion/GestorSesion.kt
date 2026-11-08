package co.solventa.feature.onboarding.sesion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.solventa.core.network.EstadoIdentidad
import co.solventa.core.network.Sesion
import co.solventa.core.network.SesionBff
import co.solventa.feature.onboarding.biometria.AlmacenToken
import co.solventa.feature.onboarding.biometria.rotar

/**
 * Sesión vigente, solo en memoria. El token de acceso nunca se persiste; lo único que queda en el
 * dispositivo es el token de refresco cifrado por [almacen], que se rota con cada renovación.
 *
 * El cliente del BFF lo usa como [SesionBff]: renueva ante un 401 y, si no puede, marca [vencida]
 * para que la navegación vuelva a Ingreso (WEB-F13).
 */
class GestorSesion(private val almacen: AlmacenToken? = null) : SesionBff {
    // Las pantallas escriben desde el hilo principal y la renovación desde OkHttp: toda escritura pasa por
    // este candado para que una copia vieja no restaure un token de refresco ya consumido.
    private val candado = Any()

    /**
     * Avisa cuando cambia la cuenta (otra cuenta, cierre o vencimiento), no en cada renovación. [antes]
     * corre antes de reemplazar la sesión: el envío en segundo plano se detiene sin ver la nueva.
     */
    var observador: ObservadorCuenta? = null

    @Volatile
    var sesion: Sesion? = null
        set(valor) {
            val anterior = field?.cliente?.id
            val nuevo = valor?.cliente?.id
            val cambia = anterior != nuevo
            if (cambia) observador?.antes(nuevo)
            synchronized(candado) { field = valor }
            if (cambia) observador?.despues(nuevo)
        }

    /** La sesión venció y no se pudo renovar: la navegación vuelve a Ingreso y lo consume. */
    var vencida by mutableStateOf(false)
        private set

    /** El token rotado no se pudo guardar: la huella quedó desactivada y hay que avisarle al cliente. */
    var biometriaDesactivada by mutableStateOf(false)
        private set

    override val tokenAcceso: String? get() = sesion?.tokenAcceso

    override val tokenRefresco: String? get() = sesion?.tokenRefresco

    override val clienteId: String? get() = sesion?.cliente?.id

    val identidadVerificada: Boolean get() = sesion?.cliente?.estadoIdentidad == EstadoIdentidad.VERIFICADA

    fun actualizarIdentidad(estado: EstadoIdentidad) = synchronized(candado) {
        sesion = sesion?.let { it.copy(cliente = it.cliente.copy(estadoIdentidad = estado)) }
    }

    override fun alRenovar(nueva: Sesion) {
        val rotado = synchronized(candado) {
            sesion = nueva
            almacen?.rotar(nueva.tokenRefresco) != false
        }
        if (!rotado) avisarBiometriaDesactivada()
    }

    override fun alVencer(tokenRechazado: Boolean) {
        synchronized(candado) {
            sesion = null
            // El token guardado para la huella es el mismo que se rechazó: ya no sirve.
            if (tokenRechazado) almacen?.borrar()
        }
        vencida = true
    }

    /** El cliente cierra sesión: la navegación vuelve a Ingreso. La huella sigue activa para volver a entrar. */
    fun cerrar() {
        sesion = null
        vencida = true
    }

    fun vencimientoAtendido() {
        vencida = false
    }

    fun avisarBiometriaDesactivada() {
        biometriaDesactivada = true
    }

    fun avisoBiometriaAtendido() {
        biometriaDesactivada = false
    }
}

/** Lo que la app hace al cambiar de cuenta; [antes] y [despues] reciben el `cliente.id` nuevo o null. */
interface ObservadorCuenta {
    fun antes(nuevoCliente: String?)

    fun despues(cliente: String?)
}
