package co.solventa.feature.onboarding.sesion

import co.solventa.core.network.EstadoIdentidad
import co.solventa.core.network.Sesion

/**
 * Sesión vigente, solo en memoria. El token de acceso nunca se persiste; lo único que queda en el
 * dispositivo es el token de refresco cifrado por [co.solventa.feature.onboarding.biometria.AlmacenToken].
 */
class GestorSesion {
    @Volatile
    var sesion: Sesion? = null

    val tokenAcceso: String? get() = sesion?.tokenAcceso

    val identidadVerificada: Boolean get() = sesion?.cliente?.estadoIdentidad == EstadoIdentidad.VERIFICADA

    fun actualizarIdentidad(estado: EstadoIdentidad) {
        sesion = sesion?.let { it.copy(cliente = it.cliente.copy(estadoIdentidad = estado)) }
    }
}
