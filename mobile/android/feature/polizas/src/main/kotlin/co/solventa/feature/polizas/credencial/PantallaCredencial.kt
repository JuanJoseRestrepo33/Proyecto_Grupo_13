package co.solventa.feature.polizas.credencial

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.core.network.PolizaDetalle
import co.solventa.feature.polizas.DependenciasPolizas
import co.solventa.feature.polizas.R
import co.solventa.feature.polizas.comun.BotonSecundario
import co.solventa.feature.polizas.comun.Cargando
import co.solventa.feature.polizas.comun.Franja
import co.solventa.feature.polizas.comun.FranjaBilletera
import co.solventa.feature.polizas.comun.InsigniaEstado
import co.solventa.feature.polizas.comun.Mensaje
import co.solventa.feature.polizas.comun.PlantillaPolizas
import co.solventa.feature.polizas.comun.etiqueta
import co.solventa.feature.polizas.comun.formatoDiaSincronizacion
import co.solventa.feature.polizas.comun.formatoSincronizacionCompleta
import co.solventa.feature.polizas.comun.nombrePoliza
import co.solventa.feature.polizas.comun.textoVigencia
import java.time.ZoneId

/**
 * Credencial con su ViewModel. El número llega por la ruta `policies/credencial/{numero}`, anidada bajo
 * Pólizas para que la barra inferior siga marcándola.
 *
 * @param onVolver regresa a la pantalla anterior (la lista o el detalle).
 */
@Composable
fun RutaCredencial(
    dependencias: DependenciasPolizas,
    onVolver: () -> Unit,
    onSesionVencida: () -> Unit = {},
) {
    val viewModel = viewModel {
        CredencialViewModel(createSavedStateHandle(), dependencias.billetera, dependencias.reloj)
    }
    LaunchedEffect(viewModel) { viewModel.vigilarConexion() }
    val estado = viewModel.estado
    LaunchedEffect(estado.sesionVencida) {
        if (estado.sesionVencida) {
            viewModel.sesionAtendida()
            onSesionVencida()
        }
    }
    BackHandler(onBack = onVolver)
    PantallaCredencial(
        estado,
        onVolver,
        onReintentar = viewModel::cargar,
        zona = dependencias.reloj.zone,
        onReintentarSincronizacion = viewModel::alReconectar,
    )
}

/**
 * Sin estado propio: las pruebas la dibujan con cualquier [estado]. Sin `FLAG_SECURE`: el cliente la
 * muestra para demostrar su cobertura y puede necesitar una captura para enviarla.
 */
@Composable
fun PantallaCredencial(
    estado: EstadoCredencial,
    onVolver: () -> Unit,
    onReintentar: () -> Unit,
    zona: ZoneId = ZoneId.systemDefault(),
    onReintentarSincronizacion: () -> Unit = onReintentar,
) {
    val franja = when {
        estado.sincronizando -> Franja.SINCRONIZANDO
        estado.sinConexion -> Franja.SOLO_SIN_CONEXION
        estado.sinActualizar -> Franja.SIN_ACTUALIZAR
        else -> null
    }
    PlantillaPolizas(
        titulo = stringResource(R.string.credencial_titulo),
        onVolver = onVolver,
        modifier = Modifier.testTag("pantalla-credencial"),
        franja = franja?.let {
            { FranjaBilletera(it, estado.sincronizadaEn, zona, onReintentar = onReintentarSincronizacion) }
        },
    ) {
        val detalle = estado.detalle
        when {
            estado.cargando && detalle == null -> Cargando(stringResource(R.string.credencial_cargando))
            detalle == null || estado.motivo != null -> SinCredencial(estado.motivo, onReintentar, onVolver)
            else -> Credencial(estado, detalle, zona)
        }
    }
}

@Composable
private fun SinCredencial(motivo: SinCredencial?, onReintentar: () -> Unit, onVolver: () -> Unit) {
    val texto = when (motivo) {
        SinCredencial.NO_GUARDADA -> R.string.credencial_no_guardada
        SinCredencial.NO_VIGENTE -> R.string.credencial_no_vigente
        SinCredencial.NECESITA_CONEXION, null -> R.string.credencial_necesita_conexion
    }
    Mensaje(stringResource(texto), modifier = Modifier.testTag("sin-credencial"), esError = false)
    if (motivo == SinCredencial.NECESITA_CONEXION) {
        BotonSecundario(
            stringResource(R.string.reintentar),
            onClick = onReintentar,
            modifier = Modifier.testTag("reintentar"),
        )
    }
    BotonSecundario(
        stringResource(R.string.volver),
        onClick = onVolver,
        modifier = Modifier.testTag("volver-credencial"),
    )
}

/**
 * Lleva todo lo que un tercero necesita para contrastarla con la aseguradora, sin otra pantalla: titular,
 * producto, número (seleccionable para copiarlo), vigencia, estado con su fecha, coberturas con su
 * límite, valor asegurado, deducible y la fecha de sincronización. Lo que falta se ve «No disponible».
 */
@Composable
private fun Credencial(estado: EstadoCredencial, detalle: PolizaDetalle, zona: ZoneId) {
    val noDisponible = stringResource(R.string.no_disponible)
    val sincronizada = estado.sincronizadaEn
    OutlinedCard(
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().testTag("credencial"),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
            modifier = Modifier.padding(SolventaSpacing.md),
        ) {
            Text(
                stringResource(R.string.credencial_encabezado).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                nombrePoliza(detalle.resumen),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("credencial-producto").semantics { heading() },
            )
            Fila(stringResource(R.string.credencial_numero), "credencial-numero") {
                // Seleccionable: el cliente puede copiarlo para enviarlo.
                SelectionContainer {
                    Text(detalle.numero, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            FilaTexto(
                stringResource(R.string.credencial_titular),
                estado.titular?.takeIf(String::isNotBlank) ?: noDisponible,
                "credencial-titular",
            )
            FilaTexto(
                stringResource(R.string.credencial_vigencia),
                textoVigencia(detalle.resumen),
                "credencial-vigencia",
            )
            EstadoSegunSincronizacion(detalle, sincronizada?.let { formatoDiaSincronizacion(it, zona) } ?: noDisponible)
        }
    }
    Condiciones(detalle, noDisponible)
    Text(
        stringResource(
            R.string.credencial_pie,
            sincronizada?.let { formatoSincronizacionCompleta(it, zona) } ?: noDisponible,
        ),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("credencial-pie"),
    )
    Text(stringResource(R.string.credencial_contraste), style = MaterialTheme.typography.bodySmall)
}

/** Coberturas con su límite, valor asegurado y deducible. */
@Composable
private fun Condiciones(detalle: PolizaDetalle, noDisponible: String) {
    Text(
        stringResource(R.string.detalle_coberturas),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    if (detalle.coberturas.isEmpty()) {
        Text(
            stringResource(R.string.detalle_sin_coberturas),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("credencial-sin-coberturas"),
        )
    }
    detalle.coberturas.forEachIndexed { indice, cobertura ->
        FilaTexto(
            cobertura.nombre,
            cobertura.limite.takeIf(String::isNotBlank) ?: noDisponible,
            "credencial-cobertura-$indice",
        )
    }
    FilaTexto(
        stringResource(R.string.detalle_valor_asegurado),
        detalle.valorAsegurado?.takeIf(String::isNotBlank) ?: noDisponible,
        "credencial-valor-asegurado",
    )
    FilaTexto(
        stringResource(R.string.detalle_deducible),
        detalle.deducible?.takeIf(String::isNotBlank) ?: noDisponible,
        "credencial-deducible",
    )
}

/** «● Vigente según datos del 18 nov 2026»: la insignia con texto, nunca solo el color. */
@Composable
private fun EstadoSegunSincronizacion(detalle: PolizaDetalle, dia: String) {
    val nombre = stringResource(detalle.estado.etiqueta())
    val descripcion = stringResource(R.string.credencial_estado_descripcion, nombre, dia)
    Row(
        horizontalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
        modifier = Modifier.testTag("credencial-estado").clearAndSetSemantics { contentDescription = descripcion },
    ) {
        InsigniaEstado(detalle.estado)
        Text(
            stringResource(R.string.credencial_segun_datos, dia),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun FilaTexto(etiqueta: String, valor: String, testTag: String) {
    val descripcion = stringResource(R.string.fila_descripcion, etiqueta, valor)
    Row(
        horizontalArrangement = Arrangement.spacedBy(SolventaSpacing.md),
        modifier = Modifier.fillMaxWidth().testTag(testTag).clearAndSetSemantics { contentDescription = descripcion },
    ) {
        Text(etiqueta, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            valor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Fila(etiqueta: String, testTag: String, valor: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().testTag(testTag)) {
        Text(etiqueta, style = MaterialTheme.typography.bodyMedium)
        valor()
    }
}
