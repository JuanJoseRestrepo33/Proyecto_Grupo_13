package co.solventa.mobile.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.feature.consentimiento.DependenciasConsentimiento
import co.solventa.feature.consentimiento.RutaConsentimiento
import co.solventa.mobile.R

/**
 * @param identidadVerificada false mientras KYC no apruebe: Inicio lo avisa y las funciones que
 * requieren identidad verificada quedan fuera de alcance (MOB-F01, criterio 1).
 * @param dependenciasConsentimiento dependencias para el flujo MOB-F02.
 */
@Composable
fun SolventaApp(
    identidadVerificada: Boolean = true,
    dependenciasConsentimiento: DependenciasConsentimiento? = null,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selected = TopLevelDestination.fromRoute(backStackEntry?.destination?.route)
    Scaffold(
        bottomBar = {
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
        },
    ) { padding ->
        NavHost(navController, startDestination = TopLevelDestination.HOME.route, Modifier.padding(padding)) {
            TopLevelDestination.entries.forEach { destination ->
                composable(destination.route) {
                    when {
                        destination == TopLevelDestination.PROFILE && dependenciasConsentimiento != null -> {
                            RutaConsentimiento(
                                api = dependenciasConsentimiento.api,
                                onVolver = {
                                    navController.navigate(TopLevelDestination.PROFILE.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                            )
                        }
                        else -> {
                            val aviso = !identidadVerificada && destination == TopLevelDestination.HOME
                            PlaceholderScreen(destination, avisoIdentidad = aviso)
                        }
                    }
                }
            }
        }
    }
}

private fun TopLevelDestination.icon() = when (this) {
    TopLevelDestination.HOME -> Icons.Default.Home
    TopLevelDestination.POLICIES -> Icons.AutoMirrored.Filled.List
    TopLevelDestination.NOTICES -> Icons.Default.Notifications
    TopLevelDestination.PROFILE -> Icons.Default.Person
}

@Composable
private fun PlaceholderScreen(destination: TopLevelDestination, avisoIdentidad: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(SolventaSpacing.md).testTag("screen-${destination.route}"),
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
        Text(stringResource(R.string.placeholder), style = MaterialTheme.typography.bodyLarge)
    }
}
