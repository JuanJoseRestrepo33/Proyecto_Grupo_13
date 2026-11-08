package co.solventa.mobile.navigation

import co.solventa.core.network.BffApi
import co.solventa.feature.siniestros.borrador.AlmacenBorradoresCifrado
import co.solventa.feature.siniestros.borrador.CifradoAesGcm
import co.solventa.feature.siniestros.borrador.EnviadorBorradores
import co.solventa.feature.siniestros.borrador.FuenteLlave
import co.solventa.feature.siniestros.borrador.MonitorConectividad
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
