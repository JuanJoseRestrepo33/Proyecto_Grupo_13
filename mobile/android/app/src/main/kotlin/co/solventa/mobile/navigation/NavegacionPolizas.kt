package co.solventa.mobile.navigation

import android.net.Uri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import co.solventa.feature.polizas.DependenciasPolizas
import co.solventa.feature.polizas.detalle.DetallePolizaViewModel
import co.solventa.feature.polizas.detalle.RutaDetallePoliza

// Navegación de WEB-F04. «Mis pólizas» es la pestaña Pólizas (`policies`); el detalle va anidado bajo ella.

/** El detalle de la póliza, anidado bajo Pólizas para que la barra siga marcándola. */
internal const val RUTA_DETALLE_POLIZA = "policies/detalle/{${DetallePolizaViewModel.ARGUMENTO_NUMERO}}"

internal fun rutaDetallePoliza(numero: String) = "policies/detalle/${Uri.encode(numero)}"

/** Sin [dependencias] no hay detalle de pólizas, como sin dependencias no hay lista. */
internal fun NavGraphBuilder.detallePoliza(
    dependencias: DependenciasPolizas?,
    navController: NavHostController,
    onSesionVencida: () -> Unit,
) {
    if (dependencias == null) return
    composable(
        RUTA_DETALLE_POLIZA,
        arguments = listOf(navArgument(DetallePolizaViewModel.ARGUMENTO_NUMERO) { type = NavType.StringType }),
    ) {
        RutaDetallePoliza(
            dependencias = dependencias,
            onVolver = { volverAMisPolizas(navController) },
            onSesionVencida = onSesionVencida,
        )
    }
}

/** Del detalle se vuelve a «Mis pólizas»; si se llegó directo y no está en la pila, se abre. */
private fun volverAMisPolizas(navController: NavHostController) {
    if (!navController.popBackStack(TopLevelDestination.POLICIES.route, inclusive = false)) {
        navController.navigate(TopLevelDestination.POLICIES.route) {
            popUpTo(RUTA_DETALLE_POLIZA) { inclusive = true }
        }
    }
}
