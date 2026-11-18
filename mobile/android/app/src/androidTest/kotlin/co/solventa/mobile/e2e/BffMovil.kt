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
 * El router móvil del BFF con una sesión propia de la prueba, aparte de la de la app (MOB-F06, #209): lo
 * que la app no deja ver desde fuera, como el registro del dispositivo y la bandeja de notificaciones.
 * Los errores dan la ruta y el código, nunca el cuerpo, el token de sesión ni el `token_push`.
 *
 * @param raiz la URL del router móvil, terminada en `/api/v1/movil/`.
 */
internal class BffMovil(private val raiz: String) {
    private val cliente = OkHttpClient.Builder().readTimeout(ESPERA_SEGUNDOS, TimeUnit.SECONDS).build()
    private var token: String? = null

    /** El `cliente.id` de la sesión: la clave con que la app guarda lo que ya leyó de la bandeja. */
    lateinit var clienteId: String
        private set

    /** `POST /sesiones` con correo y contraseña; guarda el token de acceso solo en memoria. */
    fun iniciarSesion(correo: String, clave: String) {
        val cuerpo = JSONObject().put("correo", correo).put("contrasena", clave)
        val sesion = JSONObject(enviar("sesiones", cuerpo, autenticado = false).cuerpo)
        token = sesion.getString("token_acceso")
        clienteId = sesion.getJSONObject("cliente").getString("id")
    }

    /**
     * `POST /dispositivos` y el código de la respuesta: 201 si el token es nuevo, 200 si ya estaba
     * registrado (y queda con esta cuenta). Es idempotente por token.
     */
    fun registrarDispositivo(tokenPush: String): Int =
        enviar("dispositivos", JSONObject().put("token_push", tokenPush).put("plataforma", "android")).codigo

    /** `GET /notificaciones`, de la más reciente a la más antigua. */
    fun notificaciones(): List<JSONObject> {
        val solicitud = Request.Builder().url(raiz + "notificaciones?limite=$LIMITE").autorizada().build()
        val lista = cliente.newCall(solicitud).execute().use { respuesta ->
            exigirExito(respuesta, "notificaciones")
            JSONArray(respuesta.body!!.string())
        }
        return List(lista.length(), lista::getJSONObject)
    }

    /** La notificación de la bandeja cuyo `datos.id_siniestro` es [idSiniestro], o null si no hay. */
    fun notificacionDe(idSiniestro: String): JSONObject? = notificaciones().firstOrNull {
        it.optJSONObject("datos")?.optString("id_siniestro") == idSiniestro
    }

    private fun enviar(ruta: String, cuerpo: JSONObject, autenticado: Boolean = true): Respuesta {
        val solicitud = Request.Builder()
            .url(raiz + ruta)
            .post(cuerpo.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (autenticado) autorizada() }
            .build()
        return cliente.newCall(solicitud).execute().use { respuesta ->
            exigirExito(respuesta, ruta)
            Respuesta(respuesta.code, respuesta.body!!.string())
        }
    }

    private fun Request.Builder.autorizada(): Request.Builder =
        header("Authorization", "Bearer ${checkNotNull(token) { "BffMovil sin sesión" }}")

    private fun exigirExito(respuesta: Response, ruta: String) {
        check(respuesta.isSuccessful) { "$ruta respondió ${respuesta.code}" }
    }

    private class Respuesta(val codigo: Int, val cuerpo: String)

    private companion object {
        // El primer pedido a Cloud Run tras un rato sin tráfico arranca una instancia.
        const val ESPERA_SEGUNDOS = 60L

        /** El máximo del contrato: la cuenta de prueba acumula notificaciones entre corridas. */
        const val LIMITE = 100
    }
}
