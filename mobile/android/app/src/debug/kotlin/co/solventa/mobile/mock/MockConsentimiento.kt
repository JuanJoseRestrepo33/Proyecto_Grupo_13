package co.solventa.mobile.mock

import co.solventa.core.network.CodigoFinalidad
import co.solventa.core.network.Consentimiento
import co.solventa.core.network.EntidadConsentimiento
import co.solventa.core.network.EstadoConsentimiento
import co.solventa.core.network.FinalidadConsentimiento
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.SolicitudConsentimiento
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private val UMBRAL_POR_VENCER: Duration = Duration.ofDays(30)

private val ENTIDADES: Map<CodigoFinalidad, List<EntidadConsentimiento>> = mapOf(
    CodigoFinalidad.COTIZACION to listOf(
        EntidadConsentimiento("bancolombia", "Bancolombia"),
        EntidadConsentimiento("davivienda", "Davivienda"),
        EntidadConsentimiento("nequi", "Nequi"),
    ),
    CodigoFinalidad.SUSCRIPCION to listOf(
        EntidadConsentimiento("bancolombia", "Bancolombia"),
        EntidadConsentimiento("davivienda", "Davivienda"),
    ),
    CodigoFinalidad.PREVENCION_FRAUDE to listOf(
        EntidadConsentimiento("bancolombia", "Bancolombia"),
        EntidadConsentimiento("davivienda", "Davivienda"),
        EntidadConsentimiento("nequi", "Nequi"),
    ),
)

internal val CATALOGO_FINALIDADES = listOf(
    FinalidadConsentimiento(
        codigo = CodigoFinalidad.COTIZACION,
        nombre = "Cotización personalizada",
        descripcion = "Permite calcular primas con tu perfil financiero real para obtener precios más precisos.",
        alcance = listOf("saldos_cuenta", "movimientos_6_meses", "score_crediticio"),
        vigenciaMeses = 6,
        entidadesOrigen = requireNotNull(ENTIDADES[CodigoFinalidad.COTIZACION]),
        versionTexto = "cotizacion-2026.1",
    ),
    FinalidadConsentimiento(
        codigo = CodigoFinalidad.SUSCRIPCION,
        nombre = "Suscripción de póliza",
        descripcion = "Permite verificar solvencia financiera para aprobar la emisión de tu póliza.",
        alcance = listOf("saldos_cuenta", "historial_pagos"),
        vigenciaMeses = 12,
        entidadesOrigen = requireNotNull(ENTIDADES[CodigoFinalidad.SUSCRIPCION]),
        versionTexto = "suscripcion-2026.1",
    ),
    FinalidadConsentimiento(
        codigo = CodigoFinalidad.PREVENCION_FRAUDE,
        nombre = "Prevención de fraude",
        descripcion = "Permite detectar patrones inusuales para proteger tu cuenta y pólizas.",
        alcance = listOf("movimientos_recientes", "ubicaciones"),
        vigenciaMeses = 12,
        entidadesOrigen = requireNotNull(ENTIDADES[CodigoFinalidad.PREVENCION_FRAUDE]),
        versionTexto = "fraude-2026.1",
    ),
)

internal data class DatosConsentimiento(
    val id: String,
    val idCuenta: String,
    val finalidad: CodigoFinalidad,
    val entidades: List<EntidadConsentimiento>,
    val versionTexto: String,
    val otorgadoEn: Instant,
    val vigenciaMeses: Long,
)

internal class ConsentimientoMock(datos: DatosConsentimiento) {
    val id = datos.id
    val idCuenta = datos.idCuenta
    val finalidad = datos.finalidad
    private val entidades = datos.entidades
    private val versionTexto = datos.versionTexto
    private val otorgadoEn = datos.otorgadoEn
    private val vigenciaMeses = datos.vigenciaMeses

    var venceEn: Instant = sumarMeses(datos.otorgadoEn, vigenciaMeses)
    var revocadoEn: Instant? = null

    fun estado(ahora: Instant): EstadoConsentimiento = when {
        revocadoEn != null -> EstadoConsentimiento.REVOCADO
        !ahora.isBefore(venceEn) -> EstadoConsentimiento.VENCIDO
        else -> EstadoConsentimiento.VIGENTE
    }

    fun porVencer(ahora: Instant): Boolean =
        estado(ahora) == EstadoConsentimiento.VIGENTE &&
            Duration.between(ahora, venceEn) <= UMBRAL_POR_VENCER

    fun diasParaVencer(ahora: Instant): Int? =
        if (estado(ahora) == EstadoConsentimiento.VIGENTE) {
            Duration.between(ahora, venceEn).toDays().toInt().coerceAtLeast(0)
        } else {
            null
        }

    fun dto(ahora: Instant) = Consentimiento(
        id = id,
        finalidad = finalidad,
        entidadesOrigen = entidades,
        estado = estado(ahora),
        canal = "movil",
        otorgadoEn = otorgadoEn.toString(),
        venceEn = venceEn.toString(),
        revocadoEn = revocadoEn?.toString(),
        versionTexto = versionTexto,
        porVencer = porVencer(ahora),
        diasParaVencer = diasParaVencer(ahora),
    )

    fun revocar(ahora: Instant) { revocadoEn = ahora }

    fun renovar(ahora: Instant) {
        revocadoEn = null
        venceEn = sumarMeses(ahora, vigenciaMeses)
    }
}

internal class MockConsentimiento(private val estado: EstadoMock) {

    private val consentimientos = mutableListOf<ConsentimientoMock>()

    fun finalidades(autorizacion: String?): RespuestaMock =
        if (estado.cuentaAutenticada(autorizacion) == null) noAutenticado() else respuesta(200, CATALOGO_FINALIDADES)

    fun listar(autorizacion: String?): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        val ahora = estado.reloj()
        return respuesta(200, consentimientos.filter { it.idCuenta == cuenta.id }.map { it.dto(ahora) })
    }

    @Suppress("ReturnCount")
    fun otorgar(autorizacion: String?, cuerpo: String): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        val solicitud = NetworkClient.json.decodeFromString(SolicitudConsentimiento.serializer(), cuerpo)
        val ahora = estado.reloj()

        validar(solicitud)?.let { return it }
        if (hayVigente(cuenta.id, solicitud.finalidad, ahora)) return fallo(409, "consentimiento_ya_vigente")

        val fin = CATALOGO_FINALIDADES.first { it.codigo == solicitud.finalidad }
        val nuevo = ConsentimientoMock(
            DatosConsentimiento(
                id = estado.siguiente("CON"),
                idCuenta = cuenta.id,
                finalidad = solicitud.finalidad,
                entidades = fin.entidadesOrigen.filter { it.id in solicitud.entidadesOrigen },
                versionTexto = solicitud.versionTexto,
                otorgadoEn = ahora,
                vigenciaMeses = fin.vigenciaMeses.toLong(),
            ),
        )
        consentimientos += nuevo
        return respuesta(201, nuevo.dto(ahora))
    }

    @Suppress("ReturnCount")
    fun revocar(autorizacion: String?, idConsentimiento: String): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        val ahora = estado.reloj()
        val con = buscar(cuenta.id, idConsentimiento) ?: return fallo(404, "consentimiento_no_encontrado")
        return revocarSegunEstado(con, ahora)
    }

    @Suppress("ReturnCount")
    fun renovar(autorizacion: String?, idConsentimiento: String): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        val ahora = estado.reloj()
        val con = buscar(cuenta.id, idConsentimiento) ?: return fallo(404, "consentimiento_no_encontrado")
        return renovarSegunEstado(con, cuenta.id, ahora)
    }

    // ── privados ──────────────────────────────────────────────────────────────────────────────

    /** Mismo orden que el backend: primero la versión del texto legal, luego las entidades. */
    private fun validar(solicitud: SolicitudConsentimiento): RespuestaMock? {
        val finalidad = CATALOGO_FINALIDADES.firstOrNull { it.codigo == solicitud.finalidad }
        val validas = finalidad?.entidadesOrigen?.map { it.id }?.toSet().orEmpty()
        return when {
            solicitud.versionTexto != finalidad?.versionTexto -> fallo(422, "version_texto_desactualizada")
            solicitud.entidadesOrigen.isEmpty() || !validas.containsAll(solicitud.entidadesOrigen) ->
                fallo(422, "entidades_invalidas")
            else -> null
        }
    }

    private fun hayVigente(idCuenta: String, finalidad: CodigoFinalidad, ahora: Instant) =
        consentimientos.any {
            it.idCuenta == idCuenta &&
                it.finalidad == finalidad &&
                it.estado(ahora) == EstadoConsentimiento.VIGENTE
        }

    private fun buscar(idCuenta: String, idConsentimiento: String) =
        consentimientos.firstOrNull { it.id == idConsentimiento && it.idCuenta == idCuenta }

    @Suppress("ReturnCount")
    private fun revocarSegunEstado(con: ConsentimientoMock, ahora: Instant): RespuestaMock =
        when (con.estado(ahora)) {
            EstadoConsentimiento.VIGENTE -> { con.revocar(ahora); respuesta(200, con.dto(ahora)) }
            EstadoConsentimiento.REVOCADO -> fallo(409, "consentimiento_revocado")
            EstadoConsentimiento.VENCIDO -> fallo(409, "consentimiento_no_vigente")
        }

    @Suppress("ReturnCount")
    private fun renovarSegunEstado(con: ConsentimientoMock, idCuenta: String, ahora: Instant): RespuestaMock {
        if (con.estado(ahora) == EstadoConsentimiento.REVOCADO) return fallo(409, "consentimiento_revocado")
        if (con.estado(ahora) == EstadoConsentimiento.VENCIDO && hayVigente(idCuenta, con.finalidad, ahora)) {
            return fallo(409, "consentimiento_ya_vigente")
        }
        con.renovar(ahora)
        return respuesta(200, con.dto(ahora))
    }
}

/** Meses calendario, como el backend: el vencimiento no se aproxima con días fijos. */
private fun sumarMeses(desde: Instant, meses: Long): Instant =
    desde.atOffset(ZoneOffset.UTC).plusMonths(meses).toInstant()
