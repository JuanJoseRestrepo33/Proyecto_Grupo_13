package co.solventa.mobile.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import co.solventa.feature.notificaciones.AvisoPermisoNotificaciones
import co.solventa.feature.notificaciones.DependenciasNotificaciones
import kotlinx.coroutines.flow.first

// Navegación de MOB-F06: la notificación tocada abre el detalle del siniestro y Inicio explica el permiso.

/**
 * Una notificación tocada abre el detalle de su siniestro (MOB-F06, criterio 2) y queda leída. Llega aquí
 * cuando hay sesión: si no la había, después de iniciarla. La lista no está en la pila: el detalle la abre
 * al volver.
 */
@Composable
internal fun AbrirDesdeNotificacion(
    dependencias: DependenciasNotificaciones?,
    abrirSiniestros: Boolean,
    navController: NavHostController,
) {
    val apertura = dependencias?.aperturas?.pendiente
    LaunchedEffect(apertura) {
        if (dependencias == null || apertura == null) return@LaunchedEffect
        val siniestro = apertura.idSiniestro
        if (siniestro != null && abrirSiniestros) {
            // El Scaffold compone el NavHost al medir: se espera a que el grafo tenga su primera entrada.
            navController.currentBackStackEntryFlow.first()
            navController.navigate(rutaDetalleSiniestro(siniestro)) { launchSingleTop = true }
        }
        dependencias.receptor.marcarLeida(apertura.idNotificacion)
        dependencias.aperturas.atendida(apertura)
    }
}

/** Sin permiso, el estado se sigue viendo en «Mis siniestros» (MOB-F06, P20, criterio 3). */
@Composable
internal fun AvisoPermisoEnInicio(
    destination: TopLevelDestination,
    dependencias: DependenciasNotificaciones?,
    conSiniestros: Boolean,
    navController: NavHostController,
) {
    if (destination != TopLevelDestination.HOME || dependencias == null) return
    AvisoPermisoNotificaciones(
        almacen = dependencias.almacen,
        onVerSiniestros = if (conSiniestros) {
            { navController.navigate(RUTA_MIS_SINIESTROS) }
        } else {
            null
        },
    )
}
