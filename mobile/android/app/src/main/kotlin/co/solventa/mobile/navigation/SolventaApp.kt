package co.solventa.mobile.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.List
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.mobile.R

@Composable
fun SolventaApp() {
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
                composable(destination.route) { PlaceholderScreen(destination) }
            }
        }
    }
}

private fun TopLevelDestination.icon() = when (this) {
    TopLevelDestination.HOME -> Icons.Default.Home
    TopLevelDestination.POLICIES -> Icons.Default.List
    TopLevelDestination.NOTICES -> Icons.Default.Notifications
    TopLevelDestination.PROFILE -> Icons.Default.Person
}

@Composable
private fun PlaceholderScreen(destination: TopLevelDestination) {
    Column(
        modifier = Modifier.fillMaxSize().padding(SolventaSpacing.md),
        verticalArrangement = Arrangement.spacedBy(SolventaSpacing.sm),
    ) {
        Text(stringResource(destination.label), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.placeholder), style = MaterialTheme.typography.bodyLarge)
    }
}
