package co.solventa.feature.polizas.comun

import co.solventa.core.network.Dinero
import co.solventa.core.network.EstadoPoliza
import co.solventa.core.network.PolizaResumen
import co.solventa.core.network.Resultado
import java.text.NumberFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Reglas de la consulta de pólizas (WEB-F04) que no dependen de la interfaz: se prueban sin Compose.

/** Por qué no se pudo consultar: sin red se dice distinto que un fallo del BFF. */
enum class FalloConsulta { SIN_RED, GENERICO }

internal const val HTTP_NO_AUTENTICADO = 401
internal const val HTTP_NO_ENCONTRADO = 404

/** Fallo recuperable de una consulta: sin red, tiempo agotado, 5xx o una respuesta ilegible. */
internal fun Resultado<*>.fallo(): FalloConsulta? = when (this) {
    is Resultado.Exito -> null
    Resultado.SinConexion -> FalloConsulta.SIN_RED
    is Resultado.Falla -> FalloConsulta.GENERICO
}

/** Pestañas de «Mis pólizas», como en la web: una póliza por iniciar solo se ve en Todas. */
enum class Pestana(private val estado: EstadoPoliza?) {
    ACTIVAS(EstadoPoliza.VIGENTE),
    VENCIDAS(EstadoPoliza.VENCIDA),
    TODAS(null),
    ;

    fun incluye(poliza: PolizaResumen): Boolean = estado == null || poliza.estado == estado
}

/** Color de la insignia; siempre va con el texto del estado, nunca solo el color (P12). */
enum class TonoInsignia { VERDE, GRIS, INDIGO }

val EstadoPoliza.tono: TonoInsignia
    get() = when (this) {
        EstadoPoliza.VIGENTE -> TonoInsignia.VERDE
        EstadoPoliza.VENCIDA -> TonoInsignia.GRIS
        EstadoPoliza.POR_INICIAR -> TonoInsignia.INDIGO
    }

private val COLOMBIA: Locale = Locale.forLanguageTag("es-CO")
private val DIA: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", COLOMBIA)

/** Un día del contrato (`2026-10-15`) como «15 oct. 2026»; si no se puede leer, tal cual. */
fun formatoDia(iso: String): String = runCatching { DIA.format(LocalDate.parse(iso)) }.getOrDefault(iso)

/** El día de una fecha y hora del contrato en la zona del dispositivo; si no se puede leer, tal cual. */
fun formatoDiaDe(iso: String, zona: ZoneId = ZoneId.systemDefault()): String =
    runCatching { DIA.format(OffsetDateTime.parse(iso).atZoneSameInstant(zona)) }.getOrDefault(iso)

/** «$185.000» en pesos; otra moneda va con su código («USD 120»). */
fun formatoDinero(dinero: Dinero): String {
    val cifra = NumberFormat.getIntegerInstance(COLOMBIA).format(dinero.valor)
    return if (dinero.moneda == "COP") "$$cifra" else "${dinero.moneda} $cifra"
}
