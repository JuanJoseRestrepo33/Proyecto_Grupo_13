package co.solventa.core.network

import java.io.IOException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/** Resultado de una operación del BFF, ya traducido a lo que la interfaz necesita decidir. */
sealed interface Resultado<out T> {
    data class Exito<T>(val valor: T) : Resultado<T>

    /**
     * El BFF respondió con error. [codigo] es el código estable del contrato (nunca se muestra
     * el `mensaje` tal cual); [reintentarEnSegundos] viene del encabezado Retry-After.
     */
    data class Falla(
        val http: Int,
        val codigo: String?,
        val error: ErrorBff? = null,
        val reintentarEnSegundos: Long? = null,
    ) : Resultado<Nothing>

    data object SinConexion : Resultado<Nothing>
}

/** Estado de una [Resultado.Falla] sin respuesta HTTP utilizable: un cuerpo que no cumple el contrato. */
const val HTTP_RESPUESTA_ILEGIBLE = 0

suspend fun <T> llamarBff(llamada: suspend () -> T): Resultado<T> = try {
    Resultado.Exito(llamada())
} catch (http: HttpException) {
    val error = ErrorBff.desde(http)
    Resultado.Falla(
        http = http.code(),
        codigo = error?.codigo,
        error = error,
        reintentarEnSegundos = http.response()?.headers()?.get("Retry-After")?.toLongOrNull(),
    )
} catch (ignored: SerializationException) {
    // Un cuerpo que no cumple el contrato (campo faltante, enum desconocido): error genérico, sin cerrar la app.
    Resultado.Falla(http = HTTP_RESPUESTA_ILEGIBLE, codigo = null)
} catch (ignored: IOException) {
    Resultado.SinConexion
}
