package co.solventa.mobile.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import co.solventa.core.network.Cliente
import co.solventa.core.network.EstadoIdentidad
import co.solventa.feature.consentimiento.DependenciasConsentimiento
import co.solventa.feature.onboarding.DependenciasOnboarding
import co.solventa.feature.onboarding.biometria.RutaOfertaBiometria
import co.solventa.feature.onboarding.biometria.debeOfrecerBiometria
import co.solventa.feature.onboarding.identidad.RutaIdentidad
import co.solventa.feature.onboarding.ingreso.RutaIngreso
import co.solventa.feature.onboarding.otp.RutaOtp
import co.solventa.feature.onboarding.registro.RutaRegistro
import java.time.Instant
import java.time.OffsetDateTime

@Composable
fun SolventaRaiz(
    dependencias: DependenciasOnboarding,
    dependenciasConsentimiento: DependenciasConsentimiento? = null,
) {
    val nav = rememberNavController()
    val sesion = dependencias.sesion
    // Vencida la sesión (el cliente del BFF no pudo renovarla), se vacía la pila protegida y se vuelve
    // a Ingreso, que ofrece la huella si sigue activa (WEB-F13).
    LaunchedEffect(sesion.vencida) {
        if (sesion.vencida) {
            sesion.vencimientoAtendido()
            nav.reiniciarEn(RutasEntrada.INGRESO)
        }
    }
    val alIngresar = { cliente: Cliente -> nav.reiniciarEn(destinoTrasIngreso(cliente, dependencias)) }
    val alAprobar = { nav.reiniciarEn(destinoTrasAprobacion(dependencias)) }
    val alInicio = { nav.reiniciarEn(RutasEntrada.INICIO) }
    NavHost(nav, startDestination = RutasEntrada.INGRESO) {
        composable(RutasEntrada.INGRESO) {
            RutaIngreso(dependencias, onIngresado = alIngresar, onCrearCuenta = { nav.navigate(RutasEntrada.REGISTRO) })
        }
        composable(RutasEntrada.REGISTRO) {
            RutaRegistro(
                dependencias.api,
                onRegistrado = { registro ->
                    val reenvio = runCatching { OffsetDateTime.parse(registro.otp.reenvioDisponibleEn).toInstant() }
                        .getOrElse { Instant.EPOCH }
                    // Volver desde el OTP no debe reenviar el formulario: la cuenta ya existe.
                    nav.navigate(RutasEntrada.otp(registro.id, reenvio)) { popUpTo(RutasEntrada.INGRESO) }
                },
                onSalir = { nav.popBackStack() },
            )
        }
        composable(
            RutasEntrada.OTP,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("reenvio") { type = NavType.LongType },
            ),
        ) { entrada ->
            val argumentos = requireNotNull(entrada.arguments)
            RutaOtp(
                api = dependencias.api,
                sesion = dependencias.sesion,
                idRegistro = requireNotNull(argumentos.getString("id")),
                reenvioDisponibleEn = Instant.ofEpochSecond(argumentos.getLong("reenvio")),
                reloj = dependencias.reloj,
                onVerificado = { nav.reiniciarEn(RutasEntrada.IDENTIDAD) },
            )
        }
        composable(RutasEntrada.IDENTIDAD) {
            RutaIdentidad(dependencias.api, dependencias.sesion, onAprobada = alAprobar, onIrAlInicio = alInicio)
        }
        composable(RutasEntrada.BIOMETRIA) { RutaOfertaBiometria(dependencias, onTerminar = alInicio) }
        composable(RutasEntrada.INICIO) {
            SolventaApp(
                identidadVerificada = sesion.identidadVerificada,
                dependenciasConsentimiento = dependenciasConsentimiento,
                // Un 401 que llega a la pantalla ya no se pudo renovar; en el mock no hay renovación.
                onSesionVencida = { sesion.alVencer(tokenRechazado = false) },
                avisoBiometriaDesactivada = sesion.biometriaDesactivada,
                onAvisoAtendido = sesion::avisoBiometriaAtendido,
            )
        }
    }
}

/** Un cliente sin identidad verificada ve primero el estado de su verificación. */
internal fun destinoTrasIngreso(cliente: Cliente, dependencias: DependenciasOnboarding): String =
    if (cliente.estadoIdentidad == EstadoIdentidad.VERIFICADA) {
        destinoTrasAprobacion(dependencias)
    } else {
        RutasEntrada.IDENTIDAD
    }

internal fun destinoTrasAprobacion(dependencias: DependenciasOnboarding): String {
    val cliente = dependencias.sesion.sesion?.cliente
    val ofrecer = cliente != null &&
        debeOfrecerBiometria(cliente, dependencias.biometria.disponibilidad(), dependencias.almacen)
    return if (ofrecer) RutasEntrada.BIOMETRIA else RutasEntrada.INICIO
}

/** Navega y vacía la pila: tras entrar, Atrás no vuelve al ingreso ni al OTP. */
private fun NavHostController.reiniciarEn(ruta: String) = navigate(ruta) {
    popUpTo(graph.id) { inclusive = true }
    launchSingleTop = true
}
