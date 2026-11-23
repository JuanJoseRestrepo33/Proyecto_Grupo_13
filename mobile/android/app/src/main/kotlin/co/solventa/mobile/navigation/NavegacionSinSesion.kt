package co.solventa.mobile.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import co.solventa.core.network.Cliente
import co.solventa.feature.onboarding.DependenciasOnboarding
import co.solventa.feature.onboarding.biometria.DesbloqueoTelefono
import co.solventa.feature.onboarding.ingreso.RutaIngreso
import co.solventa.feature.polizas.DependenciasPolizas
import co.solventa.feature.polizas.sinsesion.RutaBilleteraSinSesion

// Entrada sin conexión desde Ingreso (MOB-F03, entrega 2, §7). Si Android cerró el proceso, la app vuelve
// a Ingreso, y el ingreso con huella necesita red: sin señal, el cliente no llegaría a su billetera.

/**
 * «Ver mis pólizas sin conexión», o null si no se ofrece. Se ofrece sin red, con una billetera guardada y
 * con bloqueo de pantalla: sin un desbloqueo local, cualquiera con el teléfono vería las pólizas. Al
 * tocarla se pide el desbloqueo y, solo si se supera, [onAbrir] abre la billetera sin sesión.
 */
@Composable
internal fun ofertaSinConexion(
    desbloqueo: DesbloqueoTelefono?,
    polizas: DependenciasPolizas?,
    onAbrir: () -> Unit,
): (() -> Unit)? {
    if (desbloqueo == null || polizas == null) return null
    val billetera = polizas.billetera
    val conectado by billetera.conectado.collectAsState()
    var disponible by remember { mutableStateOf(false) }
    LaunchedEffect(conectado) {
        disponible = !conectado && desbloqueo.disponible() && billetera.hayBilleteraSinSesion()
    }
    val abrir = {
        desbloqueo.desbloquear { superado ->
            if (superado && billetera.abrirSinSesion()) onAbrir()
        }
    }
    return abrir.takeIf { !conectado && disponible }
}

/**
 * Ingreso con la oferta de la billetera sin sesión y la ruta de esa billetera, que solo se ve con el
 * desbloqueo superado.
 */
internal fun NavGraphBuilder.entradaSinConexion(
    nav: NavHostController,
    dependencias: DependenciasOnboarding,
    polizas: DependenciasPolizas?,
    alIngresar: (Cliente) -> Unit,
    alIngreso: () -> Unit,
) {
    composable(RutasEntrada.INGRESO) {
        // Volver a Ingreso cierra la billetera sin sesión: la próxima entrada pide otra vez el desbloqueo.
        LaunchedEffect(Unit) { polizas?.billetera?.cerrarSinSesion() }
        RutaIngreso(
            dependencias,
            onIngresado = alIngresar,
            onCrearCuenta = { nav.navigate(RutasEntrada.REGISTRO) },
            onVerSinConexion = ofertaSinConexion(dependencias.desbloqueo, polizas) {
                nav.navigate(RutasEntrada.BILLETERA_SIN_SESION) { launchSingleTop = true }
            },
        )
    }
    if (polizas != null) {
        composable(RutasEntrada.BILLETERA_SIN_SESION) {
            SoloSi({ polizas.billetera.sinSesionDesbloqueada }, alNegar = alIngreso) {
                RutaBilleteraSinSesion(polizas, onIngresar = alIngreso)
            }
        }
    }
}

/**
 * Una ruta que solo tiene sentido con [permitida] (una sesión, o la billetera desbloqueada). Si Android
 * cerró el proceso, la navegación restaura la última ruta pero la sesión, que vive en memoria, ya no está:
 * en ese caso se vuelve a Ingreso sin mostrar nada. Se evalúa una vez al componer la ruta, para no
 * competir con la navegación del cierre de sesión.
 */
@Composable
internal fun SoloSi(permitida: () -> Boolean, alNegar: () -> Unit, contenido: @Composable () -> Unit) {
    val ok = remember { permitida() }
    if (ok) {
        contenido()
    } else {
        LaunchedEffect(Unit) { alNegar() }
    }
}
