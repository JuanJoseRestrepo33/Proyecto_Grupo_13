package co.solventa.feature.polizas.sinsesion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.feature.polizas.DependenciasPolizas
import co.solventa.feature.polizas.R
import co.solventa.feature.polizas.comun.Franja
import co.solventa.feature.polizas.comun.FranjaBilletera
import co.solventa.feature.polizas.credencial.PantallaCredencial
import co.solventa.feature.polizas.detalle.PantallaDetallePoliza
import co.solventa.feature.polizas.lista.PantallaMisPolizas
import java.time.Instant
import java.time.ZoneId

/** Qué pantalla de la billetera sin sesión se ve: la lista, un detalle o una credencial. */
private const val LISTA = ""
private const val DETALLE = "detalle:"
private const val CREDENCIAL = "credencial:"

/**
 * La billetera sin sesión (MOB-F03, entrega 2): Mis pólizas, detalle guardado y credencial, en solo lectura
 * y sin la barra inferior. Arriba, la franja con la fecha de sincronización y «Ingresar»; cuando vuelve la
 * conexión, «Ya tienes conexión · Ingresa para actualizar». La app la abre solo después del desbloqueo del
 * teléfono. La pila de pantallas es propia (sin `NavHost`): Atrás vuelve de la credencial o el detalle a la
 * lista y, desde la lista, a Ingreso.
 *
 * @param onIngresar vuelve a Ingreso; también lo hace Atrás desde la lista.
 */
@Composable
fun RutaBilleteraSinSesion(dependencias: DependenciasPolizas, onIngresar: () -> Unit) {
    val viewModel = viewModel { BilleteraSinSesionViewModel(dependencias.billetera, dependencias.reloj) }
    var pila by rememberSaveable { mutableStateOf(listOf(LISTA)) }
    val actual = pila.last()
    val volver = { if (pila.size > 1) pila = pila.dropLast(1) else onIngresar() }
    val abrir = { pantalla: String -> pila = pila + pantalla }
    BackHandler(onBack = volver)
    val estado = viewModel.estado
    val zona = dependencias.reloj.zone
    MarcoSinSesion(estado.conectado, estado.billetera?.momento, zona, onIngresar) {
        when {
            actual.startsWith(DETALLE) -> {
                val numero = actual.removePrefix(DETALLE)
                PantallaDetallePoliza(
                    estado = viewModel.detalle(numero),
                    onVolver = volver,
                    onReintentar = {},
                    onDescargar = {},
                    onAvisoMostrado = {},
                    onCredencial = { abrir(CREDENCIAL + numero) },
                    zona = zona,
                    soloLectura = true,
                )
            }
            actual.startsWith(CREDENCIAL) -> PantallaCredencial(
                estado = viewModel.credencial(actual.removePrefix(CREDENCIAL)),
                onVolver = volver,
                onReintentar = {},
                zona = zona,
                soloLectura = true,
            )
            else -> PantallaMisPolizas(
                estado = viewModel.lista(),
                onActualizar = {},
                onElegir = viewModel::elegir,
                onAbrir = { abrir(DETALLE + it) },
                onCotizar = {},
                onCredencial = { abrir(CREDENCIAL + it) },
                zona = zona,
                soloLectura = true,
            )
        }
    }
}

/**
 * Franja fija arriba y «Ingresar». Sin conexión: «Sin conexión · datos del …» y el botón aparte; con
 * conexión, la franja «Ya tienes conexión · Ingresa para actualizar» lleva el botón.
 */
@Composable
internal fun MarcoSinSesion(
    conectado: Boolean,
    sincronizadaEn: Instant?,
    zona: ZoneId,
    onIngresar: () -> Unit,
    contenido: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("billetera-sin-sesion")) {
        if (conectado) {
            FranjaBilletera(Franja.YA_CONECTADO, sincronizadaEn, zona, onReintentar = onIngresar)
        } else {
            FranjaBilletera(Franja.SIN_CONEXION, sincronizadaEn, zona)
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(horizontal = SolventaSpacing.sm),
            ) {
                TextButton(
                    onClick = onIngresar,
                    modifier = Modifier.heightIn(min = SolventaSpacing.touchTarget).testTag("sin-sesion-ingresar"),
                ) { Text(stringResource(R.string.sin_sesion_ingresar)) }
            }
        }
        Box(Modifier.weight(1f)) { contenido() }
    }
}
