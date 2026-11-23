package co.solventa.feature.consentimiento

import co.solventa.core.network.BffApi

/**
 * Lo que el flujo de MOB-F02 necesita de la app.
 * El token de acceso viaja implícitamente en cada llamada vía NetworkClient/TokenAccesoInterceptor.
 * El flujo 401 se delega al GestorSesion de la app; el ViewModel no lo maneja directamente (#154).
 */
class DependenciasConsentimiento(val api: BffApi)
