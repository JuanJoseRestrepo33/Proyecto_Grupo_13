package co.solventa.feature.siniestros.seguimiento

import co.solventa.core.network.Dinero
import co.solventa.core.network.EstadoSiniestro
import co.solventa.core.network.EventoSiniestro
import co.solventa.core.network.Requerimiento
import co.solventa.core.network.SiguientePaso
import co.solventa.core.network.Siniestro
import co.solventa.core.network.SiniestroDetalle
import co.solventa.core.network.TipoEventoSiniestro

const val ID_CASO = "SIN-42"
const val ID_REQ = "REQ-1"

fun siniestro(
    id: String = ID_CASO,
    estado: EstadoSiniestro = EstadoSiniestro.REPORTADO,
    reportadoEn: String = "2026-10-20T14:00:00Z",
    canal: String = "web",
    referencia: String = "SIN-2026-000042",
) = Siniestro(
    id = id,
    referencia = referencia,
    numeroPoliza = "POL-2026-001101",
    tipoEvento = TipoEventoSiniestro.DANO_HOGAR,
    ocurridoEl = "2026-10-19",
    descripcion = "Se rompió un tubo en la cocina",
    montoEstimado = Dinero.pesos(1_200_000),
    estado = estado,
    siguientePaso = SiguientePaso.ADJUNTAR_EVIDENCIA,
    canal = canal,
    reportadoEn = reportadoEn,
)

fun evento(estado: EstadoSiniestro, anterior: EstadoSiniestro?, en: String, motivo: String? = null, n: Int = 1) =
    EventoSiniestro("EVT-$n", anterior, estado, en, motivo)

fun pendiente(id: String = ID_REQ, descripcion: String = "Factura de la reparación") =
    Requerimiento(id, descripcion, "2026-10-21T09:00:00Z", pendiente = true, atendidoEn = null, idEvidencia = null)

fun atendido(id: String = ID_REQ, en: String = "2026-10-22T10:30:00Z") =
    Requerimiento(id, "Factura de la reparación", "2026-10-21T09:00:00Z", false, en, "EVI-1")

fun cerradoSinRespuesta(id: String = ID_REQ) =
    Requerimiento(id, "Factura de la reparación", "2026-10-21T09:00:00Z", pendiente = false, atendidoEn = null)

@Suppress("LongParameterList")
fun detalle(
    estado: EstadoSiniestro = EstadoSiniestro.REPORTADO,
    siguiente: SiguientePaso = SiguientePaso.ADJUNTAR_EVIDENCIA,
    requerimientos: List<Requerimiento> = emptyList(),
    historial: List<EventoSiniestro> = listOf(evento(EstadoSiniestro.REPORTADO, null, "2026-10-20T14:00:00Z")),
    monto: Dinero? = Dinero.pesos(1_200_000),
    actualizadoEn: String? = null,
) = SiniestroDetalle.de(
    siniestro(estado = estado)
        .copy(siguientePaso = siguiente, montoEstimado = monto, estadoActualizadoEn = actualizadoEn),
    historial,
    requerimientos,
)

/** Un caso en `requiere_informacion` con un requerimiento pendiente. */
fun requiereInformacion(requerimiento: Requerimiento = pendiente()) = detalle(
    estado = EstadoSiniestro.REQUIERE_INFORMACION,
    requerimientos = listOf(requerimiento),
    historial = listOf(
        evento(EstadoSiniestro.REPORTADO, null, "2026-10-20T14:00:00Z"),
        evento(
            EstadoSiniestro.REQUIERE_INFORMACION,
            EstadoSiniestro.REPORTADO,
            "2026-10-21T09:00:00Z",
            "Falta la factura",
            n = 2,
        ),
    ),
    actualizadoEn = "2026-10-21T09:00:00Z",
)
