package co.solventa.mobile.mock

import co.solventa.core.network.Dispositivo
import co.solventa.core.network.ErrorCampo
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.Notificacion
import co.solventa.core.network.SolicitudDispositivo
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val LARGO_TOKEN = 4096

/**
 * Tras registrar el dispositivo de la cuenta demo, su siniestro [SINIESTRO_EN_EVALUACION] "cambia" de
 * estado pasado este tiempo: la primera lectura de la bandeja la encuentra vacía y la siguiente trae el
 * push, que la app muestra (MOB-F06).
 */
val ESPERA_PUSH_DEMO: Duration = Duration.ofSeconds(20)

/** Id de la notificación de demostración de la cuenta demo. */
const val NOTIFICACION_DEMO = "NOT-demo-en-evaluacion"

private class DispositivoMock(val id: String, var correo: String, val registradoEn: Instant)

/**
 * Push con proveedor simulado (MOB-F06): `POST /dispositivos` idempotente por token, `DELETE
 * /dispositivos/{id}`, la bandeja `GET /notificaciones` y `POST /notificaciones/{id}/lectura`.
 */
@Suppress("ReturnCount")
internal class MockNotificaciones(private val estado: EstadoMock) {
    private val dispositivos = mutableMapOf<String, DispositivoMock>()
    private val leidas = mutableSetOf<Pair<String, String>>()

    fun registrar(autorizacion: String?, cuerpo: String): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        val token = NetworkClient.json.decodeFromString(SolicitudDispositivo.serializer(), cuerpo).tokenPush
        if (token.isBlank() || token.any(Char::isWhitespace) || token.length > LARGO_TOKEN) {
            return fallo(422, "token_push_invalido", listOf(ErrorCampo("token_push", "formato_invalido")))
        }
        val existente = dispositivos[token]
        // Un token ya registrado queda con esta cuenta (criterio 4).
        existente?.correo = cuenta.correo
        val dispositivo = existente
            ?: DispositivoMock(estado.siguiente("DIS"), cuenta.correo, estado.reloj()).also { dispositivos[token] = it }
        return respuesta(if (existente == null) 201 else 200, dispositivo.aContrato())
    }

    fun darDeBaja(autorizacion: String?, id: String): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        val token = dispositivos.entries.firstOrNull { it.value.id == id && it.value.correo == cuenta.correo }?.key
            ?: return fallo(404, "dispositivo_no_encontrado")
        dispositivos.remove(token)
        return RespuestaMock(204, "")
    }

    fun listar(autorizacion: String?): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        return respuesta(200, bandeja(cuenta.correo))
    }

    fun marcarLeida(autorizacion: String?, id: String): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        if (bandeja(cuenta.correo).none { it.id == id }) return fallo(404, "notificacion_no_encontrada")
        leidas += cuenta.correo to id
        return RespuestaMock(204, "")
    }

    private fun bandeja(correo: String): List<Notificacion> {
        if (correo != CORREO_DEMO) return emptyList()
        val desde = dispositivos.values.filter { it.correo == correo }.minOfOrNull { it.registradoEn }
            ?: return emptyList()
        val creada = desde + ESPERA_PUSH_DEMO
        if (estado.reloj() < creada) return emptyList()
        return listOf(
            Notificacion(
                id = NOTIFICACION_DEMO,
                titulo = "Tu siniestro SIN-2026-000902 cambió",
                cuerpo = "Ya estamos evaluando tu caso.",
                datos = JsonObject(
                    mapOf(
                        "tipo" to JsonPrimitive(Notificacion.TIPO_SINIESTRO_ESTADO),
                        "id_siniestro" to JsonPrimitive(SINIESTRO_EN_EVALUACION),
                        "referencia" to JsonPrimitive("SIN-2026-000902"),
                        "estado" to JsonPrimitive("en_evaluacion"),
                    ),
                ),
                creadaEn = creada.toString(),
                leida = (correo to NOTIFICACION_DEMO) in leidas,
            ),
        )
    }

    private fun DispositivoMock.aContrato() = Dispositivo(id, "android", registradoEn.toString())
}
