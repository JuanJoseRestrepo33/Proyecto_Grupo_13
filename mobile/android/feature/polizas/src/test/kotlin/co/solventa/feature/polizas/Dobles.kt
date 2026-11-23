package co.solventa.feature.polizas

import co.solventa.core.network.BffApi
import co.solventa.core.network.Cliente
import co.solventa.core.network.Cobertura
import co.solventa.core.network.Consentimiento
import co.solventa.core.network.Credenciales
import co.solventa.core.network.Dinero
import co.solventa.core.network.Dispositivo
import co.solventa.core.network.DuenioSolicitud
import co.solventa.core.network.ErrorBff
import co.solventa.core.network.EstadoPoliza
import co.solventa.core.network.Evidencia
import co.solventa.core.network.FinalidadConsentimiento
import co.solventa.core.network.NetworkClient
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
import co.solventa.core.almacen.ArchivoCifrado
import co.solventa.core.almacen.CifradoAesGcm
import co.solventa.core.almacen.FuenteLlave
import co.solventa.core.almacen.LlaveInexistente
import co.solventa.core.almacen.MonitorConectividad
import co.solventa.core.almacen.PuertoCifrado
import co.solventa.feature.polizas.billetera.AlmacenBilletera
import co.solventa.feature.polizas.billetera.BilleteraGuardada
import co.solventa.feature.polizas.billetera.CuentaVigente
import co.solventa.feature.polizas.billetera.SincronizadorBilletera
import co.solventa.feature.polizas.detalle.DocumentosPoliza
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ReglaMain : TestWatcher() {
    val dispatcher = UnconfinedTestDispatcher()

    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

fun fallaBff(http: Int, codigo: String? = null): HttpException {
    val cuerpo = codigo?.let { NetworkClient.json.encodeToString(ErrorBff.serializer(), ErrorBff(it, "prueba")) }
        ?: "<html>Bad gateway</html>"
    val crudo = okhttp3.Response.Builder()
        .code(http)
        .message("Prueba")
        .protocol(Protocol.HTTP_1_1)
        .request(Request.Builder().url("https://bff.prueba/api/v1/movil/").build())
        .build()
    return HttpException(Response.error<Any>(cuerpo.toResponseBody("application/json".toMediaType()), crudo))
}

// ── Datos de prueba ────────────────────────────────────────────────────────────────────────────

const val NUMERO = "POL-2026-001101"

val VIGENTE = PolizaResumen(
    numero = NUMERO,
    producto = "auto",
    inicio = "2026-10-15",
    fin = "2027-10-14",
    estado = EstadoPoliza.VIGENTE,
    nombreProducto = "Seguro de Auto — Plan Full",
    primaMensual = Dinero.pesos(185_000),
    documentoUrl = "/api/v1/movil/polizas/$NUMERO/documento",
)

/** Como las de la semilla de staging: sin nombre comercial, prima ni documento. */
val VENCIDA = PolizaResumen("POL-2026-001102", "hogar", "2025-01-01", "2025-12-31", EstadoPoliza.VENCIDA)

val POR_INICIAR = PolizaResumen("POL-2026-001103", "viaje", "2026-11-25", "2026-12-25", EstadoPoliza.POR_INICIAR)

val COMPLETA: PolizaDetalle = PolizaDetalle.de(
    VIGENTE,
    coberturas = listOf(
        Cobertura("Responsabilidad civil", "$1.000.000.000"),
        Cobertura("Pérdida total", "100 %"),
        Cobertura("Asistencia en viaje", "Incluida"),
    ),
    deducible = "5% del valor asegurado",
    valorAsegurado = "$60.000.000",
    emitidaEn = "2026-10-15T17:00:00Z",
)

val SIN_DATOS: PolizaDetalle = PolizaDetalle.de(VENCIDA, emptyList(), null, null, null)

/** Guarda el PDF en memoria y devuelve una ruta inventada. */
class DocumentosFalsos : DocumentosPoliza {
    val guardados = mutableMapOf<String, ByteArray>()
    var fallo: Exception? = null

    override fun guardar(numero: String, contenido: InputStream): String {
        fallo?.let { throw it }
        guardados[numero] = contenido.readBytes()
        return "/cache/polizas/$numero.pdf"
    }
}

// ── Billetera (MOB-F03) ───────────────────────────────────────────────────────────────────────

const val CLIENTE = "CLI-2026-000001"
const val OTRO_CLIENTE = "CLI-2026-000002"
val ZONA: ZoneId = ZoneId.of("America/Bogota")

/** 18 nov 2026, 08:14 en Bogotá. */
val SINCRONIZADA: Instant = Instant.parse("2026-11-18T13:14:00Z")

/** Reloj de la prueba en Bogotá; [instante] se mueve para simular el paso del tiempo. */
class RelojMovil(var instante: Instant = SINCRONIZADA) : Clock() {
    override fun getZone(): ZoneId = ZONA

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = instante
}

/** La billetera que deja una sincronización completa con las pólizas de [BffFalso]. */
fun billeteraDePrueba(
    cliente: String = CLIENTE,
    polizas: List<PolizaResumen> = listOf(VIGENTE, VENCIDA, POR_INICIAR),
    detalles: List<PolizaDetalle> = listOf(COMPLETA, PolizaDetalle.de(POR_INICIAR, emptyList(), null, null, null)),
    momento: Instant = SINCRONIZADA,
) = BilleteraGuardada(cliente, "Ana María Rojas", polizas, detalles, momento.toString())

/** AES-256 de la JCA en lugar del Keystore. */
class LlaveJca : FuenteLlave {
    private val llave: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    var borrada = false

    override fun paraCifrar(): SecretKey = llave

    override fun paraDescifrar(): SecretKey = if (borrada) throw LlaveInexistente() else llave
}

class MonitorFalso(inicial: Boolean = true) : MonitorConectividad {
    override val conectado = MutableStateFlow(inicial)
}

class CuentaFalsa(
    override var clienteId: String? = CLIENTE,
    override var nombreCompleto: String? = "Ana María Rojas",
) : CuentaVigente

/** La billetera real sobre una carpeta temporal, con la llave de la JCA y todo en el hilo de la prueba. */
class BilleteraDePrueba(carpeta: File, alcance: CoroutineScope, val reloj: RelojMovil = RelojMovil()) {
    val api = BffFalso()
    val monitor = MonitorFalso()
    val cuenta = CuentaFalsa()
    val llave = LlaveJca()
    val archivo = File(File(carpeta, "billetera"), "billetera.bin")

    /** Corre dentro del candado del almacén, mientras se escribe o se lee el archivo. */
    var alCifrar: () -> Unit = {}
    var alDescifrar: () -> Unit = {}
    private val cifrado = CifradoAesGcm(llave)
    private val conGanchos = object : PuertoCifrado {
        override fun cifrar(destino: OutputStream): OutputStream = cifrado.cifrar(destino).also { alCifrar() }

        override fun descifrar(origen: InputStream): InputStream = cifrado.descifrar(origen).also { alDescifrar() }
    }
    val almacen = AlmacenBilletera(ArchivoCifrado(archivo, conGanchos), Dispatchers.Unconfined)
    val sincronizador = SincronizadorBilletera(api, almacen, monitor, cuenta, alcance, reloj)

    /** Deja guardada [billetera] como si hubiera sincronizado. */
    suspend fun guardar(billetera: BilleteraGuardada = billeteraDePrueba()) {
        check(almacen.guardarSi(billetera) { true })
    }
}

// ── BFF doble ─────────────────────────────────────────────────────────────────────────────────

class BffFalso : BffApi {
    var polizas: suspend () -> List<PolizaResumen> = { listOf(VIGENTE, VENCIDA, POR_INICIAR) }
    var consultasPolizas = 0
        private set

    override suspend fun listarPolizas(): List<PolizaResumen> {
        consultasPolizas += 1
        return polizas()
    }

    /** El de la póliza pedida; COMPLETA para las que no son de la lista de prueba. */
    var detalle: suspend (String) -> PolizaDetalle = { numero ->
        when (numero) {
            POR_INICIAR.numero -> PolizaDetalle.de(POR_INICIAR, emptyList(), null, null, null)
            VENCIDA.numero -> SIN_DATOS
            else -> COMPLETA
        }
    }
    var consultasDetalle = 0
        private set

    override suspend fun obtenerPoliza(numero: String): PolizaDetalle {
        consultasDetalle += 1
        return detalle(numero)
    }

    var documento: suspend (String) -> ResponseBody = {
        "%PDF-1.4 prueba".toResponseBody("application/pdf".toMediaType())
    }
    val descargas = mutableListOf<String>()

    override suspend fun descargarDocumentoPoliza(numero: String): ResponseBody {
        descargas += numero
        return documento(numero)
    }

    override suspend fun crearRegistro(solicitud: SolicitudRegistro): Registro = error(NO_SE_USA)

    override suspend fun verificarOtp(idRegistro: String, solicitud: SolicitudOtp): Sesion = error(NO_SE_USA)

    override suspend fun reenviarOtp(idRegistro: String): Otp = error(NO_SE_USA)

    override suspend fun iniciarSesion(credenciales: Credenciales): Sesion = error(NO_SE_USA)

    override suspend fun renovarSesion(solicitud: SolicitudRenovacion): Sesion = error(NO_SE_USA)

    override suspend fun obtenerSesion(): Cliente = error(NO_SE_USA)

    override suspend fun obtenerVerificacionIdentidad(): VerificacionIdentidad = error(NO_SE_USA)

    override suspend fun solicitarVerificacionIdentidad(): VerificacionIdentidad = error(NO_SE_USA)

    override suspend fun listarFinalidades(): List<FinalidadConsentimiento> = error(NO_SE_USA)

    override suspend fun listarConsentimientos(): List<Consentimiento> = error(NO_SE_USA)

    override suspend fun otorgarConsentimiento(solicitud: SolicitudConsentimiento): Consentimiento =
        error(NO_SE_USA)

    override suspend fun revocarConsentimiento(idConsentimiento: String): Consentimiento = error(NO_SE_USA)

    override suspend fun renovarConsentimiento(idConsentimiento: String): Consentimiento = error(NO_SE_USA)

    override suspend fun reportarSiniestro(
        claveIdempotencia: String,
        solicitud: SolicitudSiniestro,
        duenio: DuenioSolicitud?,
    ): Siniestro = error(NO_SE_USA)

    override suspend fun listarSiniestros(duenio: DuenioSolicitud?): List<Siniestro> = error(NO_SE_USA)

    override suspend fun obtenerSiniestro(idSiniestro: String): SiniestroDetalle = error(NO_SE_USA)

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
    ): Evidencia = error(NO_SE_USA)

    override suspend fun listarEvidencias(idSiniestro: String, duenio: DuenioSolicitud?): List<Evidencia> =
        error(NO_SE_USA)

    // Push (MOB-F06): lo prueba feature/notificaciones.
    override suspend fun registrarDispositivo(solicitud: SolicitudDispositivo): Dispositivo = error(NO_SE_USA)

    override suspend fun darDeBajaDispositivo(idDispositivo: String, autorizacion: String?) = error(NO_SE_USA)

    override suspend fun listarNotificaciones(): List<Notificacion> = error(NO_SE_USA)

    override suspend fun marcarNotificacionLeida(idNotificacion: String) = error(NO_SE_USA)

    private companion object {
        const val NO_SE_USA = "no se usa en pólizas"
    }
}
