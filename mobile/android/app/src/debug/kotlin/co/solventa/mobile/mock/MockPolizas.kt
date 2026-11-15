package co.solventa.mobile.mock

import co.solventa.core.network.Cobertura
import co.solventa.core.network.Dinero
import co.solventa.core.network.EstadoPoliza
import co.solventa.core.network.PolizaDetalle
import co.solventa.core.network.PolizaResumen
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Número que no es de nadie: el detalle y el documento responden 404 `poliza_no_encontrada`. */
const val POLIZA_INEXISTENTE = "POL-2026-999999"

private const val DIGITOS_CLIENTE = 3

/** Hora de emisión de las pólizas sembradas: mediodía en Colombia. */
private const val HORA_EMISION = "T17:00:00Z"

/**
 * Plantilla de una póliza sembrada, con fechas relativas a hoy. Sin [condiciones] es una póliza como las
 * de la semilla de staging: sin nombre comercial, prima, deducible, valor asegurado, emisión,
 * coberturas ni documento.
 */
private data class PlantillaPoliza(
    val sufijo: Int,
    val producto: String,
    val desdeDias: Long,
    val hastaDias: Long,
    val condiciones: Condiciones? = null,
)

private data class Condiciones(
    val nombre: String,
    val prima: Long,
    val coberturas: List<Cobertura>,
    val deducible: String,
    val valorAsegurado: String,
    /** Días antes del inicio en que se emitió. */
    val emitidaAntesDias: Long,
    val conDocumento: Boolean = true,
)

/**
 * Pólizas de cada cliente, en el orden en que las devuelve el mock. La 101 y la 102 son las que usa el
 * asistente de reporte desde WEB-F05: conservan número, producto y vigencia, y la 101 sigue primero
 * (las pruebas del reporte y de la evidencia toman la primera de la lista).
 */
private val PLANTILLAS = listOf(
    // Vigente, completa y con documento.
    PlantillaPoliza(
        101,
        "auto",
        desdeDias = -30,
        hastaDias = 335,
        Condiciones(
            nombre = "Seguro de Auto — Plan Full",
            prima = 185_000,
            coberturas = listOf(
                Cobertura("Responsabilidad civil", "$1.000.000.000"),
                Cobertura("Pérdida total", "100 % del valor asegurado"),
                Cobertura("Asistencia en viaje", "Incluida"),
            ),
            deducible = "5% del valor asegurado",
            valorAsegurado = "$60.000.000",
            emitidaAntesDias = 1,
        ),
    ),
    // Por iniciar: solo aparece en «Todas».
    PlantillaPoliza(
        103,
        "viaje",
        desdeDias = 10,
        hastaDias = 40,
        Condiciones(
            nombre = "Seguro de Viaje — Plan Internacional",
            prima = 45_000,
            coberturas = listOf(
                Cobertura("Gastos médicos", "USD 60.000"),
                Cobertura("Cancelación del viaje", "$3.000.000"),
            ),
            deducible = "Sin deducible",
            valorAsegurado = "$8.000.000",
            emitidaAntesDias = 12,
        ),
    ),
    // Vigente y sin datos, como las de la semilla de staging.
    PlantillaPoliza(104, "hogar", desdeDias = -60, hastaDias = 305),
    // Vencida, con condiciones pero sin documento.
    PlantillaPoliza(
        102,
        "hogar",
        desdeDias = -395,
        hastaDias = -30,
        Condiciones(
            nombre = "Seguro de Hogar — Plan Básico",
            prima = 62_000,
            coberturas = listOf(Cobertura("Incendio", "$250.000.000"), Cobertura("Hurto de contenidos", "$20.000.000")),
            deducible = "10% de la pérdida",
            valorAsegurado = "$250.000.000",
            emitidaAntesDias = 2,
            conDocumento = false,
        ),
    ),
)

/**
 * `GET /polizas`, `GET /polizas/{numero}` y `GET /polizas/{numero}/documento` (WEB-F04). Cada cliente
 * tiene su propio número por póliza (POL-2026-001101 para CLI-2026-000001), como en staging: la de otro
 * cliente responde 404, igual que una que no existe.
 */
internal class MockPolizas(private val estado: EstadoMock) {
    fun listar(autorizacion: String?): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        return respuesta(200, polizasDe(cuenta))
    }

    fun detalle(autorizacion: String?, numero: String): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        return detallesDe(cuenta).firstOrNull { it.numero == numero }?.let { respuesta(200, it) }
            ?: fallo(404, "poliza_no_encontrada")
    }

    /** El PDF, como adjunto; sin documento, 404 `documento_no_disponible`. */
    fun documento(autorizacion: String?, numero: String): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        val poliza = detallesDe(cuenta).firstOrNull { it.numero == numero }
        return when {
            poliza == null -> fallo(404, "poliza_no_encontrada")
            poliza.documentoUrl == null -> fallo(404, "documento_no_disponible")
            else -> RespuestaMock(
                codigo = 200,
                cuerpo = pdfDePrueba(numero),
                encabezados = mapOf("Content-Disposition" to "attachment; filename=\"$numero.pdf\""),
                tipo = "application/pdf",
            )
        }
    }

    /** Las del asistente de reporte: el resumen de cada una. */
    fun polizasDe(cuenta: CuentaMock): List<PolizaResumen> = detallesDe(cuenta).map { it.resumen }

    private fun detallesDe(cuenta: CuentaMock): List<PolizaDetalle> {
        if (cuenta.correo == CORREO_SIN_POLIZAS) return emptyList()
        val hoy = hoy()
        val cliente = cuenta.id.takeLast(DIGITOS_CLIENTE)
        return PLANTILLAS.map { plantilla ->
            val inicio = hoy.plusDays(plantilla.desdeDias)
            val fin = hoy.plusDays(plantilla.hastaDias)
            val numero = "POL-2026-$cliente${plantilla.sufijo}"
            val condiciones = plantilla.condiciones
            val resumen = PolizaResumen(
                numero = numero,
                producto = plantilla.producto,
                inicio = inicio.toString(),
                fin = fin.toString(),
                estado = when {
                    fin.isBefore(hoy) -> EstadoPoliza.VENCIDA
                    inicio.isAfter(hoy) -> EstadoPoliza.POR_INICIAR
                    else -> EstadoPoliza.VIGENTE
                },
                nombreProducto = condiciones?.nombre,
                primaMensual = condiciones?.let { Dinero.pesos(it.prima) },
                documentoUrl = "/api/v1/movil/polizas/$numero/documento".takeIf { condiciones?.conDocumento == true },
            )
            PolizaDetalle.de(
                resumen,
                coberturas = condiciones?.coberturas.orEmpty(),
                deducible = condiciones?.deducible,
                valorAsegurado = condiciones?.valorAsegurado,
                emitidaEn = condiciones?.let { "${inicio.minusDays(it.emitidaAntesDias)}$HORA_EMISION" },
            )
        }
    }

    /** El núcleo compara con el día UTC. */
    private fun hoy(): LocalDate = estado.reloj().atZone(ZoneOffset.UTC).toLocalDate()
}

/** Un PDF de una página, válido para cualquier visor, con el número de la póliza. Solo ASCII. */
internal fun pdfDePrueba(numero: String): String {
    val texto = "BT /F1 18 Tf 72 720 Td (Solventa - poliza $numero - documento de prueba) Tj ET"
    val objetos = listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R " +
            "/Resources << /Font << /F1 5 0 R >> >> >>",
        "<< /Length ${texto.length} >>\nstream\n$texto\nendstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    )
    val pdf = StringBuilder("%PDF-1.4\n")
    val posiciones = objetos.mapIndexed { indice, objeto ->
        pdf.length.also { pdf.append("${indice + 1} 0 obj\n$objeto\nendobj\n") }
    }
    val xref = pdf.length
    pdf.append("xref\n0 ${objetos.size + 1}\n0000000000 65535 f \n")
    posiciones.forEach { pdf.append(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
    pdf.append("trailer\n<< /Size ${objetos.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return pdf.toString()
}
