package co.solventa.mobile.navigation

import java.time.Instant

/** Rutas del flujo de entrada (MOB-F01); [INICIO] abre la navegación principal de cuatro destinos. */
object RutasEntrada {
    const val INGRESO = "ingreso"
    const val REGISTRO = "registro"
    const val OTP = "otp/{id}/{reenvio}"
    const val IDENTIDAD = "identidad"
    const val BIOMETRIA = "biometria"
    const val INICIO = "inicio"

    /** La billetera sin sesión (MOB-F03, entrega 2): solo después del desbloqueo del teléfono. */
    const val BILLETERA_SIN_SESION = "sin-sesion/polizas"

    fun otp(id: String, reenvioDisponibleEn: Instant) = "otp/$id/${reenvioDisponibleEn.epochSecond}"
}
