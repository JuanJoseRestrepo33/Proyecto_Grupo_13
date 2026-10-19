package co.solventa.mobile

import co.solventa.mobile.mock.MockBffMovil
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

class DebugMockInterceptor(private val mock: MockBffMovil) : Interceptor {
    private val prefijo = java.net.URI(BuildConfig.BASE_URL).rawPath

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cuerpo = request.body?.let { body -> Buffer().also(body::writeTo).readUtf8() }.orEmpty()
        val respuesta = mock.responder(
            metodo = request.method,
            ruta = request.url.encodedPath.removePrefix(prefijo),
            cuerpo = cuerpo,
            autorizacion = request.header("Authorization"),
        )
        // Nunca se llama chain.proceed: debug no sale a la red, ni ante rutas desconocidas.
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(respuesta.codigo)
            .message("Mock")
            .apply { respuesta.encabezados.forEach { (nombre, valor) -> header(nombre, valor) } }
            .body(respuesta.cuerpo.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
