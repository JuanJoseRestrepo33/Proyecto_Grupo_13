package co.solventa.mobile.mock

import co.solventa.core.network.Dinero
import co.solventa.core.network.EstadoSiniestro
import co.solventa.core.network.EventoSiniestro
import co.solventa.core.network.Requerimiento
import co.solventa.core.network.SiguientePaso
import co.solventa.core.network.SiniestroDetalle
import co.solventa.core.network.TipoEventoSiniestro

// Seguimiento (WEB-F06): casos sembrados en la cuenta demo (CORREO_DEMO), con id, referencia, fechas e
// historial fijos para que las pruebas no dependan del reloj. La cuenta CORREO_SIN_POLIZAS no tiene
// siniestros. Las referencias empiezan en 900 para no chocar con las de los reportes de la sesión.

const val SINIESTRO_REPORTADO = "SIN-demo-reportado"
const val SINIESTRO_EN_EVALUACION = "SIN-demo-en-evaluacion"
const val SINIESTRO_REQUIERE_INFORMACION = "SIN-demo-requiere-informacion"
const val SINIESTRO_APROBADO = "SIN-demo-aprobado"
const val SINIESTRO_RECHAZADO = "SIN-demo-rechazado"
const val SINIESTRO_PAGADO = "SIN-demo-pagado"

/** Un `id` que no existe: el detalle responde 404 `siniestro_no_encontrado`. */
const val SINIESTRO_INEXISTENTE = "SIN-demo-inexistente"

/** Requerimiento pendiente de [SINIESTRO_REQUIERE_INFORMACION]. */
const val REQUERIMIENTO_PENDIENTE = "REQ-demo-factura"

/** Requerimiento atendido de [SINIESTRO_APROBADO]. */
const val REQUERIMIENTO_ATENDIDO = "REQ-demo-fotos"

/** Requerimiento cerrado sin respuesta de [SINIESTRO_RECHAZADO]. */
const val REQUERIMIENTO_CERRADO = "REQ-demo-denuncia"

private const val MOTIVO_RECHAZO = "El evento no está cubierto por la póliza."

/** Los seis estados del caso y las variantes de requerimiento, sobre las pólizas de [numeroPoliza]. */
@Suppress("LongMethod")
internal fun siniestrosSembrados(numeroPoliza: String): List<SiniestroDetalle> {
    fun caso(
        id: String,
        referencia: Int,
        tipo: TipoEventoSiniestro,
        estado: EstadoSiniestro,
        siguiente: SiguientePaso,
        reportadoEn: String,
        historial: List<EventoSiniestro>,
        requerimientos: List<Requerimiento> = emptyList(),
        monto: Dinero? = Dinero.pesos(1_500_000),
        canal: String = "web",
    ) = SiniestroDetalle(
        id = id,
        referencia = "SIN-2026-${referencia.toString().padStart(6, '0')}",
        numeroPoliza = numeroPoliza,
        tipoEvento = tipo,
        ocurridoEl = reportadoEn.substring(0, 10),
        descripcion = "Caso de demostración: ${estado.name.lowercase().replace('_', ' ')}.",
        montoEstimado = monto,
        estado = estado,
        siguientePaso = siguiente,
        canal = canal,
        reportadoEn = reportadoEn,
        estadoActualizadoEn = historial.drop(1).lastOrNull()?.ocurridoEn,
        historial = historial,
        requerimientos = requerimientos,
    )

    fun reporte(id: String, en: String) = EventoSiniestro("EVT-$id-1", null, EstadoSiniestro.REPORTADO, en, null)
    fun cambio(id: String, n: Int, de: EstadoSiniestro, a: EstadoSiniestro, en: String, motivo: String? = null) =
        EventoSiniestro("EVT-$id-$n", de, a, en, motivo)

    val r = EstadoSiniestro.REPORTADO
    val ev = EstadoSiniestro.EN_EVALUACION
    val ri = EstadoSiniestro.REQUIERE_INFORMACION
    val ap = EstadoSiniestro.APROBADO
    return listOf(
        // Sin monto y sin cambios de estado.
        caso(
            SINIESTRO_REPORTADO, 901, TipoEventoSiniestro.HURTO, r, SiguientePaso.ADJUNTAR_EVIDENCIA,
            "2026-10-20T14:00:00Z",
            listOf(reporte("901", "2026-10-20T14:00:00Z")),
            monto = null,
        ),
        caso(
            SINIESTRO_EN_EVALUACION, 902, TipoEventoSiniestro.ACCIDENTE_VEHICULO, ev,
            SiguientePaso.ESPERAR_EVALUACION, "2026-10-15T09:30:00Z",
            listOf(reporte("902", "2026-10-15T09:30:00Z"), cambio("902", 2, r, ev, "2026-10-16T10:00:00Z")),
            canal = "movil",
        ),
        caso(
            SINIESTRO_REQUIERE_INFORMACION, 903, TipoEventoSiniestro.DANO_HOGAR, ri,
            SiguientePaso.ADJUNTAR_EVIDENCIA, "2026-10-10T08:00:00Z",
            listOf(
                reporte("903", "2026-10-10T08:00:00Z"),
                cambio("903", 2, r, ev, "2026-10-11T09:00:00Z"),
                cambio("903", 3, ev, ri, "2026-10-12T11:15:00Z", "Necesitamos la factura de la reparación."),
            ),
            listOf(
                Requerimiento(
                    REQUERIMIENTO_PENDIENTE, "Factura de la reparación", "2026-10-12T11:15:00Z",
                    pendiente = true, atendidoEn = null, idEvidencia = null,
                ),
            ),
        ),
        // Un requerimiento atendido; «Requiere información» aparece dos veces en el historial.
        caso(
            SINIESTRO_APROBADO, 904, TipoEventoSiniestro.EQUIPO_DANADO_O_PERDIDO, ap,
            SiguientePaso.ESPERAR_EVALUACION, "2026-09-28T16:00:00Z",
            listOf(
                reporte("904", "2026-09-28T16:00:00Z"),
                cambio("904", 2, r, ri, "2026-09-29T10:00:00Z", "Faltan fotos del equipo."),
                cambio("904", 3, ri, ev, "2026-09-30T12:00:00Z"),
                cambio("904", 4, ev, ri, "2026-10-01T09:00:00Z", "Las fotos no se ven bien."),
                cambio("904", 5, ri, ev, "2026-10-02T15:00:00Z"),
                cambio("904", 6, ev, ap, "2026-10-05T17:30:00Z"),
            ),
            listOf(
                Requerimiento(
                    REQUERIMIENTO_ATENDIDO, "Fotos del equipo dañado", "2026-09-29T10:00:00Z",
                    pendiente = false, atendidoEn = "2026-09-30T11:45:00Z", idEvidencia = "EVI-demo-1",
                ),
            ),
            canal = "movil",
        ),
        // Un requerimiento cerrado sin respuesta: el caso salió de `requiere_informacion` al rechazarse.
        caso(
            SINIESTRO_RECHAZADO, 905, TipoEventoSiniestro.RETRASO_VUELO, EstadoSiniestro.RECHAZADO,
            SiguientePaso.ESPERAR_EVALUACION, "2026-09-20T07:00:00Z",
            listOf(
                reporte("905", "2026-09-20T07:00:00Z"),
                cambio("905", 2, r, ri, "2026-09-21T08:00:00Z", "Necesitamos el certificado de la aerolínea."),
                cambio("905", 3, ri, EstadoSiniestro.RECHAZADO, "2026-09-25T18:00:00Z", MOTIVO_RECHAZO),
            ),
            listOf(
                Requerimiento(
                    REQUERIMIENTO_CERRADO, "Certificado de la aerolínea", "2026-09-21T08:00:00Z",
                    pendiente = false, atendidoEn = null, idEvidencia = null,
                ),
            ),
        ),
        caso(
            SINIESTRO_PAGADO, 906, TipoEventoSiniestro.CANCELACION_VIAJE, EstadoSiniestro.PAGADO,
            SiguientePaso.ESPERAR_EVALUACION, "2026-09-01T12:00:00Z",
            listOf(
                reporte("906", "2026-09-01T12:00:00Z"),
                cambio("906", 2, r, ev, "2026-09-02T09:00:00Z"),
                cambio("906", 3, ev, ap, "2026-09-05T10:00:00Z"),
                cambio("906", 4, ap, EstadoSiniestro.PAGADO, "2026-09-10T15:00:00Z"),
            ),
        ),
    )
}
