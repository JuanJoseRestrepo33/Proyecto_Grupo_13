package co.solventa.feature.notificaciones

import co.solventa.core.network.BffApi
import co.solventa.core.network.Cliente
import co.solventa.core.network.Consentimiento
import co.solventa.core.network.Credenciales
import co.solventa.core.network.Dispositivo
import co.solventa.core.network.DuenioSolicitud
import co.solventa.core.network.Evidencia
import co.solventa.core.network.FinalidadConsentimiento
import co.solventa.core.network.Notificacion
import co.solventa.core.network.Otp
import co.solventa.core.network.PolizaDetalle
import co.solventa.core.network.PolizaResumen
import co.solventa.core.network.Registro
import co.solventa.core.network.Sesion
import co.solventa.core.network.Siniestro
import co.solventa.core.network.SiniestroDetalle
import co.solventa.core.network.SolicitudConsentimiento
import co.solventa.core.network.SolicitudDispositivo
import co.solventa.core.network.SolicitudOtp
import co.solventa.core.network.SolicitudRegistro
import co.solventa.core.network.SolicitudRenovacion
import co.solventa.core.network.SolicitudSiniestro
import co.solventa.core.network.VerificacionIdentidad
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody

/** Solo las rutas del push están programadas; las demás fallan si alguien las usa. */
@Suppress("TooManyFunctions")
class BffFalso : BffApi {
    var registro: suspend (SolicitudDispositivo) -> Dispositivo = {
        Dispositivo("DIS-1", "android", "2026-11-15T10:00:00Z")
    }
    var bandeja: suspend () -> List<Notificacion> = { emptyList() }
    val llamadas = mutableListOf<String>()

    override suspend fun registrarDispositivo(solicitud: SolicitudDispositivo): Dispositivo {
        llamadas += "registrar:${solicitud.tokenPush}"
        return registro(solicitud)
    }

    override suspend fun darDeBajaDispositivo(idDispositivo: String, autorizacion: String?) {
        llamadas += "baja:$idDispositivo:$autorizacion"
    }

    override suspend fun listarNotificaciones(): List<Notificacion> {
        llamadas += "bandeja"
        return bandeja()
    }

    override suspend fun marcarNotificacionLeida(idNotificacion: String) {
        llamadas += "leida:$idNotificacion"
    }

    override suspend fun crearRegistro(solicitud: SolicitudRegistro): Registro = noSeUsa()
    override suspend fun verificarOtp(idRegistro: String, solicitud: SolicitudOtp): Sesion = noSeUsa()
    override suspend fun reenviarOtp(idRegistro: String): Otp = noSeUsa()
    override suspend fun iniciarSesion(credenciales: Credenciales): Sesion = noSeUsa()
    override suspend fun renovarSesion(solicitud: SolicitudRenovacion): Sesion = noSeUsa()
    override suspend fun obtenerSesion(): Cliente = noSeUsa()
    override suspend fun obtenerVerificacionIdentidad(): VerificacionIdentidad = noSeUsa()
    override suspend fun solicitarVerificacionIdentidad(): VerificacionIdentidad = noSeUsa()
    override suspend fun listarFinalidades(): List<FinalidadConsentimiento> = noSeUsa()
    override suspend fun listarConsentimientos(): List<Consentimiento> = noSeUsa()
    override suspend fun otorgarConsentimiento(solicitud: SolicitudConsentimiento): Consentimiento = noSeUsa()
    override suspend fun revocarConsentimiento(idConsentimiento: String): Consentimiento = noSeUsa()
    override suspend fun renovarConsentimiento(idConsentimiento: String): Consentimiento = noSeUsa()
    override suspend fun listarPolizas(): List<PolizaResumen> = noSeUsa()
    override suspend fun obtenerPoliza(numero: String): PolizaDetalle = noSeUsa()
    override suspend fun descargarDocumentoPoliza(numero: String): ResponseBody = noSeUsa()
    override suspend fun reportarSiniestro(
        claveIdempotencia: String,
        solicitud: SolicitudSiniestro,
        duenio: DuenioSolicitud?,
    ): Siniestro = noSeUsa()
    override suspend fun listarSiniestros(duenio: DuenioSolicitud?): List<Siniestro> = noSeUsa()
    override suspend fun obtenerSiniestro(idSiniestro: String): SiniestroDetalle = noSeUsa()

    @Suppress("LongParameterList")
    override suspend fun adjuntarEvidencia(
        idSiniestro: String,
        archivo: MultipartBody.Part,
        tomadaEn: RequestBody?,
        latitud: RequestBody?,
        longitud: RequestBody?,
        lugar: RequestBody?,
        idRequerimiento: RequestBody?,
        duenio: DuenioSolicitud?,
    ): Evidencia = noSeUsa()
    override suspend fun listarEvidencias(idSiniestro: String, duenio: DuenioSolicitud?): List<Evidencia> = noSeUsa()

    private fun noSeUsa(): Nothing = error("no se usa en notificaciones")
}

/** Sesión con [clienteId] y [tokenAcceso] que la prueba cambia a mano. */
class SesionFalsa(override var clienteId: String?, override var tokenAcceso: String?) :
    co.solventa.core.network.SesionBff {
    override val tokenRefresco: String? = null

    override fun alRenovar(nueva: Sesion) = Unit

    override fun alVencer(tokenRechazado: Boolean) = Unit
}

/** Sin red: [co.solventa.core.network.llamarBff] lo traduce a SinConexion. */
fun sinRed(): Nothing = throw IOException("sin red")

class AlmacenEnMemoria(private val token: String = "instalacion-1") : AlmacenPush {
    val vistas = mutableMapOf<String, Set<String>>()
    var pedido = false

    override fun tokenInstalacion() = token

    override fun vistas(cliente: String) = vistas[cliente]

    override fun guardarVistas(cliente: String, ids: Set<String>) {
        vistas[cliente] = ids
    }

    override fun permisoPedido() = pedido

    override fun marcarPermisoPedido() {
        pedido = true
    }
}

/** Registra lo que se mostró; [permitido] false simula el permiso negado. */
class NotificadorFalso(var permitido: Boolean = true) : Notificador {
    val intentos = mutableListOf<String>()
    val mostradas = mutableListOf<String>()

    override fun mostrar(notificacion: Notificacion): Boolean {
        intentos += notificacion.id
        if (permitido) mostradas += notificacion.id
        return permitido
    }
}

fun notificacion(id: String, leida: Boolean = false, siniestro: String? = "SIN-$id") = Notificacion(
    id = id,
    titulo = "Tu siniestro cambió",
    cuerpo = "Ya estamos evaluando tu caso.",
    datos = JsonObject(
        buildMap {
            put("tipo", JsonPrimitive(Notificacion.TIPO_SINIESTRO_ESTADO))
            siniestro?.let { put("id_siniestro", JsonPrimitive(it)) }
        },
    ),
    creadaEn = "2026-11-15T10:00:00Z",
    leida = leida,
)
