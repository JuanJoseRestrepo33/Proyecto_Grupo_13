package co.solventa.feature.onboarding

import co.solventa.core.network.BffApi
import co.solventa.feature.onboarding.biometria.AlmacenToken
import co.solventa.feature.onboarding.biometria.Biometria
import co.solventa.feature.onboarding.biometria.DesbloqueoTelefono
import co.solventa.feature.onboarding.sesion.GestorSesion
import java.time.Instant

/**
 * Lo que el flujo de MOB-F01 necesita de la app; las pruebas lo arman con dobles.
 *
 * @param desbloqueo el desbloqueo del teléfono que protege la billetera sin sesión (MOB-F03, entrega 2).
 */
class DependenciasOnboarding(
    val api: BffApi,
    val sesion: GestorSesion,
    val almacen: AlmacenToken,
    val biometria: Biometria,
    val desbloqueo: DesbloqueoTelefono? = null,
    val reloj: () -> Instant = Instant::now,
)
