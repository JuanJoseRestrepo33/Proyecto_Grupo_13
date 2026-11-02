package co.solventa.mobile.mock

import co.solventa.core.network.ErrorBff
import co.solventa.core.network.ErrorCampo
import co.solventa.core.network.EstadoPoliza
import co.solventa.core.network.EstadoSiniestro
import co.solventa.core.network.NetworkClient
import co.solventa.core.network.PolizaResumen
import co.solventa.core.network.SiguientePaso
import co.solventa.core.network.Siniestro
import co.solventa.core.network.SolicitudSiniestro
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Cliente sembrado, verificado y sin pólizas: «Reportar siniestro» explica que necesita una. */
const val CORREO_SIN_POLIZAS = "sinpolizas@ejemplo.co"

/**
 * Marca en la descripción para simular una respuesta perdida: el primer envío con esa clave queda
 * registrado, pero la app recibe un tiempo agotado; el reintento con la misma clave confirma la
 * misma referencia (criterio 4).
 */
const val MARCA_RESPUESTA_PERDIDA = "#sin-respuesta"

private val DESCRIPCION = 10..2000
private const val DIGITOS_CLIENTE = 3
private const val ENCABEZADO = "Idempotency-Key"

/** Plantillas de `POLIZAS_SEMILLA` en staging, con fechas relativas a hoy: cualquier cliente las tiene. */
private data class PlantillaPoliza(val sufijo: Int, val producto: String, val desdeDias: Long, val hastaDias: Long)

private val PLANTILLAS = listOf(
    PlantillaPoliza(101, "auto", desdeDias = -30, hastaDias = 335),
    PlantillaPoliza(102, "hogar", desdeDias = -395, hastaDias = -30),
)

/** [huella] es la solicitud ya limpia: la misma clave con otros datos es otro reporte. */
private class ReporteMock(val huella: SolicitudSiniestro, val siniestro: Siniestro)

/** Pólizas y siniestros: `GET /polizas` y `POST /siniestros`, con vigencia e idempotencia como el núcleo. */
internal class MockSiniestros(private val estado: EstadoMock) {
    private val reportes = mutableMapOf<Pair<String, String>, ReporteMock>()
    private val enviosPerdidos = mutableSetOf<String>()
    private var secuencia = 0

    fun polizas(autorizacion: String?): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        return respuesta(200, polizasDe(cuenta))
    }

    /** Mismo orden que el BFF y el núcleo: clave, datos, fecha futura, póliza, vigencia, idempotencia. */
    @Suppress("ReturnCount")
    fun reportar(autorizacion: String?, cuerpo: String, clave: String?): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        val claveValida = clave?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }
            ?: return fallo(422, "clave_idempotencia_invalida", listOf(ErrorCampo(ENCABEZADO, "formato_invalido")))
        val solicitud = NetworkClient.json
            .decodeFromString(SolicitudSiniestro.serializer(), cuerpo)
            .let { it.copy(descripcion = it.descripcion.trim()) }
        validar(solicitud)?.let { return it }
        val ocurrido = runCatching { LocalDate.parse(solicitud.ocurridoEl) }.getOrNull()
            ?: return fallo(422, "solicitud_invalida", listOf(ErrorCampo("ocurrido_el", "fecha_invalida")))
        if (ocurrido.isAfter(hoy())) {
            return fallo(422, "fecha_futura", listOf(ErrorCampo("ocurrido_el", "fecha_futura")))
        }
        val poliza = polizasDe(cuenta).firstOrNull { it.numero == solicitud.numeroPoliza }
            ?: return fallo(404, "poliza_no_encontrada")
        if (ocurrido.isBefore(LocalDate.parse(poliza.inicio)) || ocurrido.isAfter(LocalDate.parse(poliza.fin))) {
            val error = ErrorBff("evento_fuera_de_vigencia", "Mock BFF móvil: evento_fuera_de_vigencia")
            return respuesta(409, error.copy(vigenteDesde = poliza.inicio, vigenteHasta = poliza.fin))
        }
        val previo = reportes[cuenta.id to claveValida]
        return when {
            previo == null -> registrar(cuenta.id, claveValida, solicitud)
            previo.huella != solicitud -> fallo(409, "clave_idempotencia_en_uso")
            else -> respuesta(200, previo.siniestro)
        }
    }

    fun delCliente(idCuenta: String, idSiniestro: String): Siniestro? =
        reportes.entries.firstOrNull { (clave, reporte) ->
            clave.first == idCuenta && reporte.siniestro.id == idSiniestro
        }?.value?.siniestro

    private fun registrar(idCuenta: String, clave: String, solicitud: SolicitudSiniestro): RespuestaMock {
        secuencia += 1
        val ahora = estado.reloj()
        val siniestro = Siniestro(
            id = "SIN-${UUID.randomUUID()}",
            referencia = "SIN-${hoy().year}-${secuencia.toString().padStart(6, '0')}",
            numeroPoliza = solicitud.numeroPoliza,
            tipoEvento = solicitud.tipoEvento,
            ocurridoEl = solicitud.ocurridoEl,
            descripcion = solicitud.descripcion,
            montoEstimado = solicitud.montoEstimado,
            estado = EstadoSiniestro.REPORTADO,
            siguientePaso = SiguientePaso.ADJUNTAR_EVIDENCIA,
            canal = "movil",
            reportadoEn = ahora.toString(),
        )
        reportes[idCuenta to clave] = ReporteMock(solicitud, siniestro)
        val perder = MARCA_RESPUESTA_PERDIDA in solicitud.descripcion && enviosPerdidos.add(clave)
        return respuesta(201, siniestro).copy(perdida = perder)
    }

    private fun validar(solicitud: SolicitudSiniestro): RespuestaMock? {
        val campos = buildList {
            if (solicitud.numeroPoliza.isBlank()) add(ErrorCampo("numero_poliza", "requerido"))
            if (solicitud.descripcion.length !in DESCRIPCION) add(ErrorCampo("descripcion", "longitud_invalida"))
            if ((solicitud.montoEstimado?.valor ?: 0) < 0) add(ErrorCampo("monto_estimado.valor", "invalido"))
        }
        return if (campos.isEmpty()) null else fallo(422, "solicitud_invalida", campos)
    }

    private fun polizasDe(cuenta: CuentaMock): List<PolizaResumen> {
        if (cuenta.correo == CORREO_SIN_POLIZAS) return emptyList()
        val hoy = hoy()
        // Número propio por cliente, como las plantillas de staging: POL-2026-001101 para CLI-2026-000001.
        val cliente = cuenta.id.takeLast(DIGITOS_CLIENTE)
        return PLANTILLAS.map { plantilla ->
            val inicio = hoy.plusDays(plantilla.desdeDias)
            val fin = hoy.plusDays(plantilla.hastaDias)
            PolizaResumen(
                numero = "POL-2026-$cliente${plantilla.sufijo}",
                producto = plantilla.producto,
                inicio = inicio.toString(),
                fin = fin.toString(),
                estado = if (fin.isBefore(hoy)) EstadoPoliza.VENCIDA else EstadoPoliza.VIGENTE,
            )
        }
    }

    /** El núcleo compara con el día UTC. */
    private fun hoy(): LocalDate = estado.reloj().atZone(ZoneOffset.UTC).toLocalDate()
}
