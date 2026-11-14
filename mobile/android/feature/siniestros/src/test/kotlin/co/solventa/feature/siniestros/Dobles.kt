package co.solventa.feature.siniestros

import co.solventa.core.network.BffApi
import co.solventa.core.network.Cliente
import co.solventa.core.network.Consentimiento
import co.solventa.core.network.Credenciales
import co.solventa.core.network.ErrorBff
import co.solventa.core.network.ErrorCampo
import co.solventa.core.network.EstadoPoliza
import co.solventa.core.network.EstadoSiniestro
import co.solventa.core.network.DuenioSolicitud
import co.solventa.core.network.Evidencia
import co.solventa.core.network.SolicitudDeOtraCuenta
import co.solventa.core.network.FinalidadConsentimiento
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.Otp
import co.solventa.core.network.PolizaResumen
import co.solventa.core.network.Registro
import co.solventa.core.network.Sesion
import co.solventa.core.network.SiguientePaso
import co.solventa.core.network.Siniestro
import co.solventa.core.network.SiniestroDetalle
import co.solventa.core.network.SolicitudConsentimiento
import co.solventa.core.network.SolicitudOtp
import co.solventa.core.network.SolicitudRegistro
import co.solventa.core.network.SolicitudRenovacion
import co.solventa.core.network.SolicitudSiniestro
import co.solventa.core.network.VerificacionIdentidad
import co.solventa.feature.siniestros.evidencia.AlmacenEvidencias
import co.solventa.feature.siniestros.evidencia.FormatoEvidencia
import co.solventa.feature.siniestros.evidencia.Preparado
import co.solventa.feature.siniestros.borrador.AlmacenBorradoresCifrado
import co.solventa.feature.siniestros.borrador.CifradoAesGcm
import co.solventa.feature.siniestros.borrador.ESPERAS_REINTENTO
import co.solventa.feature.siniestros.borrador.EnviadorBorradores
import co.solventa.feature.siniestros.borrador.FuenteLlave
import co.solventa.feature.siniestros.borrador.LlaveInexistente
import co.solventa.feature.siniestros.borrador.MonitorConectividad
import co.solventa.feature.siniestros.borrador.PuertoCifrado
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
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
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ReglaMain : TestWatcher() {
    /** Para `runTest(main.dispatcher)`: el reloj virtual es el mismo que usan las esperas del enviador. */
    val dispatcher = UnconfinedTestDispatcher()

    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

fun fallaBff(http: Int, error: ErrorBff): HttpException {
    val cuerpo = NetworkClient.json.encodeToString(ErrorBff.serializer(), error)
    val crudo = okhttp3.Response.Builder()
        .code(http)
        .message("Prueba")
        .protocol(Protocol.HTTP_1_1)
        .request(Request.Builder().url("https://bff.prueba/api/v1/movil/").build())
        .build()
    return HttpException(Response.error<Any>(cuerpo.toResponseBody("application/json".toMediaType()), crudo))
}

fun fallaBff(http: Int, codigo: String, campos: List<ErrorCampo> = emptyList()) =
    fallaBff(http, ErrorBff(codigo, "prueba", campos))

// ── Datos de prueba ────────────────────────────────────────────────────────────────────────────

val HOY: LocalDate = LocalDate.parse("2026-10-26")

val POLIZA_VIGENTE = PolizaResumen("POL-2026-000101", "auto", "2026-09-26", "2027-09-26", EstadoPoliza.VIGENTE)
val POLIZA_VENCIDA = PolizaResumen("POL-2026-000102", "hogar", "2025-09-26", "2026-09-26", EstadoPoliza.VENCIDA)

fun siniestroDe(solicitud: SolicitudSiniestro, referencia: String = "SIN-2026-000123") = Siniestro(
    id = "SIN-1",
    referencia = referencia,
    numeroPoliza = solicitud.numeroPoliza,
    tipoEvento = solicitud.tipoEvento,
    ocurridoEl = solicitud.ocurridoEl,
    descripcion = solicitud.descripcion,
    montoEstimado = solicitud.montoEstimado,
    estado = EstadoSiniestro.REPORTADO,
    siguientePaso = SiguientePaso.ADJUNTAR_EVIDENCIA,
    canal = "movil",
    reportadoEn = "2026-10-26T15:00:00Z",
)

data class CargaFalsa(
    val idSiniestro: String,
    val nombre: String,
    val tipo: String,
    val contenido: ByteArray,
    val tomadaEn: String?,
    val latitud: String?,
    val longitud: String?,
    val lugar: String?,
    /** Parte `id_requerimiento` (WEB-F06); null en las cargas del reporte y la evidencia posterior. */
    val idRequerimiento: String? = null,
)

/** Almacén en una carpeta temporal: entrega lo que indique [resultado], por `uri`. */
class AlmacenFalso(private val carpeta: File) : AlmacenEvidencias {
    var resultado: (String) -> Preparado = { uri -> listo(uri) }
    val borrados = mutableListOf<String>()
    var conservados: Set<String>? = null

    fun listo(nombre: String, formato: FormatoEvidencia = FormatoEvidencia.JPEG): Preparado.Listo {
        val archivo = File(carpeta, nombre).apply { writeBytes(ByteArray(10)) }
        return Preparado.Listo(archivo.path, nombre, formato, archivo.length())
    }

    /** Si se fija, la preparación espera a que se complete: sirve para cancelarla a medio camino. */
    var espera: CompletableDeferred<Unit>? = null

    override suspend fun preparar(uri: String, onProgreso: (Float) -> Unit): Preparado {
        espera?.await()
        return resultado(uri)
    }
    override fun nuevaCaptura() = File(carpeta, "captura.jpg")
    override fun archivo(ruta: String) = File(ruta).takeIf(File::isFile)

    override fun borrar(ruta: String) {
        borrados += ruta
        File(ruta).delete()
    }

    override fun conservarSolo(rutas: Set<String>) {
        conservados = rutas
    }
}

/** Claves predecibles: clave-1, clave-2… */
class Claves {
    var generadas = 0
        private set

    fun nueva(): String {
        generadas += 1
        return "clave-$generadas"
    }
}

// ── BFF doble ─────────────────────────────────────────────────────────────────────────────────

class BffFalso : BffApi {
    var polizas: suspend () -> List<PolizaResumen> = { listOf(POLIZA_VIGENTE, POLIZA_VENCIDA) }
    var reporte: suspend (String, SolicitudSiniestro) -> Siniestro = { _, solicitud -> siniestroDe(solicitud) }

    /** Clave y solicitud de cada `reportarSiniestro`, en orden. */
    val reportes = mutableListOf<Pair<String, SolicitudSiniestro>>()
    var consultasPolizas = 0
        private set

    override suspend fun listarPolizas(): List<PolizaResumen> {
        consultasPolizas += 1
        return polizas()
    }

    /** Dueño de cada llamada, en orden: `null` si la hizo una pantalla en línea. */
    val duenios = mutableListOf<String?>()

    /** Cuenta con sesión, para simular `InterceptorDuenio`: una llamada de otro dueño no sale. */
    var sesionDe: (() -> String?)? = null

    private fun revisarDuenio(duenio: DuenioSolicitud?) {
        val vigente = sesionDe?.invoke()
        if (duenio != null && sesionDe != null && vigente != duenio.clienteId) {
            throw SolicitudDeOtraCuenta(duenio.clienteId, vigente)
        }
        duenios += duenio?.clienteId
    }

    override suspend fun reportarSiniestro(
        claveIdempotencia: String,
        solicitud: SolicitudSiniestro,
        duenio: DuenioSolicitud?,
    ): Siniestro {
        revisarDuenio(duenio)
        reportes += claveIdempotencia to solicitud
        return reporte(claveIdempotencia, solicitud)
    }

    var siniestros: suspend () -> List<Siniestro> = { emptyList() }
    var consultasSiniestros = 0
        private set

    override suspend fun listarSiniestros(duenio: DuenioSolicitud?): List<Siniestro> {
        revisarDuenio(duenio)
        consultasSiniestros += 1
        return siniestros()
    }

    /** Carga: nombre del archivo y campos de texto. Por defecto la guarda y responde como el BFF. */
    var carga: suspend (CargaFalsa) -> Evidencia = { evidenciaDe(it) }
    var consulta: suspend () -> List<Evidencia> = { evidencias.toList() }

    /** Lo que el BFF guardó. */
    val evidencias = mutableListOf<Evidencia>()

    /** Cada intento de carga, en orden. */
    val cargas = mutableListOf<CargaFalsa>()
    var consultasEvidencia = 0
        private set

    override suspend fun adjuntarEvidencia(
        idSiniestro: String,
        archivo: MultipartBody.Part,
        tomadaEn: RequestBody?,
        latitud: RequestBody?,
        longitud: RequestBody?,
        lugar: RequestBody?,
        idRequerimiento: RequestBody?,
        duenio: DuenioSolicitud?,
    ): Evidencia {
        revisarDuenio(duenio)
        val disposicion = archivo.headers?.get("Content-Disposition").orEmpty()
        val nombre = disposicion.substringAfter("filename=\"").substringBefore('"')
        val datos = CargaFalsa(
            idSiniestro,
            nombre,
            archivo.body.contentType().toString(),
            Buffer().also(archivo.body::writeTo).readByteArray(),
            tomadaEn?.texto(),
            latitud?.texto(),
            longitud?.texto(),
            lugar?.texto(),
            idRequerimiento?.texto(),
        )
        cargas += datos
        return carga(datos)
    }

    /** `GET /siniestros/{id}` (WEB-F06). */
    var detalle: suspend (String) -> SiniestroDetalle = { error("detalle no configurado") }
    var consultasDetalle = 0
        private set

    override suspend fun obtenerSiniestro(idSiniestro: String): SiniestroDetalle {
        consultasDetalle += 1
        return detalle(idSiniestro)
    }

    override suspend fun listarEvidencias(idSiniestro: String, duenio: DuenioSolicitud?): List<Evidencia> {
        revisarDuenio(duenio)
        consultasEvidencia += 1
        return consulta()
    }

    /** Guarda la evidencia, como el BFF; una prueba puede lanzar después para perder la respuesta. */
    fun evidenciaDe(datos: CargaFalsa) = Evidencia(
        id = "EVI-${evidencias.size + 1}",
        tipoContenido = datos.tipo,
        tamano = 10,
        nombreOriginal = datos.nombre,
        canal = "movil",
        cargadaEn = "2026-10-26T15:00:00Z",
        tomadaEn = datos.tomadaEn,
        latitud = datos.latitud?.toDouble(),
        longitud = datos.longitud?.toDouble(),
        lugar = datos.lugar,
    ).also { evidencias += it }

    private fun RequestBody.texto() = Buffer().also(::writeTo).readUtf8()

    override suspend fun crearRegistro(solicitud: SolicitudRegistro): Registro = error("no se usa en siniestros")

    override suspend fun verificarOtp(idRegistro: String, solicitud: SolicitudOtp): Sesion =
        error("no se usa en siniestros")

    override suspend fun reenviarOtp(idRegistro: String): Otp = error("no se usa en siniestros")

    override suspend fun iniciarSesion(credenciales: Credenciales): Sesion = error("no se usa en siniestros")

    override suspend fun renovarSesion(solicitud: SolicitudRenovacion): Sesion = error("no se usa en siniestros")

    override suspend fun obtenerSesion(): Cliente = error("no se usa en siniestros")

    override suspend fun obtenerVerificacionIdentidad(): VerificacionIdentidad = error("no se usa en siniestros")

    override suspend fun solicitarVerificacionIdentidad(): VerificacionIdentidad = error("no se usa en siniestros")

    override suspend fun listarFinalidades(): List<FinalidadConsentimiento> = error("no se usa en siniestros")

    override suspend fun listarConsentimientos(): List<Consentimiento> = error("no se usa en siniestros")

    override suspend fun otorgarConsentimiento(solicitud: SolicitudConsentimiento): Consentimiento =
        error("no se usa en siniestros")

    override suspend fun revocarConsentimiento(idConsentimiento: String): Consentimiento =
        error("no se usa en siniestros")

    override suspend fun renovarConsentimiento(idConsentimiento: String): Consentimiento =
        error("no se usa en siniestros")
}

// ── Borradores (MOB-F05-02) ────────────────────────────────────────────────────────────────────

const val CLIENTE = "CLI-2026-000001"
const val OTRO_CLIENTE = "CLI-2026-000002"

/** Red controlable desde la prueba. */
class MonitorFalso(conectado: Boolean = true) : MonitorConectividad {
    override val conectado = MutableStateFlow(conectado)
}

/** Llave AES-256 de la JCA, en memoria: el mismo código de cifrado que con el Keystore. */
class LlaveJca : FuenteLlave {
    var llave: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    var fallo: Exception? = null

    override fun paraCifrar(): SecretKey = llave ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        .also { llave = it }

    override fun paraDescifrar(): SecretKey {
        fallo?.let { throw it }
        return llave ?: throw LlaveInexistente()
    }
}

/** Cifrado que falla al escribir tras [bytes] bytes: disco lleno o escritura incompleta. */
class CifradoQueFalla(private val real: PuertoCifrado, var bytes: Long? = null) : PuertoCifrado {
    override fun cifrar(destino: OutputStream): OutputStream {
        val limite = bytes ?: return real.cifrar(destino)
        var escritos = 0L
        return object : OutputStream() {
            override fun write(b: Int) {
                escritos += 1
                if (escritos > limite) throw IOException("No queda espacio en el dispositivo")
            }
        }
    }

    override fun descifrar(origen: InputStream): InputStream = real.descifrar(origen)
}

/** El enviador con el almacén real (AES-GCM de la JCA) en [carpeta] y la sesión que diga [cliente]. */
@Suppress("LongParameterList")
class Entorno(
    carpeta: File,
    val api: BffFalso = BffFalso(),
    val monitor: MonitorFalso = MonitorFalso(),
    val alcance: CoroutineScope = CoroutineScope(Dispatchers.Main),
    reloj: () -> Instant = { Instant.parse("2026-10-26T15:00:00Z") },
    esperas: List<Duration> = ESPERAS_REINTENTO,
    /** La misma llave del Keystore sobrevive a la muerte del proceso: se pasa la del entorno anterior. */
    val llave: LlaveJca = LlaveJca(),
) {
    val cifrado = CifradoQueFalla(CifradoAesGcm(llave))
    val raiz = File(carpeta, "borradores")
    val almacen = AlmacenBorradoresCifrado(raiz, cifrado)
    var cliente: String? = CLIENTE

    init {
        api.sesionDe = { cliente }
    }

    val enviador = EnviadorBorradores(
        api = api,
        almacen = almacen,
        monitor = monitor,
        clienteVigente = { cliente },
        alcance = alcance,
        reloj = reloj,
        io = Dispatchers.Main,
        esperas = esperas,
    )

    fun borradores(duenio: String = CLIENTE) = almacen.listar(duenio)
        .filterIsInstance<co.solventa.feature.siniestros.borrador.EntradaBorrador.Legible>()
        .map { it.borrador }
}
