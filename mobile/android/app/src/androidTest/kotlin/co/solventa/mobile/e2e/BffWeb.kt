package co.solventa.mobile.e2e

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Lo que ve la web de un siniestro: las rutas del BFF web con la misma cuenta. La UI de la web queda
 * fuera de la prueba (#172). Los errores dan la ruta y el código, nunca el cuerpo: traería datos de la cuenta.
 */
internal class BffWeb(private val raiz: String) {
    private val cliente = OkHttpClient.Builder().readTimeout(ESPERA_SEGUNDOS, TimeUnit.SECONDS).build()

    /** Inicia sesión y devuelve la cookie `sesion`, que aquí se envía a mano: no hay navegador. */
    fun iniciarSesion(correo: String, clave: String): String {
        val cuerpo = JSONObject().put("correo", correo).put("contrasena", clave).toString()
        val solicitud = Request.Builder()
            .url(raiz + "sesion")
            .post(cuerpo.toRequestBody("application/json".toMediaType()))
            .build()
        return cliente.newCall(solicitud).execute().use { respuesta ->
            exigirExito(respuesta, "sesion")
            val cookie = respuesta.headers("Set-Cookie").firstOrNull { it.startsWith("sesion=") }
            checkNotNull(cookie) { "sesion no devolvió la cookie" }.substringBefore(';')
        }
    }

    fun siniestros(cookie: String): List<JSONObject> {
        val lista = JSONArray(obtener("siniestros", cookie))
        return List(lista.length(), lista::getJSONObject)
    }

    fun siniestro(cookie: String, id: String): JSONObject = JSONObject(obtener("siniestros/$id", cookie))

    private fun obtener(ruta: String, cookie: String): String {
        val solicitud = Request.Builder().url(raiz + ruta).header("Cookie", cookie).build()
        return cliente.newCall(solicitud).execute().use { respuesta ->
            exigirExito(respuesta, ruta)
            respuesta.body!!.string()
        }
    }

    private fun exigirExito(respuesta: Response, ruta: String) =
        check(respuesta.isSuccessful) { "$ruta respondió ${respuesta.code}" }

    private companion object {
        // El primer pedido a Cloud Run tras un rato sin tráfico arranca una instancia.
        const val ESPERA_SEGUNDOS = 60L
    }
}
