package co.solventa.feature.polizas.billetera

import co.solventa.core.network.EstadoPoliza
import co.solventa.core.network.PolizaDetalle
import co.solventa.core.network.PolizaResumen
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * La billetera del último cliente que sincronizó (MOB-F03, §1). Hay una sola por teléfono.
 *
 * @property polizas la lista de `GET /polizas` en el orden del BFF; vacía si el cliente no tiene pólizas.
 * @property detalles el detalle de cada póliza `vigente` o `por_iniciar`. Las vencidas guardan solo el resumen.
 * @property sincronizadaEn fecha y hora (ISO-8601, UTC) de la última sincronización completa.
 */
@Serializable
data class BilleteraGuardada(
    @SerialName("cliente_id") val clienteId: String,
    @SerialName("nombre_completo") val nombreCompleto: String,
    val polizas: List<PolizaResumen>,
    val detalles: List<PolizaDetalle>,
    @SerialName("sincronizada_en") val sincronizadaEn: String,
) {
    val momento: Instant get() = Instant.parse(sincronizadaEn)

    fun detalle(numero: String): PolizaDetalle? = detalles.firstOrNull { it.numero == numero }

    companion object {
        /** Las pólizas cuyo detalle se guarda. */
        fun guardaDetalle(poliza: PolizaResumen): Boolean = poliza.estado != EstadoPoliza.VENCIDA
    }
}
