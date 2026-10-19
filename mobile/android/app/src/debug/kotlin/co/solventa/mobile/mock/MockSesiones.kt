package co.solventa.mobile.mock

import co.solventa.core.network.Credenciales
import co.solventa.core.network.EstadoIdentidad
import co.solventa.core.network.EstadoVerificacion
import co.solventa.core.network.MotivoRechazo
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.SolicitudRenovacion
import co.solventa.core.network.VerificacionIdentidad

private val EN_CURSO = setOf(EstadoVerificacion.EN_PROCESO, EstadoVerificacion.EN_REVISION)

/** Ingreso, renovación (biometría) y sesión: `POST /sesiones`, `/sesiones/renovacion` y `GET /sesion`. */
internal class MockSesiones(private val estado: EstadoMock) {
    fun iniciar(cuerpo: String): RespuestaMock {
        val credenciales = NetworkClient.json.decodeFromString(Credenciales.serializer(), cuerpo)
        val cuenta = estado.cuentas[credenciales.correo]
            ?.takeIf { it.correoVerificado && it.contrasena == credenciales.contrasena }
        return if (cuenta == null) fallo(401, "credenciales_invalidas") else respuesta(200, estado.abrirSesion(cuenta))
    }

    /** Cada token de refresco sirve una sola vez (rotación). */
    fun renovar(cuerpo: String): RespuestaMock {
        val token = NetworkClient.json.decodeFromString(SolicitudRenovacion.serializer(), cuerpo).tokenRefresco
        val cuenta = estado.refrescos.remove(token)?.let(estado.cuentas::get)
        return if (cuenta == null) fallo(401, "token_refresco_invalido") else respuesta(200, estado.abrirSesion(cuenta))
    }

    fun sesion(autorizacion: String?): RespuestaMock =
        estado.cuentaAutenticada(autorizacion)?.let { respuesta(200, it.cliente()) } ?: noAutenticado()
}

/**
 * Verificación de identidad: `GET` y `POST /identidad/verificacion`. El resultado del proveedor
 * simulado depende del último dígito del documento:
 * 7 rechazada · 8 en revisión manual · 9 en proceso (la siguiente consulta la encuentra aprobada) ·
 * cualquier otro aprobada.
 */
internal class MockIdentidad(private val estado: EstadoMock) {
    fun consultar(autorizacion: String?): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion)
        val actual = cuenta?.verificacion
        return when {
            cuenta == null -> noAutenticado()
            actual == null -> fallo(404, "verificacion_no_encontrada")
            actual.estado == EstadoVerificacion.EN_PROCESO -> {
                cuenta.aplicar(
                    actual.copy(estado = EstadoVerificacion.APROBADA, actualizadaEn = estado.reloj().toString()),
                )
                respuesta(200, cuenta.verificacion)
            }
            else -> respuesta(200, actual)
        }
    }

    fun solicitar(autorizacion: String?): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion)
        val enCurso = cuenta?.verificacion?.estado in EN_CURSO
        return when {
            cuenta == null -> noAutenticado()
            cuenta.estadoIdentidad == EstadoIdentidad.VERIFICADA -> fallo(409, "identidad_ya_verificada")
            enCurso -> fallo(409, "verificacion_en_curso")
            else -> {
                cuenta.aplicar(resultadoPara(cuenta.numeroDocumento))
                respuesta(202, cuenta.verificacion)
            }
        }
    }

    private fun resultadoPara(numeroDocumento: String): VerificacionIdentidad {
        val id = estado.siguiente("KYC")
        val ahora = estado.reloj().toString()
        return when (numeroDocumento.last()) {
            '7' -> VerificacionIdentidad(
                id = id,
                estado = EstadoVerificacion.RECHAZADA,
                motivo = MotivoRechazo.DATOS_NO_COINCIDEN,
                reintentoPermitido = true,
                actualizadaEn = ahora,
            )
            '8' -> VerificacionIdentidad(id, EstadoVerificacion.EN_REVISION, actualizadaEn = ahora)
            '9' -> VerificacionIdentidad(id, EstadoVerificacion.EN_PROCESO, actualizadaEn = ahora)
            else -> VerificacionIdentidad(id, EstadoVerificacion.APROBADA, actualizadaEn = ahora)
        }
    }
}
