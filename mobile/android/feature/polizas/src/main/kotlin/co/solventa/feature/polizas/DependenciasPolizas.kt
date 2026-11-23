package co.solventa.feature.polizas

import co.solventa.core.network.BffApi
import co.solventa.feature.polizas.billetera.SincronizadorBilletera
import co.solventa.feature.polizas.detalle.DocumentosPoliza
import co.solventa.feature.polizas.detalle.VisorPdf
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Lo que la consulta de pólizas (WEB-F04) y la billetera sin conexión (MOB-F03) necesitan de la app; las
 * pruebas lo arman con dobles. El token de acceso viaja en cada llamada vía NetworkClient, con la
 * renovación de sesión; un 401 que no se pudo renovar se delega al flujo de sesión.
 *
 * @param documentos guarda el PDF descargado en la caché de la app (`cacheDir/polizas/`).
 * @param urlCotizacion la cotización de la web (`WEB_URL` + `cotizacion`): la app aún no tiene cotizador.
 * @param billetera copia cifrada de las pólizas para consultarlas sin conexión, con alcance de aplicación.
 * @param visor abre el PDF; sin él, la pantalla usa el visor del sistema con el contexto de la Activity.
 * @param io donde se escribe el PDF mientras llega del BFF.
 * @param reloj la fecha del teléfono: la vigencia sin conexión (§5) y la hora de sincronización.
 */
@Suppress("LongParameterList")
class DependenciasPolizas(
    val api: BffApi,
    val documentos: DocumentosPoliza,
    val urlCotizacion: String,
    val billetera: SincronizadorBilletera,
    val visor: VisorPdf? = null,
    val io: CoroutineDispatcher = Dispatchers.IO,
    val reloj: Clock = Clock.systemDefaultZone(),
)
