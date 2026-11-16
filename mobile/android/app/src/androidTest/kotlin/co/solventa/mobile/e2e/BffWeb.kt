package co.solventa.mobile.e2e

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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

    fun evidencias(cookie: String, idSiniestro: String): List<JSONObject> {
        val lista = JSONArray(obtener("siniestros/$idSiniestro/evidencias", cookie))
        return List(lista.length(), lista::getJSONObject)
    }

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

    /**
     * Cotiza el seguro de auto con Open Finance; la tarifa depende del consentimiento vigente. El núcleo
     * reutiliza la cotización vigente de una solicitud idéntica: quien vaya a comprar usa una [ciudad] única.
     */
    fun cotizarAuto(cookie: String, ciudad: String = "Bogotá"): JSONObject {
        val cuerpo = JSONObject()
            .put("producto", "auto")
            .put("asegurado", JSONObject().put("edad", EDAD).put("ciudad", ciudad))
            .put("usar_open_finance", true)
        return JSONObject(enviar("cotizaciones", cookie, cuerpo))
    }

    /** Acepta la cotización (WEB-F03) y devuelve la suscripción. */
    fun suscribir(cookie: String, cotizacionId: String): JSONObject =
        JSONObject(enviar("suscripciones", cookie, JSONObject().put("cotizacion_id", cotizacionId)))

    /**
     * Paga con tarjeta de crédito, que la pasarela simulada aprueba. [clave] es la `Idempotency-Key` de
     * esta compra: un reintento la reenvía igual y el núcleo no cobra dos veces (P18).
     */
    fun pagar(cookie: String, suscripcionId: String, clave: String): JSONObject {
        val ruta = "suscripciones/$suscripcionId/pagos"
        val cuerpo = JSONObject().put("medio", "tarjeta_credito")
        var ultimo: Exception? = null
        repeat(INTENTOS_PAGO) {
            try {
                return JSONObject(enviar(ruta, cookie, cuerpo, mapOf("Idempotency-Key" to clave)))
            } catch (error: IOException) {
                ultimo = error
            } catch (error: FalloBff) {
                // 409 pago_en_curso o un 5xx: el mismo intento, con la misma clave.
                if (error.codigo != HTTP_CONFLICTO && error.codigo < HTTP_ERROR_SERVIDOR) throw error
                ultimo = error
            }
            Thread.sleep(PAUSA_PAGO_MS)
        }
        throw checkNotNull(ultimo)
    }

    /** Firma con las condiciones aceptadas y devuelve la suscripción `emitida`, con su `poliza`. */
    fun firmar(cookie: String, suscripcionId: String): JSONObject = JSONObject(
        enviar("suscripciones/$suscripcionId/firma", cookie, JSONObject().put("acepta_condiciones", true)),
    )

    fun polizas(cookie: String): List<JSONObject> {
        val lista = JSONArray(obtener("polizas", cookie))
        return List(lista.length(), lista::getJSONObject)
    }

    fun poliza(cookie: String, numero: String): JSONObject = JSONObject(obtener("polizas/$numero", cookie))

    /** El PDF que descarga la web para esa póliza. */
    fun documento(cookie: String, numero: String): ByteArray {
        val ruta = "polizas/$numero/documento"
        val solicitud = Request.Builder().url(raiz + ruta).header("Cookie", cookie).build()
        return cliente.newCall(solicitud).execute().use { respuesta ->
            exigirExito(respuesta, ruta)
            respuesta.body!!.bytes()
        }
    }

    private fun obtener(ruta: String, cookie: String): String {
        val solicitud = Request.Builder().url(raiz + ruta).header("Cookie", cookie).build()
        return cliente.newCall(solicitud).execute().use { respuesta ->
            exigirExito(respuesta, ruta)
            respuesta.body!!.string()
        }
    }

    private fun enviar(
        ruta: String,
        cookie: String,
        cuerpo: JSONObject,
        encabezados: Map<String, String> = emptyMap(),
    ): String {
        val solicitud = Request.Builder()
            .url(raiz + ruta)
            .header("Cookie", cookie)
            .apply { encabezados.forEach { (nombre, valor) -> header(nombre, valor) } }
            .post(cuerpo.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return cliente.newCall(solicitud).execute().use { respuesta ->
            exigirExito(respuesta, ruta)
            respuesta.body!!.string()
        }
    }

    private fun exigirExito(respuesta: Response, ruta: String) {
        if (!respuesta.isSuccessful) throw FalloBff(ruta, respuesta.code)
    }

    /** Solo la ruta y el código: el cuerpo traería datos de la cuenta. */
    class FalloBff(ruta: String, val codigo: Int) : IllegalStateException("$ruta respondió $codigo")

    private companion object {
        // El primer pedido a Cloud Run tras un rato sin tráfico arranca una instancia.
        const val ESPERA_SEGUNDOS = 60L
        const val EDAD = 34
        const val INTENTOS_PAGO = 3
        const val PAUSA_PAGO_MS = 2_000L
        const val HTTP_CONFLICTO = 409
        const val HTTP_ERROR_SERVIDOR = 500
    }
}
