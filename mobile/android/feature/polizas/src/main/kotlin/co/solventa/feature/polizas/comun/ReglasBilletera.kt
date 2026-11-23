package co.solventa.feature.polizas.comun

import co.solventa.core.network.EstadoPoliza
import co.solventa.core.network.PolizaResumen
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Reglas de la billetera sin conexión (MOB-F03) que no dependen de la interfaz: se prueban sin Compose.

private val COLOMBIA: Locale = Locale.forLanguageTag("es-CO")
private val DIA: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", COLOMBIA)
private val DIA_Y_HORA_CORTO: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", COLOMBIA)
private val DIA_Y_HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", COLOMBIA)
private val HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", COLOMBIA)

/**
 * Estado que se muestra sin conexión según la última sincronización (§5). Con conexión manda el `estado`
 * del BFF. La app no puede saber sin conexión si la aseguradora canceló o modificó la póliza; lo que sí
 * hace es no seguir mostrando como vigente una póliza cuyas fechas ya pasaron. `fin` es el último día de
 * cobertura, incluido. Si las fechas no se pueden leer, queda el estado guardado.
 */
fun estadoSinConexion(poliza: PolizaResumen, hoy: LocalDate): EstadoPoliza {
    val inicio = runCatching { LocalDate.parse(poliza.inicio) }.getOrNull()
    val fin = runCatching { LocalDate.parse(poliza.fin) }.getOrNull()
    return when {
        inicio == null || fin == null -> poliza.estado
        hoy.isBefore(inicio) -> EstadoPoliza.POR_INICIAR
        hoy.isAfter(fin) -> EstadoPoliza.VENCIDA
        poliza.estado == EstadoPoliza.VENCIDA -> EstadoPoliza.VENCIDA
        // Vigente, o una por iniciar que ya empezó.
        else -> EstadoPoliza.VIGENTE
    }
}

/** La póliza con el estado que se muestra sin conexión. */
fun PolizaResumen.sinConexion(hoy: LocalDate): PolizaResumen = copy(estado = estadoSinConexion(this, hoy))

/** «18 nov., 08:14»: la franja de sin conexión. */
fun formatoSincronizacion(momento: Instant, zona: ZoneId): String = DIA_Y_HORA_CORTO.format(momento.atZone(zona))

/** «18 nov. 2026, 08:14»: el pie de la credencial. */
fun formatoSincronizacionCompleta(momento: Instant, zona: ZoneId): String = DIA_Y_HORA.format(momento.atZone(zona))

/** «18 nov. 2026»: «Vigente según datos del …». */
fun formatoDiaSincronizacion(momento: Instant, zona: ZoneId): String = DIA.format(momento.atZone(zona))

/** «08:14». */
fun formatoHora(momento: Instant, zona: ZoneId): String = HORA.format(momento.atZone(zona))

fun esHoy(momento: Instant, hoy: LocalDate, zona: ZoneId): Boolean = momento.atZone(zona).toLocalDate() == hoy
