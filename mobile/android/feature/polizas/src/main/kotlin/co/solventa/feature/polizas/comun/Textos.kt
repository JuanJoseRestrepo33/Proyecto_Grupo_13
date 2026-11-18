package co.solventa.feature.polizas.comun

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import co.solventa.core.network.EstadoPoliza
import co.solventa.core.network.PolizaResumen
import co.solventa.feature.polizas.R

// Textos legibles de los valores del contrato: la interfaz nunca muestra un código ni un `null`.

fun EstadoPoliza.etiqueta() = when (this) {
    EstadoPoliza.VIGENTE -> R.string.estado_vigente
    EstadoPoliza.VENCIDA -> R.string.estado_vencida
    EstadoPoliza.POR_INICIAR -> R.string.estado_por_iniciar
}

/** Nombre comercial o, si el catálogo no lo tiene, el nombre genérico del producto (como la web). */
@Composable
internal fun nombrePoliza(poliza: PolizaResumen): String = poliza.nombreProducto?.takeIf(String::isNotBlank)
    ?: when (poliza.producto) {
        "auto" -> stringResource(R.string.producto_auto)
        "hogar" -> stringResource(R.string.producto_hogar)
        "viaje" -> stringResource(R.string.producto_viaje)
        "dispositivos" -> stringResource(R.string.producto_dispositivos)
        else -> stringResource(R.string.producto_otro, poliza.producto.replace('_', ' '))
    }

@Composable
internal fun textoVigencia(poliza: PolizaResumen): String =
    stringResource(R.string.vigencia_rango, formatoDia(poliza.inicio), formatoDia(poliza.fin))

/** «$185.000 / mes» o «No disponible». */
@Composable
internal fun textoPrima(poliza: PolizaResumen): String = poliza.primaMensual
    ?.let { stringResource(R.string.prima_por_mes, formatoDinero(it)) }
    ?: stringResource(R.string.no_disponible)
