package co.solventa.mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.core.network.BffApi
import co.solventa.feature.consentimiento.DependenciasConsentimiento
import co.solventa.feature.notificaciones.AlmacenPushAndroid
import co.solventa.feature.notificaciones.AperturasPush
import co.solventa.feature.notificaciones.DependenciasNotificaciones
import co.solventa.feature.notificaciones.NotificadorAndroid
import co.solventa.feature.notificaciones.ReceptorPush
import co.solventa.feature.notificaciones.aperturaDe
import co.solventa.feature.notificaciones.consumirApertura
import co.solventa.feature.notificaciones.intentoApertura
import co.solventa.feature.onboarding.DependenciasOnboarding
import co.solventa.feature.onboarding.biometria.AlmacenTokenKeystore
import co.solventa.feature.onboarding.biometria.BiometriaAndroid
import co.solventa.feature.onboarding.sesion.GestorSesion
import co.solventa.feature.onboarding.sesion.ObservadorCuenta
import co.solventa.feature.siniestros.borrador.AlmacenBorradoresCifrado
import co.solventa.feature.siniestros.borrador.CifradoAesGcm
import co.solventa.feature.siniestros.borrador.EnviadorBorradores
import co.solventa.feature.siniestros.borrador.LlaveKeystore
import co.solventa.feature.siniestros.borrador.MonitorConectividadAndroid
import co.solventa.feature.siniestros.DependenciasSiniestros
import co.solventa.feature.siniestros.evidencia.AlmacenEvidenciasAndroid
import co.solventa.feature.siniestros.evidencia.LectorUbicacionAndroid
import co.solventa.mobile.navigation.SolventaRaiz

// FragmentActivity: BiometricPrompt la requiere para mostrar el diálogo del sistema.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val proceso = Proceso.de(applicationContext)
        val dependencias = DependenciasOnboarding(
            api = proceso.bffApi,
            sesion = proceso.sesion,
            almacen = proceso.almacen,
            biometria = BiometriaAndroid(this),
        )
        val dependenciasConsentimiento = DependenciasConsentimiento(
            api = proceso.bffApi,
        )
        val dependenciasSiniestros = DependenciasSiniestros(
            api = proceso.bffApi,
            enviador = proceso.enviador,
            almacen = AlmacenEvidenciasAndroid(applicationContext),
            ubicacion = LectorUbicacionAndroid(applicationContext),
        )
        val dependenciasNotificaciones = DependenciasNotificaciones(
            receptor = proceso.receptor,
            almacen = proceso.almacenPush,
            aperturas = proceso.aperturas,
        )
        recibirApertura(intent)
        setContent {
            SolventaTheme {
                SolventaRaiz(
                    dependencias,
                    dependenciasConsentimiento,
                    dependenciasSiniestros,
                    dependenciasNotificaciones,
                )
            }
        }
    }

    /**
     * Al abrir la app con sesión activa se procesan los borradores pendientes (MOB-F05-02), se registra el
     * dispositivo y se lee la bandeja de notificaciones (MOB-F06).
     */
    override fun onStart() {
        super.onStart()
        val proceso = Proceso.de(applicationContext)
        proceso.sesion.clienteId?.let {
            proceso.enviador.activar(it)
            proceso.receptor.activar(it)
        }
    }

    /** `singleTop`: tocar una notificación con la app abierta llega aquí, no a otra copia de la Activity. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recibirApertura(intent)
    }

    /** La navegación abre el detalle del siniestro en cuanto haya sesión (MOB-F06, criterio 2). */
    private fun recibirApertura(intent: Intent?) {
        val apertura = aperturaDe(intent) ?: return
        Proceso.de(applicationContext).aperturas.recibir(apertura)
        consumirApertura(intent)
    }
}

/**
 * Punto de composición: la sesión y el cliente del BFF sobreviven a la recreación de la Activity.
 * La sesión y el ingreso comparten el almacén para rotar el mismo token de refresco.
 */
private class Proceso(context: Context) {
    val almacen = AlmacenTokenKeystore(context)
    val sesion = GestorSesion(almacen)
    val bffApi: BffApi by lazy { BffProvider.create(sesion) }

    /** Vive lo que el proceso: envía los borradores mientras la app tenga sesión, aunque esté en segundo plano. */
    val enviador: EnviadorBorradores by lazy {
        EnviadorBorradores(
            api = bffApi,
            almacen = AlmacenBorradoresCifrado(
                File(context.noBackupFilesDir, "borradores"),
                CifradoAesGcm(LlaveKeystore()),
            ),
            monitor = MonitorConectividadAndroid(context),
            clienteVigente = { sesion.clienteId },
            alcance = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    val almacenPush = AlmacenPushAndroid(context)
    val aperturas = AperturasPush()

    /** Push del siniestro (MOB-F06): registra el dispositivo y lee la bandeja mientras haya sesión. */
    val receptor: ReceptorPush by lazy {
        ReceptorPush(
            api = bffApi,
            almacen = almacenPush,
            notificador = NotificadorAndroid(context) { intentoApertura(context, MainActivity::class.java, it) },
            sesion = sesion,
            alcance = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    init {
        // El envío y el push del anterior se cancelan antes de reemplazar la sesión; los del nuevo empiezan
        // después. Al iniciar sesión se registra el dispositivo (MOB-F06, criterio 4).
        sesion.observador = object : ObservadorCuenta {
            override fun antes(nuevoCliente: String?) {
                enviador.detener()
                receptor.detener()
            }

            override fun despues(cliente: String?) {
                cliente?.let {
                    enviador.activar(it)
                    receptor.activar(it)
                }
            }
        }
    }

    companion object {
        @Volatile
        private var instancia: Proceso? = null

        fun de(context: Context): Proceso = instancia ?: synchronized(this) {
            instancia ?: Proceso(context).also { instancia = it }
        }
    }
}
