package co.solventa.mobile.navigation

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.feature.consentimiento.DependenciasConsentimiento
import co.solventa.feature.consentimiento.RutaConsentimiento
import co.solventa.feature.siniestros.DependenciasSiniestros
import co.solventa.feature.siniestros.borrador.AccionesTarjeta
import co.solventa.feature.siniestros.borrador.Tarjeta
import co.solventa.feature.siniestros.borrador.TarjetasBorradores
import co.solventa.feature.siniestros.borrador.bloqueanReporteNuevo
import co.solventa.feature.siniestros.reporte.RutaReporteSiniestro
import co.solventa.feature.siniestros.seguimiento.DetalleSiniestroViewModel
import co.solventa.feature.siniestros.seguimiento.RutaDetalleSiniestro
import co.solventa.feature.siniestros.seguimiento.RutaMisSiniestros
import co.solventa.mobile.R

/**
 * @param identidadVerificada false mientras KYC no apruebe: Inicio lo avisa y las funciones que
 * requieren identidad verificada quedan fuera de alcance (MOB-F01, criterio 1).
 * @param dependenciasConsentimiento con ellas, Perfil da acceso al consentimiento (MOB-F02).
 * @param dependenciasSiniestros con ellas, Inicio da acceso al reporte de siniestros (WEB-F05).
 * @param onSesionVencida flujo de sesión al que se delega un 401 del BFF.
 * @param avisoBiometriaDesactivada el token rotado no se pudo guardar: se avisa que la huella se
 * desactivó y [onAvisoAtendido] lo consume.
 * @param onCerrarSesion con él, Perfil ofrece cerrar sesión; si hay borradores sin enviar, avisa (M13).
 * @param navController las pruebas lo pasan para abrir una ruta directamente, como hará el push (#204).
 */
@Suppress("LongMethod")
@Composable
fun SolventaApp(
    identidadVerificada: Boolean = true,
    dependenciasConsentimiento: DependenciasConsentimiento? = null,
    dependenciasSiniestros: DependenciasSiniestros? = null,
    onSesionVencida: () -> Unit = {},
    avisoBiometriaDesactivada: Boolean = false,
    onAvisoAtendido: () -> Unit = {},
    onCerrarSesion: (() -> Unit)? = null,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selected = TopLevelDestination.fromRoute(backStackEntry?.destination?.route)
    val avisos = remember { SnackbarHostState() }
    AvisoBiometriaDesactivada(avisos, avisoBiometriaDesactivada, onAvisoAtendido)
    Scaffold(
        snackbarHost = { SnackbarHost(avisos) },
        bottomBar = { BarraInferior(selected, navController) },
    ) { padding ->
        NavHost(navController, startDestination = TopLevelDestination.HOME.route, Modifier.padding(padding)) {
            TopLevelDestination.entries.forEach { destination ->
                composable(destination.route) {
                    val aviso = !identidadVerificada && destination == TopLevelDestination.HOME
                    // Mientras un reporte quizá ya quedó registrado, no se ofrece otro (MOB-F05-02, criterio 8).
                    val tarjetas = dependenciasSiniestros?.enviador?.tarjetas?.collectAsState()?.value.orEmpty()
                    val sinConfirmar = tarjetas.bloqueanReporteNuevo()
                    val accion = accionDe(
                        destination,
                        conConsentimiento = dependenciasConsentimiento != null,
                        conSiniestros = dependenciasSiniestros != null,
                        navegar = { ruta -> navController.navigate(ruta) },
                        reporteBloqueado = sinConfirmar,
                    )
                    PlaceholderScreen(destination, avisoIdentidad = aviso, accion = accion) {
                        if (destination == TopLevelDestination.HOME && dependenciasSiniestros != null) {
                            // WEB-F06: el seguimiento, junto a «Reportar siniestro».
                            OutlinedButton(
                                onClick = { navController.navigate(RUTA_MIS_SINIESTROS) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = SolventaSpacing.touchTarget)
                                    .testTag("ir-mis-siniestros"),
                            ) { Text(stringResource(R.string.inicio_mis_siniestros)) }
                            TarjetasBorradores(
                                dependenciasSiniestros.enviador,
                                AccionesTarjeta(
                                    onRevisar = { id -> navController.navigate(rutaCorreccion(id)) },
                                    onIngresar = onSesionVencida,
                                    onReportar = {
                                        if (!sinConfirmar) navController.navigate(RUTA_REPORTE_SINIESTRO)
                                    },
                                ),
                            )
                        }
                        if (destination == TopLevelDestination.PROFILE && onCerrarSesion != null) {
                            CerrarSesion(dependenciasSiniestros, onCerrarSesion)
                        }
                    }
                }
            }
            if (dependenciasConsentimiento != null) {
                composable(RUTA_CONSENTIMIENTO) {
                    RutaConsentimiento(
                        api = dependenciasConsentimiento.api,
                        onVolver = { navController.popBackStack() },
                        onSesionVencida = onSesionVencida,
                    )
                }
            }
            if (dependenciasSiniestros != null) {
                composable(RUTA_REPORTE_SINIESTRO) {
                    RutaReporteSiniestro(
                        dependencias = dependenciasSiniestros,
                        onTerminar = { navController.popBackStack() },
                        onSesionVencida = onSesionVencida,
                    )
                }
                composable(RUTA_MIS_SINIESTROS) {
                    val tarjetas = dependenciasSiniestros.enviador.tarjetas.collectAsState().value
                    val bloqueado = tarjetas.bloqueanReporteNuevo()
                    RutaMisSiniestros(
                        dependencias = dependenciasSiniestros,
                        reporteBloqueado = bloqueado,
                        onAbrir = { id -> navController.navigate(rutaDetalleSiniestro(id)) },
                        onReportar = { if (!bloqueado) navController.navigate(RUTA_REPORTE_SINIESTRO) },
                        onVolver = { navController.popBackStack() },
                        onSesionVencida = onSesionVencida,
                    )
                }
                composable(
                    RUTA_DETALLE_SINIESTRO,
                    arguments = listOf(
                        navArgument(DetalleSiniestroViewModel.ARGUMENTO_ID) { type = NavType.StringType },
                    ),
                ) {
                    RutaDetalleSiniestro(
                        dependencias = dependenciasSiniestros,
                        onVolver = { volverAMisSiniestros(navController) },
                        onSesionVencida = onSesionVencida,
                    )
                }
                composable(
                    RUTA_CORRECCION,
                    arguments = listOf(navArgument(ARGUMENTO_BORRADOR) { type = NavType.StringType }),
                ) { entrada ->
                    RutaReporteSiniestro(
                        dependencias = dependenciasSiniestros,
                        onTerminar = { navController.popBackStack() },
                        onSesionVencida = onSesionVencida,
                        idCorregir = entrada.arguments?.getString(ARGUMENTO_BORRADOR),
                    )
                }
            }
        }
    }
}

@Composable
private fun BarraInferior(selected: TopLevelDestination, navController: NavHostController) {
    NavigationBar {
        TopLevelDestination.entries.forEach { destination ->
            NavigationBarItem(
                modifier = Modifier.sizeIn(
                    minWidth = SolventaSpacing.touchTarget,
                    minHeight = SolventaSpacing.touchTarget,
                ),
                selected = selected == destination,
                onClick = {
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(destination.icon(), contentDescription = null) },
                label = { Text(stringResource(destination.label)) },
            )
        }
    }
}

/** Se consume al cerrarlo: consumirlo antes cambiaría la clave del efecto y cancelaría el propio aviso. */
@Composable
private fun AvisoBiometriaDesactivada(avisos: SnackbarHostState, activo: Boolean, onAtendido: () -> Unit) {
    val texto = stringResource(R.string.aviso_biometria_desactivada)
    val entendido = stringResource(R.string.aviso_entendido)
    LaunchedEffect(activo) {
        if (activo) {
            avisos.showSnackbar(texto, actionLabel = entendido, duration = SnackbarDuration.Indefinite)
            onAtendido()
        }
    }
}

/** Anidada bajo Perfil: la barra inferior sigue marcando Perfil. */
internal const val RUTA_CONSENTIMIENTO = "profile/consentimiento"

/** Anidada bajo Inicio, como la entrada «Reportar siniestro». */
internal const val RUTA_REPORTE_SINIESTRO = "home/siniestros/reporte"

/** M9 «Revisar»: el asistente sobre un borrador rechazado. */
private const val ARGUMENTO_BORRADOR = "borrador"
internal const val RUTA_CORRECCION = "home/siniestros/borradores/{$ARGUMENTO_BORRADOR}"

internal fun rutaCorreccion(id: String) = "home/siniestros/borradores/$id"

/** WEB-F06: «Mis siniestros», anidada bajo Inicio. */
internal const val RUTA_MIS_SINIESTROS = "home/siniestros"

/**
 * El detalle lleva un segmento propio (`detalle`) para no chocar con `home/siniestros/reporte` ni con
 * `home/siniestros/borradores/{borrador}`. El push de MOB-F06 (#204) abrirá esta misma ruta.
 */
internal const val RUTA_DETALLE_SINIESTRO = "home/siniestros/detalle/{${DetalleSiniestroViewModel.ARGUMENTO_ID}}"

internal fun rutaDetalleSiniestro(id: String) = "home/siniestros/detalle/${Uri.encode(id)}"

/**
 * Del detalle se vuelve a la lista. Si se llegó directo (p. ej. desde un push) y la lista no está en
 * la pila, se abre en lugar del detalle.
 */
private fun volverAMisSiniestros(navController: NavHostController) {
    if (!navController.popBackStack(RUTA_MIS_SINIESTROS, inclusive = false)) {
        navController.navigate(RUTA_MIS_SINIESTROS) {
            popUpTo(RUTA_DETALLE_SINIESTRO) { inclusive = true }
        }
    }
}

/** Perfil: cerrar sesión. Con borradores sin enviar se avisa que siguen guardados para esta cuenta (M13). */
@Composable
private fun CerrarSesion(siniestros: DependenciasSiniestros?, onCerrar: () -> Unit) {
    var confirmando by rememberSaveable { mutableStateOf(false) }
    val pendientes = siniestros?.enviador?.tarjetas?.collectAsState()?.value.orEmpty().count { it !is Tarjeta.Enviada }
    OutlinedButton(
        onClick = { if (pendientes > 0) confirmando = true else onCerrar() },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SolventaSpacing.touchTarget)
            .testTag("cerrar-sesion"),
    ) { Text(stringResource(R.string.perfil_cerrar_sesion)) }
    if (confirmando) {
        AlertDialog(
            onDismissRequest = { confirmando = false },
            title = { Text(pluralStringResource(R.plurals.cerrar_pendientes_titulo, pendientes, pendientes)) },
            text = { Text(stringResource(R.string.cerrar_pendientes_texto)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmando = false
                        onCerrar()
                    },
                    modifier = Modifier.heightIn(min = SolventaSpacing.touchTarget).testTag("confirmar-cerrar-sesion"),
                ) { Text(stringResource(R.string.perfil_cerrar_sesion)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmando = false },
                    modifier = Modifier.heightIn(min = SolventaSpacing.touchTarget),
                ) { Text(stringResource(R.string.cerrar_cancelar)) }
            },
        )
    }
}

/**
 * Botón con contenido real en un destino que, por lo demás, aún es un placeholder. Sin [habilitada],
 * [motivo] explica por qué.
 */
private class AccionDestino(
    @StringRes val texto: Int,
    val etiqueta: String,
    val habilitada: Boolean = true,
    @StringRes val motivo: Int? = null,
    val onClick: () -> Unit,
)

/** Perfil abre el consentimiento (MOB-F02) e Inicio el reporte de siniestros (WEB-F05). */
private fun accionDe(
    destination: TopLevelDestination,
    conConsentimiento: Boolean,
    conSiniestros: Boolean,
    navegar: (String) -> Unit,
    reporteBloqueado: Boolean = false,
): AccionDestino? = when {
    destination == TopLevelDestination.PROFILE && conConsentimiento ->
        AccionDestino(R.string.perfil_consentimiento, "ir-consentimiento") { navegar(RUTA_CONSENTIMIENTO) }
    destination == TopLevelDestination.HOME && conSiniestros -> AccionDestino(
        R.string.inicio_reportar_siniestro,
        "ir-reportar-siniestro",
        habilitada = !reporteBloqueado,
        motivo = R.string.inicio_reporte_por_confirmar.takeIf { reporteBloqueado },
    ) { navegar(RUTA_REPORTE_SINIESTRO) }
    else -> null
}

private fun TopLevelDestination.icon() = when (this) {
    TopLevelDestination.HOME -> Icons.Default.Home
    TopLevelDestination.POLICIES -> Icons.AutoMirrored.Filled.List
    TopLevelDestination.NOTICES -> Icons.Default.Notifications
    TopLevelDestination.PROFILE -> Icons.Default.Person
}

@Composable
private fun PlaceholderScreen(
    destination: TopLevelDestination,
    avisoIdentidad: Boolean,
    accion: AccionDestino? = null,
    contenido: @Composable () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SolventaSpacing.md)
            .testTag("screen-${destination.route}"),
        verticalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
    ) {
        Text(stringResource(destination.label), style = MaterialTheme.typography.headlineSmall)
        if (avisoIdentidad) {
            Text(
                stringResource(R.string.aviso_identidad_pendiente),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("aviso-identidad"),
            )
        }
        // Con contenido real, el aviso de "próximamente" parecería referirse a él.
        if (accion != null) {
            OutlinedButton(
                onClick = accion.onClick,
                enabled = accion.habilitada,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SolventaSpacing.touchTarget)
                    .testTag(accion.etiqueta),
            ) {
                Text(stringResource(accion.texto))
            }
            accion.motivo?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("reporte-bloqueado"),
                )
            }
        } else {
            Text(stringResource(R.string.placeholder), style = MaterialTheme.typography.bodyLarge)
        }
        contenido()
    }
}
