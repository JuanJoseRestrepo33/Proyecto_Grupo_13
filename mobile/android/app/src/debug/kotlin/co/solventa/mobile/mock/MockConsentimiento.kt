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

private val VIGENCIA_COTIZACION: Duration = Duration.ofDays(180)
private val VIGENCIA_SUSCRIPCION: Duration = Duration.ofDays(365)
private val VIGENCIA_FRAUDE: Duration = Duration.ofDays(365)
private val UMBRAL_POR_VENCER: Duration = Duration.ofDays(30)

private val ENTIDADES_COTIZACION = listOf(
    EntidadConsentimiento("bancolombia", "Bancolombia"),
    EntidadConsentimiento("davivienda", "Davivienda"),
    EntidadConsentimiento("nequi", "Nequi"),
)

private val ENTIDADES_SUSCRIPCION = listOf(
    EntidadConsentimiento("bancolombia", "Bancolombia"),
    EntidadConsentimiento("davivienda", "Davivienda"),
)

private val ENTIDADES_FRAUDE = listOf(
    EntidadConsentimiento("bancolombia", "Bancolombia"),
    EntidadConsentimiento("davivienda", "Davivienda"),
    EntidadConsentimiento("nequi", "Nequi"),
)

internal val CATALOGO_FINALIDADES = listOf(
    FinalidadConsentimiento(
        codigo = CodigoFinalidad.COTIZACION,
        nombre = "Cotización personalizada",
        descripcion = "Permite calcular primas con tu perfil financiero real para obtener precios más precisos.",
        alcance = listOf("saldos_cuenta", "movimientos_6_meses", "score_crediticio"),
        vigenciaMeses = 6,
        entidadesOrigen = ENTIDADES_COTIZACION,
        versionTexto = "cotizacion-2026.1",
    ),
    FinalidadConsentimiento(
        codigo = CodigoFinalidad.SUSCRIPCION,
        nombre = "Suscripción de póliza",
        descripcion = "Permite verificar solvencia financiera para aprobar la emisión de tu póliza.",
        alcance = listOf("saldos_cuenta", "historial_pagos"),
        vigenciaMeses = 12,
        entidadesOrigen = ENTIDADES_SUSCRIPCION,
        versionTexto = "suscripcion-2026.1",
    ),
    FinalidadConsentimiento(
        codigo = CodigoFinalidad.PREVENCION_FRAUDE,
        nombre = "Prevención de fraude",
        descripcion = "Permite detectar patrones inusuales para proteger tu cuenta y pólizas.",
        alcance = listOf("movimientos_recientes", "ubicaciones"),
        vigenciaMeses = 12,
        entidadesOrigen = ENTIDADES_FRAUDE,
        versionTexto = "fraude-2026.1",
    ),
)

internal class ConsentimientoMock(
    val id: String,
    val idCuenta: String,
    val finalidad: CodigoFinalidad,
    val entidades: List<EntidadConsentimiento>,
    val versionTexto: String,
    otorgadoEn: Instant,
    vigencia: Duration,
) {
    val otorgadoEn: Instant = otorgadoEn
    var venceEn: Instant = otorgadoEn + vigencia
    var revocadoEn: Instant? = null
    private var renovadoEn: Instant? = null

    fun estado(ahora: Instant): EstadoConsentimiento = when {
        revocadoEn != null -> EstadoConsentimiento.REVOCADO
        ahora.isAfter(venceEn) -> EstadoConsentimiento.VENCIDO
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

    fun dto(ahora: Instant): Consentimiento = Consentimiento(
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

    fun revocar(ahora: Instant) {
        revocadoEn = ahora
    }

    fun renovar(ahora: Instant, vigencia: Duration) {
        revocadoEn = null
        venceEn = ahora + vigencia
        renovadoEn = ahora
    }
}

internal class MockConsentimiento(private val estado: EstadoMock) {

    private val consentimientos = mutableListOf<ConsentimientoMock>()

    private fun vigenciaPara(finalidad: CodigoFinalidad): Duration = when (finalidad) {
        CodigoFinalidad.COTIZACION -> VIGENCIA_COTIZACION
        CodigoFinalidad.SUSCRIPCION -> VIGENCIA_SUSCRIPCION
        CodigoFinalidad.PREVENCION_FRAUDE -> VIGENCIA_FRAUDE
    }

    private fun versiones() = CATALOGO_FINALIDADES.associate { it.codigo to it.versionTexto }

    fun finalidades(): RespuestaMock = respuesta(200, CATALOGO_FINALIDADES)

    fun listar(autorizacion: String?): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        val ahora = estado.reloj()
        val lista = consentimientos
            .filter { it.idCuenta == cuenta.id }
            .map { it.dto(ahora) }
        return respuesta(200, lista)
    }

    fun otorgar(autorizacion: String?, cuerpo: String): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        val solicitud = NetworkClient.json.decodeFromString(
            SolicitudConsentimiento.serializer(),
            cuerpo,
        )
        val ahora = estado.reloj()
        val versiones = versiones()
        val versionEsperada = versiones[solicitud.finalidad]

        if (solicitud.versionTexto != versionEsperada) {
            return fallo(422, "version_texto_desactualizada")
        }

        val todasValidas = solicitud.entidadesOrigen.all { id ->
            CATALOGO_FINALIDADES.firstOrNull { it.codigo == solicitud.finalidad }
                ?.entidadesOrigen?.any { it.id == id } == true
        }
        if (!todasValidas) return fallo(422, "entidades_invalidas")

        // Verificar si ya existe uno vigente
        val yaVigente = consentimientos.any {
            it.idCuenta == cuenta.id &&
                it.finalidad == solicitud.finalidad &&
                it.estado(ahora) == EstadoConsentimiento.VIGENTE
        }
        if (yaVigente) return fallo(409, "consentimiento_ya_vigente")

        val finalidad = CATALOGO_FINALIDADES.first { it.codigo == solicitud.finalidad }
        val entidadesFiltradas = finalidad.entidadesOrigen.filter { it.id in solicitud.entidadesOrigen }

        val nuevo = ConsentimientoMock(
            id = estado.siguiente("CON"),
            idCuenta = cuenta.id,
            finalidad = solicitud.finalidad,
            entidades = entidadesFiltradas,
            versionTexto = solicitud.versionTexto,
            otorgadoEn = ahora,
            vigencia = vigenciaPara(solicitud.finalidad),
        )
        consentimientos += nuevo
        return respuesta(201, nuevo.dto(ahora))
    }

    fun revocar(autorizacion: String?, idConsentimiento: String): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        val ahora = estado.reloj()
        val con = consentimientos.firstOrNull { it.id == idConsentimiento && it.idCuenta == cuenta.id }
            ?: return fallo(404, "consentimiento_no_encontrado")
        return when (con.estado(ahora)) {
            EstadoConsentimiento.VIGENTE -> {
                con.revocar(ahora)
                respuesta(200, con.dto(ahora))
            }
            EstadoConsentimiento.REVOCADO -> fallo(409, "consentimiento_revocado")
            EstadoConsentimiento.VENCIDO -> fallo(409, "consentimiento_no_vigente")
        }
    }

    fun renovar(autorizacion: String?, idConsentimiento: String): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        val ahora = estado.reloj()
        val con = consentimientos.firstOrNull { it.id == idConsentimiento && it.idCuenta == cuenta.id }
            ?: return fallo(404, "consentimiento_no_encontrado")

        return when (con.estado(ahora)) {
            EstadoConsentimiento.REVOCADO -> fallo(409, "consentimiento_revocado")
            EstadoConsentimiento.VIGENTE, EstadoConsentimiento.VENCIDO -> {
                // Si vencido, verificar que no haya otro vigente para la misma finalidad
                if (con.estado(ahora) == EstadoConsentimiento.VENCIDO) {
                    val yaVigente = consentimientos.any {
                        it.idCuenta == cuenta.id &&
                            it.finalidad == con.finalidad &&
                            it.id != con.id &&
                            it.estado(ahora) == EstadoConsentimiento.VIGENTE
                    }
                    if (yaVigente) return fallo(409, "consentimiento_ya_vigente")
                }
                con.renovar(ahora, vigenciaPara(con.finalidad))
                respuesta(200, con.dto(ahora))
            }
        }
    }
}
