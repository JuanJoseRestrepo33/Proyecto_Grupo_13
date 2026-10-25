package co.solventa.feature.consentimiento

import co.solventa.core.network.BffApi
import co.solventa.feature.onboarding.sesion.GestorSesion

/** Lo que el flujo de MOB-F02 necesita de la app; las pruebas lo arman con dobles. */
class DependenciasConsentimiento(
    val api: BffApi,
    val sesion: GestorSesion,
)
