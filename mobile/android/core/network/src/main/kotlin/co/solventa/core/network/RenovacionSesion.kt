package co.solventa.core.network

import java.io.IOException
import kotlinx.serialization.SerializationException
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Lo que el cliente necesita de la sesión de la app para firmar las llamadas y renovarlas ante un 401.
 * Las llamadas llegan desde los hilos de OkHttp.
 */
interface SesionBff {
    val tokenAcceso: String?
    val tokenRefresco: String?

    /** El BFF rotó el par de tokens: el de refresco anterior ya no sirve (criterio 5 de WEB-F13). */
    fun alRenovar(nueva: Sesion)

    /**
     * No se pudo renovar: la app limpia la sesión y vuelve a Ingreso.
     * @param tokenRechazado el BFF rechazó el token de refresco; el guardado para la huella tampoco sirve.
     */
    fun alVencer(tokenRechazado: Boolean)
}

/** Renovación síncrona: corre dentro del [Authenticator], en el hilo de OkHttp de la llamada original. */
internal interface RenovacionApi {
    @POST("sesiones/renovacion")
    fun renovar(@Body solicitud: SolicitudRenovacion): Call<Sesion>
}

internal sealed interface Renovacion {
    data class Renovada(val sesion: Sesion) : Renovacion

    /** 401 `token_refresco_invalido`: vencido, revocado o ya usado. */
    data object Rechazada : Renovacion

    /** Otro error del BFF, un cuerpo ilegible o sin red: la sesión no se puede confirmar. */
    data object Fallida : Renovacion
}

/**
 * Ante un 401 de una ruta protegida renueva la sesión una sola vez y repite la llamada con el token
 * nuevo. Solo hay una renovación en curso: las llamadas que fallaron con el mismo token esperan y
 * reutilizan el resultado. Si no se puede renovar, ni siquiera por falta de red, la sesión vence y la
 * app vuelve a Ingreso; solo un token rechazado borra además el de la huella.
 */
internal class RenovadorSesion(
    private val sesion: SesionBff,
    private val renovar: (String) -> Renovacion,
) : Authenticator {
    private val candado = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val usado = response.request.header(AUTORIZACION)?.removePrefix(BEARER)
        // Sin token la ruta es pública o no había sesión: el 401 es de negocio.
        if (usado == null || esPublica(response.request)) return null
        val nuevo = synchronized(candado) {
            val vigente = sesion.tokenAcceso
            when {
                vigente == null -> null
                // Otra llamada ya renovó: se repite con su token.
                vigente != usado -> vigente
                // Ya se renovó y la llamada repetida volvió a dar 401: no se insiste y la sesión vence.
                response.priorResponse != null -> {
                    sesion.alVencer(tokenRechazado = false)
                    null
                }
                else -> renovarUnaVez()
            }
        }
        return nuevo?.let { response.request.newBuilder().header(AUTORIZACION, BEARER + it).build() }
    }

    private fun renovarUnaVez(): String? {
        val refresco = sesion.tokenRefresco
        val resultado = if (refresco == null) Renovacion.Rechazada else renovar(refresco)
        if (resultado !is Renovacion.Renovada) {
            sesion.alVencer(tokenRechazado = resultado == Renovacion.Rechazada)
            return null
        }
        sesion.alRenovar(resultado.sesion)
        return resultado.sesion.tokenAcceso
    }

    internal companion object {
        const val AUTORIZACION = "Authorization"
        const val BEARER = "Bearer "
        private const val HTTP_NO_AUTORIZADO = 401
        private val PUBLICAS = listOf("/sesiones", "/sesiones/renovacion")

        // Un 401 de registro o de ingreso es de negocio (p. ej. contraseña errada), no una sesión vencida.
        fun esPublica(request: Request): Boolean {
            val ruta = request.url.encodedPath
            return PUBLICAS.any(ruta::endsWith) || ruta.contains("/registros")
        }

        fun desde(api: RenovacionApi): (String) -> Renovacion = { refresco ->
            try {
                val respuesta = api.renovar(SolicitudRenovacion(refresco)).execute()
                val cuerpo = respuesta.body()
                when {
                    respuesta.isSuccessful && cuerpo != null -> Renovacion.Renovada(cuerpo)
                    respuesta.code() == HTTP_NO_AUTORIZADO -> Renovacion.Rechazada
                    else -> Renovacion.Fallida
                }
            } catch (ignored: SerializationException) {
                Renovacion.Fallida
            } catch (ignored: IOException) {
                Renovacion.Fallida
            }
        }
    }
}
