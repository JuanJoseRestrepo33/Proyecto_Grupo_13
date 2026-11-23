package co.solventa.mobile.navigation

import co.solventa.core.almacen.ArchivoCifrado
import co.solventa.core.network.BffApi
import co.solventa.feature.polizas.billetera.AlmacenBilletera
import co.solventa.feature.polizas.billetera.CuentaVigente
import co.solventa.feature.polizas.billetera.SincronizadorBilletera
import java.io.File
import co.solventa.feature.siniestros.borrador.AlmacenBorradoresCifrado
import co.solventa.core.almacen.CifradoAesGcm
import co.solventa.feature.siniestros.borrador.EnviadorBorradores
import co.solventa.core.almacen.FuenteLlave
import co.solventa.core.almacen.MonitorConectividad
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Llave AES de la JCA: en Robolectric no hay Android Keystore. */
private class LlaveEnMemoria : FuenteLlave {
    private val llave: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun paraCifrar() = llave

    override fun paraDescifrar() = llave
}

/** El enviador real, con el almacén cifrado en una carpeta temporal y siempre con red. */
fun enviadorDePrueba(
    api: BffApi,
    monitor: MonitorConectividad = MonitorConectividad.Siempre,
    clienteVigente: () -> String?,
) = EnviadorBorradores(
    api = api,
    almacen = AlmacenBorradoresCifrado(
        Files.createTempDirectory("borradores").toFile().apply { deleteOnExit() },
        CifradoAesGcm(LlaveEnMemoria()),
    ),
    monitor = monitor,
    clienteVigente = clienteVigente,
    alcance = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    io = Dispatchers.Main,
)

/** La billetera real (MOB-F03), cifrada en una carpeta temporal con una llave de la JCA. */
class BilleteraDePrueba(
    api: BffApi,
    cuenta: CuentaVigente,
    monitor: MonitorConectividad = MonitorConectividad.Siempre,
) {
    val archivo = File(Files.createTempDirectory("billetera").toFile().apply { deleteOnExit() }, "billetera.bin")
    val sincronizador = SincronizadorBilletera(
        api = api,
        almacen = AlmacenBilletera(ArchivoCifrado(archivo, CifradoAesGcm(LlaveEnMemoria())), Dispatchers.Main),
        monitor = monitor,
        cuenta = cuenta,
        alcance = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    )
}

/** Una cuenta fija, para las pruebas que no pasan por el ingreso. */
class CuentaFija(override val clienteId: String?, override val nombreCompleto: String? = "Cliente Demo") : CuentaVigente
