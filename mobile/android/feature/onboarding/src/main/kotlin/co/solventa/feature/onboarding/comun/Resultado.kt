package co.solventa.feature.onboarding.comun

import co.solventa.core.network.ErrorBff
import java.io.IOException
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
} catch (ignored: IOException) {
    Resultado.SinConexion
}
