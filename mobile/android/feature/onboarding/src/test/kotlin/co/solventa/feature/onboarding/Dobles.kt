package co.solventa.feature.onboarding

import co.solventa.core.network.BffApi
import co.solventa.core.network.Cliente
import co.solventa.core.network.Credenciales
import co.solventa.core.network.ErrorBff
import co.solventa.core.network.ErrorCampo
import co.solventa.core.network.EstadoIdentidad
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.Otp
import co.solventa.core.network.Registro
import co.solventa.core.network.Sesion
import co.solventa.core.network.SolicitudOtp
import co.solventa.core.network.SolicitudRegistro
import co.solventa.core.network.SolicitudRenovacion
import co.solventa.core.network.VerificacionIdentidad
import co.solventa.feature.onboarding.biometria.AlmacenToken
import co.solventa.feature.onboarding.biometria.Biometria
import co.solventa.feature.onboarding.biometria.DisponibilidadBiometrica
import co.solventa.feature.onboarding.biometria.PropositoBiometrico
import co.solventa.feature.onboarding.biometria.ResultadoBiometrico
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import retrofit2.HttpException
import retrofit2.Response

/** viewModelScope corre en Dispatchers.Main: en JVM se reemplaza por un despachador inmediato. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReglaMain : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(UnconfinedTestDispatcher())

    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** Error HTTP con el cuerpo de error del contrato. */
fun fallaBff(
    http: Int,
    codigo: String,
    intentosRestantes: Int? = null,
    campos: List<ErrorCampo> = emptyList(),
    encabezados: Map<String, String> = emptyMap(),
): HttpException {
    val error = ErrorBff(codigo, "prueba", campos, intentosRestantes)
    val cuerpo = NetworkClient.json.encodeToString(ErrorBff.serializer(), error)
    val crudo = okhttp3.Response.Builder()
        .code(http)
        .message("Prueba")
        .protocol(Protocol.HTTP_1_1)
        .request(Request.Builder().url("https://bff.prueba/api/v1/movil/").build())
        .apply { encabezados.forEach { (nombre, valor) -> header(nombre, valor) } }
        .build()
    return HttpException(Response.error<Any>(cuerpo.toResponseBody("application/json".toMediaType()), crudo))
}

fun cliente(estado: EstadoIdentidad = EstadoIdentidad.PENDIENTE_VERIFICACION) =
    Cliente("CLI-1", "Ana María Rojas", "ana@ejemplo.co", estado)

fun sesionPrueba(estado: EstadoIdentidad = EstadoIdentidad.PENDIENTE_VERIFICACION, refresco: String = "REF-1") =
    Sesion("ACC-1", refresco, "2026-10-17T10:15:00Z", cliente(estado))

fun otp(intentos: Int = 3, reenvio: String = "2026-10-17T10:00:30Z") = Otp("2026-10-17T10:10:00Z", intentos, reenvio)

/** BffApi programable: cada operación responde con su lambda; sin programar, falla la prueba. */
class BffFalso : BffApi {
    var registro: suspend (SolicitudRegistro) -> Registro = { error("crearRegistro no programado") }
    var verificacionOtp: suspend (String, SolicitudOtp) -> Sesion = { _, _ -> error("verificarOtp no programado") }
    var reenvio: suspend (String) -> Otp = { error("reenviarOtp no programado") }
    var ingreso: suspend (Credenciales) -> Sesion = { error("iniciarSesion no programado") }
    var renovacion: suspend (SolicitudRenovacion) -> Sesion = { error("renovarSesion no programado") }
    var consulta: suspend () -> VerificacionIdentidad = { error("obtenerVerificacionIdentidad no programado") }
    var solicitud: suspend () -> VerificacionIdentidad = { error("solicitarVerificacionIdentidad no programado") }
    val llamadas = mutableListOf<String>()

    override suspend fun crearRegistro(solicitud: SolicitudRegistro): Registro {
        llamadas += "registro"
        return registro(solicitud)
    }

    override suspend fun verificarOtp(idRegistro: String, solicitud: SolicitudOtp): Sesion {
        llamadas += "otp:${solicitud.codigo}"
        return verificacionOtp(idRegistro, solicitud)
    }

    override suspend fun reenviarOtp(idRegistro: String): Otp {
        llamadas += "reenvio"
        return reenvio(idRegistro)
    }

    override suspend fun iniciarSesion(credenciales: Credenciales): Sesion {
        llamadas += "ingreso"
        return ingreso(credenciales)
    }

    override suspend fun renovarSesion(solicitud: SolicitudRenovacion): Sesion {
        llamadas += "renovacion:${solicitud.tokenRefresco}"
        return renovacion(solicitud)
    }

    override suspend fun obtenerSesion(): Cliente = error("no se usa en MOB-F01")

    override suspend fun obtenerVerificacionIdentidad(): VerificacionIdentidad {
        llamadas += "consulta"
        return consulta()
    }

    override suspend fun solicitarVerificacionIdentidad(): VerificacionIdentidad {
        llamadas += "solicitud"
        return solicitud()
    }
}

/** Almacén en memoria; [cipher] es un objeto opaco que solo viaja entre el almacén y la biometría. */
class AlmacenFalso(var token: String? = null) : AlmacenToken {
    var llaveCreable = true
    var llaveInvalidada = false
    val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    override fun hayToken() = token != null

    override fun guardar(token: String): Boolean {
        if (!llaveCreable) return false
        this.token = token
        return true
    }

    override fun prepararLectura(): Cipher? = if (llaveInvalidada) null else cipher.takeIf { hayToken() }

    override fun leer(desbloqueado: Cipher): String? = token

    override fun borrar() {
        token = null
    }
}

class BiometriaFalsa(
    var disponible: DisponibilidadBiometrica = DisponibilidadBiometrica.DISPONIBLE,
    var resultado: (Cipher) -> ResultadoBiometrico = { ResultadoBiometrico.Desbloqueado(it) },
) : Biometria {
    val propositos = mutableListOf<PropositoBiometrico>()

    override fun disponibilidad() = disponible

    override fun autenticar(cipher: Cipher, proposito: PropositoBiometrico, alTerminar: (ResultadoBiometrico) -> Unit) {
        propositos += proposito
        alTerminar(resultado(cipher))
    }
}
