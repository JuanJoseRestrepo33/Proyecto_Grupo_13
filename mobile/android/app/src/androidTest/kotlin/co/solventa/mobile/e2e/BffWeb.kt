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
 * Lo que ve la web: las rutas del BFF web con la misma cuenta. La UI de la web queda fuera de las
 * pruebas (#162, #172). Los errores dan la ruta y el código, nunca el cuerpo: traería datos de la cuenta.
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

    fun consentimientos(cookie: String): List<JSONObject> {
        val lista = JSONArray(obtener("consentimientos", cookie))
        return List(lista.length(), lista::getJSONObject)
    }

    fun consentimientoVigente(cookie: String, finalidad: String): JSONObject? =
        consentimientos(cookie).firstOrNull {
            it.getString("finalidad") == finalidad && it.getString("estado") == "vigente"
        }

    /** Otorga como lo haría la web: la primera entidad de origen y la versión vigente del texto legal. */
    fun otorgar(cookie: String, finalidad: String) {
        val finalidades = JSONArray(obtener("consentimientos/finalidades", cookie))
        val elegida = List(finalidades.length(), finalidades::getJSONObject)
            .single { it.getString("codigo") == finalidad }
        val entidad = elegida.getJSONArray("entidades_origen").getJSONObject(0).getString("id")
        val cuerpo = JSONObject()
            .put("finalidad", finalidad)
            .put("entidades_origen", JSONArray().put(entidad))
            .put("version_texto", elegida.getString("version_texto"))
        enviar("consentimientos", cookie, cuerpo)
    }

    fun revocar(cookie: String, id: String) {
        enviar("consentimientos/$id/revocacion", cookie, JSONObject())
    }

    /** Cotiza el seguro de auto con Open Finance; la tarifa depende del consentimiento vigente. */
    fun cotizarAuto(cookie: String): JSONObject {
        val cuerpo = JSONObject()
            .put("producto", "auto")
            .put("asegurado", JSONObject().put("edad", EDAD).put("ciudad", "Bogotá"))
            .put("usar_open_finance", true)
        return JSONObject(enviar("cotizaciones", cookie, cuerpo))
    }

    private fun obtener(ruta: String, cookie: String): String {
        val solicitud = Request.Builder().url(raiz + ruta).header("Cookie", cookie).build()
        return cliente.newCall(solicitud).execute().use { respuesta ->
            exigirExito(respuesta, ruta)
            respuesta.body!!.string()
        }
    }

    private fun enviar(ruta: String, cookie: String, cuerpo: JSONObject): String {
        val solicitud = Request.Builder()
            .url(raiz + ruta)
            .header("Cookie", cookie)
            .post(cuerpo.toString().toRequestBody("application/json".toMediaType()))
            .build()
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
        const val EDAD = 34
    }
}
