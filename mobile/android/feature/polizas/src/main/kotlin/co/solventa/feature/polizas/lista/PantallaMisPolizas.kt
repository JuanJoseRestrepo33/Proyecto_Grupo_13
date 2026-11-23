package co.solventa.feature.polizas.lista

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.core.network.PolizaResumen
import co.solventa.feature.polizas.DependenciasPolizas
import co.solventa.feature.polizas.R
import co.solventa.feature.polizas.comun.BotonPrincipal
import co.solventa.feature.polizas.comun.Cargando
import co.solventa.feature.polizas.comun.ErrorConsulta
import co.solventa.feature.polizas.comun.FranjaBilletera
import co.solventa.feature.polizas.comun.LineaSincronizacion
import co.solventa.feature.polizas.comun.Mensaje
import co.solventa.feature.polizas.comun.RequiereConexion
import co.solventa.feature.polizas.comun.BotonSecundario
import co.solventa.feature.polizas.comun.franjaDe
import co.solventa.feature.polizas.comun.InsigniaEstado
import co.solventa.feature.polizas.comun.Pestana
import co.solventa.feature.polizas.comun.PlantillaPolizas
import co.solventa.feature.polizas.comun.etiqueta
import co.solventa.feature.polizas.comun.formatoDia
import co.solventa.feature.polizas.comun.formatoDinero
import co.solventa.feature.polizas.comun.nombrePoliza
import co.solventa.feature.polizas.comun.textoFallo
import co.solventa.feature.polizas.comun.textoPrima
import co.solventa.feature.polizas.comun.textoVigencia
import java.time.ZoneId

/**
 * «Mis pólizas» con su ViewModel, en la pestaña Pólizas de la barra inferior.
 *
 * @param entrada sube cada vez que el cliente selecciona la pestaña: la lista se vuelve a consultar
 * (política de refresco, §2). Una recomposición con el mismo valor no consulta.
 * @param onAbrir abre el detalle del número de la póliza.
 * @param onCredencial abre la credencial del número de la póliza (MOB-F03).
 */
@Composable
fun RutaMisPolizas(
    dependencias: DependenciasPolizas,
    entrada: Int,
    onAbrir: (String) -> Unit,
    onCredencial: (String) -> Unit = {},
    onSesionVencida: () -> Unit = {},
) {
    val viewModel = viewModel {
        MisPolizasViewModel(dependencias.api, dependencias.billetera, entrada, dependencias.reloj)
    }
    LaunchedEffect(entrada) { viewModel.alEntrar(entrada) }
    // Solo mientras la pestaña está en pantalla: sin red pasa a la billetera y al volver la red se sincroniza
    // sola (MOB-F03, §2).
    LaunchedEffect(viewModel) { viewModel.vigilarConexion() }
    val estado = viewModel.estado
    LaunchedEffect(estado.sesionVencida) {
        if (estado.sesionVencida) {
            viewModel.sesionAtendida()
            onSesionVencida()
        }
    }
    val navegador = LocalUriHandler.current
    PantallaMisPolizas(
        estado = estado,
        onActualizar = viewModel::actualizar,
        onElegir = viewModel::elegir,
        onAbrir = onAbrir,
        // Sin navegador instalado no hay a dónde llevarlo; el botón no hace nada en lugar de cerrar la app.
        onCotizar = { runCatching { navegador.openUri(dependencias.urlCotizacion) } },
        onCredencial = onCredencial,
        zona = dependencias.reloj.zone,
    )
}

/** Sin estado propio: las pruebas la dibujan con cualquier [estado]. */
@Composable
fun PantallaMisPolizas(
    estado: EstadoMisPolizas,
    onActualizar: () -> Unit,
    onElegir: (Pestana) -> Unit,
    onAbrir: (String) -> Unit,
    onCotizar: () -> Unit,
    onCredencial: (String) -> Unit = {},
    zona: ZoneId = ZoneId.systemDefault(),
) {
    val polizas = estado.mostradas
    val franja = estado.franja()
    PlantillaPolizas(
        titulo = stringResource(R.string.lista_titulo),
        onVolver = null,
        actualizando = estado.actualizando,
        onActualizar = onActualizar,
        modifier = Modifier.testTag("pantalla-mis-polizas"),
        franja = franja?.let { { FranjaBilletera(it, estado.billetera?.momento, zona, onReintentar = onActualizar) } },
    ) {
        val error = estado.error
        if (estado.muestraLineaSincronizacion) LineaSincronizacion(estado.billetera?.momento, estado.hoy, zona)
        when {
            estado.modo == ModoPolizas.SIN_BILLETERA -> SinBilletera(onActualizar)
            polizas == null && error != null -> ErrorConsulta(textoFallo(error, R.string.lista_error), onActualizar)
            polizas == null -> Cargando(stringResource(R.string.lista_cargando))
            polizas.isEmpty() -> {
                if (error != null) ErrorConsulta(textoFallo(error, R.string.lista_error), onActualizar)
                SinPolizas(onCotizar, conConexion = !estado.sinConexion)
            }
            else -> {
                if (error != null) ErrorConsulta(textoFallo(error, R.string.lista_error), onActualizar)
                Pestanas(estado.pestana, onElegir)
                val visibles = estado.visibles
                if (visibles.isEmpty()) {
                    Text(
                        stringResource(R.string.pestana_vacia),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("pestana-vacia"),
                    )
                }
                visibles.forEach { poliza ->
                    TarjetaPoliza(
                        poliza,
                        onAbrir,
                        onCredencial = { onCredencial(poliza.numero) }.takeIf { estado.ofreceCredencial(poliza) },
                    )
                }
            }
        }
    }
}

private fun EstadoMisPolizas.franja() = franjaDe(
    sinConexion = sinConexion,
    sinActualizar = modo == ModoPolizas.SIN_ACTUALIZAR,
    sincronizando = sincronizando,
)

/** Sin conexión y sin una sincronización completa guardada. */
@Composable
private fun SinBilletera(onReintentar: () -> Unit) {
    Mensaje(stringResource(R.string.sin_billetera), modifier = Modifier.testTag("sin-billetera"), esError = false)
    BotonSecundario(
        stringResource(R.string.reintentar),
        onClick = onReintentar,
        modifier = Modifier.testTag("reintentar"),
    )
}

@Composable
private fun Pestanas(elegida: Pestana, onElegir: (Pestana) -> Unit) {
    val descripcion = stringResource(R.string.pestanas_descripcion)
    TabRow(
        selectedTabIndex = elegida.ordinal,
        modifier = Modifier.semantics { contentDescription = descripcion },
    ) {
        Pestana.entries.forEach { pestana ->
            Tab(
                selected = pestana == elegida,
                onClick = { onElegir(pestana) },
                text = { Text(stringResource(pestana.texto())) },
                modifier = Modifier
                    .heightIn(min = SolventaSpacing.touchTarget)
                    .testTag("pestana-${pestana.name.lowercase()}"),
            )
        }
    }
}

private fun Pestana.texto() = when (this) {
    Pestana.ACTIVAS -> R.string.pestana_activas
    Pestana.VENCIDAS -> R.string.pestana_vencidas
    Pestana.TODAS -> R.string.pestana_todas
}

/**
 * El cliente aún no tiene pólizas: se le lleva a cotizar en la web, que es donde está el cotizador. Sin
 * conexión el botón queda inactivo y se explica por qué.
 */
@Composable
private fun SinPolizas(onCotizar: () -> Unit, conConexion: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SolventaSpacing.md),
        modifier = Modifier.fillMaxWidth().padding(vertical = SolventaSpacing.lg).testTag("sin-polizas"),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.List,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Text(
            stringResource(R.string.vacio_titulo),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.vacio_texto), style = MaterialTheme.typography.bodyLarge)
    }
    BotonPrincipal(
        stringResource(R.string.vacio_cotizar),
        onClick = onCotizar,
        habilitado = conConexion,
        modifier = Modifier.testTag("cotizar"),
    )
    if (!conConexion) RequiereConexion(stringResource(R.string.cotizar_requiere_conexion), "cotizar-requiere-conexion")
}

/**
 * Toda la tarjeta abre el detalle; TalkBack la lee como un solo control con todo su contenido. Con
 * [onCredencial], «Ver credencial» va como un control aparte.
 */
@Composable
private fun TarjetaPoliza(poliza: PolizaResumen, onAbrir: (String) -> Unit, onCredencial: (() -> Unit)?) {
    val nombre = nombrePoliza(poliza)
    val estado = stringResource(poliza.estado.etiqueta())
    val prima = textoPrima(poliza)
    val descripcion = stringResource(
        R.string.tarjeta_descripcion,
        nombre,
        estado,
        poliza.numero,
        formatoDia(poliza.inicio),
        formatoDia(poliza.fin),
        poliza.primaMensual?.let { stringResource(R.string.tarjeta_prima, formatoDinero(it)) }
            ?: stringResource(R.string.prima_no_disponible),
    )
    val abrir = stringResource(R.string.tarjeta_abrir)
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SolventaSpacing.touchTarget)
            .testTag("poliza-${poliza.numero}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = abrir) { onAbrir(poliza.numero) }
                .padding(SolventaSpacing.md)
                .clearAndSetSemantics { contentDescription = descripcion },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(SolventaSpacing.xs), modifier = Modifier.weight(1f)) {
                Text(nombre, style = MaterialTheme.typography.titleMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
                ) {
                    Text(poliza.numero, style = MaterialTheme.typography.bodyMedium)
                    InsigniaEstado(poliza.estado)
                }
                Text(textoVigencia(poliza), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (poliza.primaMensual != null) prima else stringResource(R.string.prima_no_disponible),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
        if (onCredencial != null) {
            val descripcionCredencial = stringResource(R.string.ver_credencial_descripcion, poliza.numero)
            OutlinedButton(
                onClick = onCredencial,
                modifier = Modifier
                    .padding(start = SolventaSpacing.md, end = SolventaSpacing.md, bottom = SolventaSpacing.md)
                    .heightIn(min = SolventaSpacing.touchTarget)
                    .testTag("credencial-${poliza.numero}")
                    .semantics { contentDescription = descripcionCredencial },
            ) { Text(stringResource(R.string.ver_credencial)) }
        }
    }
}
