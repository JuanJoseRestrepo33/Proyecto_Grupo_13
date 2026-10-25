package co.solventa.mobile.mock

import co.solventa.core.network.BffApi
import co.solventa.core.network.Credenciales
import co.solventa.core.network.ErrorBff
import co.solventa.core.network.EstadoIdentidad
import co.solventa.core.network.EstadoVerificacion
import co.solventa.core.network.MotivoRechazo
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.Sesion
import co.solventa.core.network.SolicitudConsentimiento
import co.solventa.core.network.SolicitudOtp
import co.solventa.core.network.SolicitudRegistro
import co.solventa.core.network.SolicitudRenovacion
import co.solventa.core.network.TipoDocumento
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.DebugMockInterceptor
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.HttpException

// Recorre el mock a través del cliente real: BffApi → OkHttp → DebugMockInterceptor → MockBffMovil.
class MockBffMovilTest {
    private var ahora = Instant.parse("2026-10-17T10:00:00Z")
    private var token: String? = null
    private val api: BffApi = NetworkClient.create(
        BuildConfig.BASE_URL,
        listOf(DebugMockInterceptor(MockBffMovil { ahora })),
        tokenAcceso = { token },
    )

    private fun solicitud(documento: String = "1098765432", correo: String = "nuevo@ejemplo.co") = SolicitudRegistro(
        nombreCompleto = "Juan Pérez",
        tipoDocumento = TipoDocumento.CEDULA_CIUDADANIA,
        numeroDocumento = documento,
        correo = correo,
        celular = "3109876543",
        contrasena = "Clave2026",
        versionTerminos = "2026-09",
    )

    private fun falla(llamada: suspend () -> Any): Pair<Int, ErrorBff?> {
        val error = assertThrows(HttpException::class.java) { runBlocking { llamada() } }
        return error.code() to ErrorBff.desde(error)
    }

    private fun registrarYVerificar(documento: String): Sesion = runBlocking {
        val registro = api.crearRegistro(solicitud(documento, "doc$documento@ejemplo.co"))
        api.verificarOtp(registro.id, SolicitudOtp(CODIGO_OTP)).also { token = it.tokenAcceso }
    }

    @Test
    fun `registro, OTP y KYC aprobada dejan la identidad verificada`() = runBlocking {
        val registro = api.crearRegistro(solicitud())
        assertEquals(EstadoIdentidad.PENDIENTE_VERIFICACION, registro.estadoIdentidad)
        assertEquals("2026-10-17T10:10:00Z", registro.otp.expiraEn)
        val sesion = api.verificarOtp(registro.id, SolicitudOtp(CODIGO_OTP))
        assertEquals(EstadoIdentidad.PENDIENTE_VERIFICACION, sesion.cliente.estadoIdentidad)
        token = sesion.tokenAcceso
        assertEquals(EstadoVerificacion.APROBADA, api.solicitarVerificacionIdentidad().estado)
        assertEquals(EstadoIdentidad.VERIFICADA, api.obtenerSesion().estadoIdentidad)
        assertEquals(409 to "identidad_ya_verificada", falla { api.solicitarVerificacionIdentidad() }.clave())
    }

    @Test
    fun `tres OTP incorrectos bloquean el codigo y no la cuenta`() {
        val id = runBlocking { api.crearRegistro(solicitud()).id }
        assertEquals(422 to 2, falla { api.verificarOtp(id, SolicitudOtp("000000")) }.intentos())
        assertEquals(422 to 1, falla { api.verificarOtp(id, SolicitudOtp("000000")) }.intentos())
        assertEquals(409 to "otp_bloqueado", falla { api.verificarOtp(id, SolicitudOtp("000000")) }.clave())
        assertEquals(409 to "otp_bloqueado", falla { api.verificarOtp(id, SolicitudOtp(CODIGO_OTP)) }.clave())

        assertEquals(429 to "otp_reenvio_prematuro", falla { api.reenviarOtp(id) }.clave())
        ahora += Duration.ofSeconds(30)
        assertEquals(3, runBlocking { api.reenviarOtp(id) }.intentosRestantes)
        runBlocking { api.verificarOtp(id, SolicitudOtp(CODIGO_OTP)) }
        assertEquals(409 to "registro_ya_verificado", falla { api.verificarOtp(id, SolicitudOtp(CODIGO_OTP)) }.clave())
        assertEquals(409 to "registro_ya_verificado", falla { api.reenviarOtp(id) }.clave())
    }

    @Test
    fun `el reenvio prematuro indica cuanto esperar`() {
        val id = runBlocking { api.crearRegistro(solicitud()).id }
        ahora += Duration.ofSeconds(12)
        val error = assertThrows(HttpException::class.java) { runBlocking { api.reenviarOtp(id) } }
        assertEquals("18", error.response()?.headers()?.get("Retry-After"))
    }

    @Test
    fun `un OTP vencido no se acepta`() {
        val id = runBlocking { api.crearRegistro(solicitud()).id }
        ahora += Duration.ofMinutes(10)
        assertEquals(409 to "otp_vencido", falla { api.verificarOtp(id, SolicitudOtp(CODIGO_OTP)) }.clave())
        assertEquals(404 to "registro_no_encontrado", falla { api.verificarOtp("REG-X", SolicitudOtp("1")) }.clave())
        assertEquals(404 to "registro_no_encontrado", falla { api.reenviarOtp("REG-X") }.clave())
    }

    @Test
    fun `el registro valida cada campo y rechaza duplicados`() {
        val invalida = solicitud().copy(nombreCompleto = " A", numeroDocumento = "12", correo = "sin-arroba",
            celular = "123", contrasena = "solo-letras", versionTerminos = "")
        val (codigo, error) = falla { api.crearRegistro(invalida) }
        assertEquals(422, codigo)
        assertEquals(
            listOf("nombre_completo", "numero_documento", "correo", "celular", "contrasena", "version_terminos"),
            error?.campos?.map { it.campo },
        )
        val duplicado = solicitud(correo = CORREO_DEMO)
        assertEquals(409 to "correo_ya_registrado", falla { api.crearRegistro(duplicado) }.clave())
        assertEquals(409 to "documento_ya_registrado", falla { api.crearRegistro(solicitud("1020304050")) }.clave())
    }

    @Test
    fun `el ingreso con contrasena abre sesion y la renovacion rota el token`() = runBlocking {
        val sesion = api.iniciarSesion(Credenciales(CORREO_DEMO, CONTRASENA_DEMO))
        assertEquals(EstadoIdentidad.VERIFICADA, sesion.cliente.estadoIdentidad)
        val renovada = api.renovarSesion(SolicitudRenovacion(sesion.tokenRefresco))
        assertEquals(sesion.cliente, renovada.cliente)
        assertEquals(
            401 to "token_refresco_invalido",
            falla { api.renovarSesion(SolicitudRenovacion(sesion.tokenRefresco)) }.clave(),
        )
        assertEquals(
            401 to "credenciales_invalidas",
            falla { api.iniciarSesion(Credenciales(CORREO_DEMO, "otra")) }.clave(),
        )
    }

    @Test
    fun `sin correo verificado no se puede ingresar`() {
        runBlocking { api.crearRegistro(solicitud()) }
        assertEquals(
            401 to "credenciales_invalidas",
            falla { api.iniciarSesion(Credenciales("nuevo@ejemplo.co", "Clave2026")) }.clave(),
        )
    }

    @Test
    fun `KYC rechazada trae motivo y permite reintentar`() = runBlocking {
        registrarYVerificar("1000000007")
        val resultado = api.solicitarVerificacionIdentidad()
        assertEquals(EstadoVerificacion.RECHAZADA, resultado.estado)
        assertEquals(MotivoRechazo.DATOS_NO_COINCIDEN, resultado.motivo)
        assertEquals(true, resultado.reintentoPermitido)
        assertEquals(EstadoIdentidad.RECHAZADA, api.obtenerSesion().estadoIdentidad)
        assertEquals(EstadoVerificacion.RECHAZADA, api.solicitarVerificacionIdentidad().estado)
    }

    @Test
    fun `KYC en revision manual no admite otra solicitud`() = runBlocking {
        registrarYVerificar("1000000008")
        assertEquals(EstadoVerificacion.EN_REVISION, api.solicitarVerificacionIdentidad().estado)
        assertEquals(EstadoIdentidad.EN_REVISION, api.obtenerSesion().estadoIdentidad)
        assertEquals(409 to "verificacion_en_curso", falla { api.solicitarVerificacionIdentidad() }.clave())
        assertEquals(EstadoVerificacion.EN_REVISION, api.obtenerVerificacionIdentidad().estado)
    }

    @Test
    fun `KYC en proceso se resuelve en la siguiente consulta`() = runBlocking {
        registrarYVerificar("1000000009")
        assertEquals(404 to "verificacion_no_encontrada", falla { api.obtenerVerificacionIdentidad() }.clave())
        assertEquals(EstadoVerificacion.EN_PROCESO, api.solicitarVerificacionIdentidad().estado)
        assertEquals(EstadoVerificacion.APROBADA, api.obtenerVerificacionIdentidad().estado)
        assertEquals(EstadoIdentidad.VERIFICADA, api.obtenerSesion().estadoIdentidad)
    }

    @Test
    fun `las operaciones protegidas exigen sesion`() {
        token = null
        assertEquals(401 to "no_autenticado", falla { api.obtenerSesion() }.clave())
        assertEquals(401 to "no_autenticado", falla { api.obtenerVerificacionIdentidad() }.clave())
        token = "ACC-inventado"
        assertEquals(401 to "no_autenticado", falla { api.solicitarVerificacionIdentidad() }.clave())
    }

    @Test
    fun `rutas desconocidas y cuerpos invalidos responden sin salir a la red`() {
        val mock = MockBffMovil { ahora }
        assertEquals(404, mock.responder("GET", "polizas", "", null).codigo)
        assertEquals(404, mock.responder("GET", "registros/REG-1/otp/reenvio", "", null).codigo)
        val invalida = mock.responder("POST", "sesiones", """{"correo":1}""", null)
        assertEquals(422, invalida.codigo)
        assertNull(mock.responder("POST", "registros", "{}", null).encabezados["Retry-After"])
    }

    private fun Pair<Int, ErrorBff?>.clave() = first to second?.codigo
    private fun Pair<Int, ErrorBff?>.intentos() = first to second?.intentosRestantes

    // ── Consentimiento ────────────────────────────────────────────────────────────────────────

    private fun ingresarComoDemo(): Unit = runBlocking {
        val sesion = api.iniciarSesion(Credenciales(CORREO_DEMO, CONTRASENA_DEMO))
        token = sesion.tokenAcceso
    }

    @Test
    fun `el catalogo de finalidades no requiere autenticacion`() = runBlocking {
        val finalidades = api.listarFinalidades()
        assertEquals(3, finalidades.size)
        assertEquals(co.solventa.core.network.CodigoFinalidad.COTIZACION, finalidades[0].codigo)
        assertEquals("cotizacion-2026.1", finalidades[0].versionTexto)
    }

    @Test
    fun `listar consentimientos requiere sesion`() {
        assertEquals(401 to "no_autenticado", falla { api.listarConsentimientos() }.clave())
        ingresarComoDemo()
        val lista = runBlocking { api.listarConsentimientos() }
        assertEquals(0, lista.size)
    }

    @Test
    fun `otorgar consentimiento con version correcta crea uno vigente`() = runBlocking {
        ingresarComoDemo()
        val finalidades = api.listarFinalidades()
        val f = finalidades.first { it.codigo == co.solventa.core.network.CodigoFinalidad.COTIZACION }
        val consentimiento = api.otorgarConsentimiento(
            co.solventa.core.network.SolicitudConsentimiento(
                finalidad = f.codigo,
                entidadesOrigen = listOf("bancolombia"),
                versionTexto = f.versionTexto,
            ),
        )
        assertEquals(co.solventa.core.network.EstadoConsentimiento.VIGENTE, consentimiento.estado)
        assertEquals("bancolombia", consentimiento.entidadesOrigen[0].id)
        // Aparece en la lista
        assertEquals(1, api.listarConsentimientos().size)
    }

    @Test
    fun `otorgar con version desactualizada falla`() {
        ingresarComoDemo()
        assertEquals(
            422 to "version_texto_desactualizada",
            falla {
                api.otorgarConsentimiento(
                    co.solventa.core.network.SolicitudConsentimiento(
                        finalidad = co.solventa.core.network.CodigoFinalidad.COTIZACION,
                        entidadesOrigen = listOf("bancolombia"),
                        versionTexto = "cotizacion-2020.1",
                    ),
                )
            }.clave(),
        )
    }

    @Test
    fun `otorgar con entidades invalidas falla`() {
        ingresarComoDemo()
        val finalidades = runBlocking { api.listarFinalidades() }
        val f = finalidades.first { it.codigo == co.solventa.core.network.CodigoFinalidad.COTIZACION }
        assertEquals(
            422 to "entidades_invalidas",
            falla {
                api.otorgarConsentimiento(
                    co.solventa.core.network.SolicitudConsentimiento(
                        finalidad = f.codigo,
                        entidadesOrigen = listOf("entidad-inexistente"),
                        versionTexto = f.versionTexto,
                    ),
                )
            }.clave(),
        )
    }

    @Test
    fun `revocar vigente cambia el estado a revocado`() = runBlocking {
        ingresarComoDemo()
        val finalidades = api.listarFinalidades()
        val f = finalidades.first { it.codigo == co.solventa.core.network.CodigoFinalidad.COTIZACION }
        val creado = api.otorgarConsentimiento(
            co.solventa.core.network.SolicitudConsentimiento(
                finalidad = f.codigo,
                entidadesOrigen = listOf("bancolombia"),
                versionTexto = f.versionTexto,
            ),
        )
        val revocado = api.revocarConsentimiento(creado.id)
        assertEquals(co.solventa.core.network.EstadoConsentimiento.REVOCADO, revocado.estado)
        assertEquals(409 to "consentimiento_revocado", falla { api.revocarConsentimiento(creado.id) }.clave())
    }

    @Test
    fun `renovar vencido extiende la vigencia`() = runBlocking {
        ingresarComoDemo()
        val finalidades = api.listarFinalidades()
        val f = finalidades.first { it.codigo == co.solventa.core.network.CodigoFinalidad.COTIZACION }
        val creado = api.otorgarConsentimiento(
            co.solventa.core.network.SolicitudConsentimiento(
                finalidad = f.codigo,
                entidadesOrigen = listOf("bancolombia"),
                versionTexto = f.versionTexto,
            ),
        )
        // Avanzar el reloj 200 días para que venza
        ahora = ahora.plus(java.time.Duration.ofDays(200))
        val listaTras = api.listarConsentimientos()
        assertEquals(co.solventa.core.network.EstadoConsentimiento.VENCIDO, listaTras[0].estado)

        val renovado = api.renovarConsentimiento(creado.id)
        assertEquals(co.solventa.core.network.EstadoConsentimiento.VIGENTE, renovado.estado)
    }

    @Test
    fun `revocar consentimiento inexistente devuelve 404`() {
        ingresarComoDemo()
        assertEquals(404 to "consentimiento_no_encontrado", falla { api.revocarConsentimiento("CON-X") }.clave())
    }
}
