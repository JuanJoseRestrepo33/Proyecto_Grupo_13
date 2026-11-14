package co.solventa.mobile

import co.solventa.mobile.mock.CargaMock
import co.solventa.mobile.mock.MockBffMovil
import java.net.SocketTimeoutException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

class DebugMockInterceptor(private val mock: MockBffMovil) : Interceptor {
    private val prefijo = java.net.URI(BuildConfig.BASE_URL).rawPath

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val ruta = request.url.encodedPath.removePrefix(prefijo)
        val respuesta = (request.body as? MultipartBody)?.let { formulario ->
            mock.responderCarga(ruta, request.header("Authorization"), cargaDe(formulario))
        } ?: mock.responder(
            metodo = request.method,
            ruta = ruta,
            cuerpo = request.body?.let { body -> Buffer().also(body::writeTo).readUtf8() }.orEmpty(),
            autorizacion = request.header("Authorization"),
            claveIdempotencia = request.header("Idempotency-Key"),
        )
        // Simula que la solicitud llegó pero la respuesta no: la app ve un tiempo agotado.
        if (respuesta.perdida) throw SocketTimeoutException("Mock: respuesta perdida")
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

    private fun cargaDe(formulario: MultipartBody): CargaMock {
        val partes = formulario.parts.associateBy { nombreDe(it, "name").orEmpty() }
        val archivo = partes["archivo"]
        fun texto(campo: String) = partes[campo]?.let { Buffer().also(it.body::writeTo).readUtf8() }
        return CargaMock(
            nombre = archivo?.let { nombreDe(it, "filename") },
            tipo = archivo?.body?.contentType()?.toString(),
            contenido = archivo?.let { Buffer().also(it.body::writeTo).readByteArray() } ?: ByteArray(0),
            tomadaEn = texto("tomada_en"),
            latitud = texto("latitud")?.toDouble(),
            longitud = texto("longitud")?.toDouble(),
            lugar = texto("lugar"),
            idRequerimiento = texto("id_requerimiento"),
        )
    }

    private fun nombreDe(parte: MultipartBody.Part, atributo: String): String? {
        val disposicion = parte.headers?.get("Content-Disposition").orEmpty()
        return Regex("\\b$atributo=\"([^\"]*)\"").find(disposicion)?.groupValues?.get(1)
    }
}
