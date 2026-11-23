package co.solventa.mobile.e2e

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * La operación interna de cambio de estado del núcleo (WEB-F06, #197): solo existe en *staging*, sin
 * interfaz ni ruta en el BFF, y exige `X-Token-Interno`. Los errores dan la ruta y el código, nunca el
 * cuerpo ni el token.
 *
 * @param raiz URL de `solventa-nucleo`, sin `/v1`.
 */
internal class NucleoInterno(raiz: String, private val token: String) {
    private val raiz = raiz.trimEnd('/') + "/v1/"
    private val cliente = OkHttpClient.Builder().readTimeout(ESPERA_SEGUNDOS, TimeUnit.SECONDS).build()

    /** Sin reintentos: un cambio que sí se aplicó respondería 409 al repetirlo y ocultaría el fallo real. */
    fun cambiarEstado(idSiniestro: String, estado: String, requerimientos: List<String> = emptyList()): JSONObject {
        val ruta = "siniestros/$idSiniestro/estado"
        val cuerpo = JSONObject().put("estado", estado)
        if (requerimientos.isNotEmpty()) {
            val lista = JSONArray()
            requerimientos.forEach { lista.put(JSONObject().put("descripcion", it)) }
            cuerpo.put("requerimientos", lista)
        }
        val solicitud = Request.Builder()
            .url(raiz + ruta)
            .header("X-Token-Interno", token)
            .post(cuerpo.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return cliente.newCall(solicitud).execute().use { respuesta ->
            check(respuesta.isSuccessful) { "$ruta ($estado) respondió ${respuesta.code}" }
            JSONObject(respuesta.body!!.string())
        }
    }

    private companion object {
        const val ESPERA_SEGUNDOS = 60L
    }
}
