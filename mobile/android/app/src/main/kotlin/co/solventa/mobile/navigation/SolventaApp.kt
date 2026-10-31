package co.solventa.mobile.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * @param dependenciasConsentimiento con ellas, Perfil da acceso al consentimiento (MOB-F02).
 * @param onSesionVencida flujo de sesión al que se delega un 401 del BFF.
 * @param avisoBiometriaDesactivada el token rotado no se pudo guardar: se avisa que la huella se
 * desactivó y [onAvisoAtendido] lo consume.
 */
@Composable
fun SolventaApp(
    identidadVerificada: Boolean = true,
    dependenciasConsentimiento: DependenciasConsentimiento? = null,
    onSesionVencida: () -> Unit = {},
    avisoBiometriaDesactivada: Boolean = false,
    onAvisoAtendido: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selected = TopLevelDestination.fromRoute(backStackEntry?.destination?.route)
    val avisos = remember { SnackbarHostState() }
    AvisoBiometriaDesactivada(avisos, avisoBiometriaDesactivada, onAvisoAtendido)
    Scaffold(
        snackbarHost = { SnackbarHost(avisos) },
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
                    val aviso = !identidadVerificada && destination == TopLevelDestination.HOME
                    val conConsentimiento = destination == TopLevelDestination.PROFILE &&
                        dependenciasConsentimiento != null
                    val alConsentimiento = { navController.navigate(RUTA_CONSENTIMIENTO) }.takeIf { conConsentimiento }
                    PlaceholderScreen(destination, avisoIdentidad = aviso, onConsentimiento = alConsentimiento)
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
    onConsentimiento: (() -> Unit)? = null,
) {
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
        // Con contenido real, el aviso de "próximamente" parecería referirse a él.
        if (onConsentimiento != null) {
            OutlinedButton(
                onClick = onConsentimiento,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SolventaSpacing.touchTarget)
                    .testTag("ir-consentimiento"),
            ) {
                Text(stringResource(R.string.perfil_consentimiento))
            }
        } else {
            Text(stringResource(R.string.placeholder), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
