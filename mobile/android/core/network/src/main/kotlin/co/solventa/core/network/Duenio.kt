package co.solventa.core.network

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/** Cuenta dueña de una solicitud en segundo plano (un borrador de MOB-F05-02). Viaja como etiqueta OkHttp. */
data class DuenioSolicitud(val clienteId: String)

/**
 * La solicitud es de una cuenta que ya no tiene la sesión: no sale. Es una [IOException] porque es lo
 * único que OkHttp deja propagar desde un interceptor sin cerrar la app.
 */
class SolicitudDeOtraCuenta(duenio: String, vigente: String?) :
    IOException("Solicitud de $duenio con la sesión de ${vigente ?: "nadie"}")

/**
 * Compara el dueño de la solicitud con la cuenta de la sesión vigente justo antes de enviarla. Se instala
 * como interceptor de aplicación y de red: el segundo vuelve a revisar el intento que repite el
 * autenticador después de renovar el token. Sin etiqueta, la solicitud pasa igual.
 */
class InterceptorDuenio(private val clienteVigente: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val duenio = chain.request().tag(DuenioSolicitud::class.java)
        if (duenio != null) {
            val vigente = clienteVigente()
            if (vigente != duenio.clienteId) throw SolicitudDeOtraCuenta(duenio.clienteId, vigente)
        }
        return chain.proceed(chain.request())
    }
}
