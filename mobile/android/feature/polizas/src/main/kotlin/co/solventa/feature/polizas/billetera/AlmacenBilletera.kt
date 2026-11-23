package co.solventa.feature.polizas.billetera

import co.solventa.core.almacen.ArchivoCifrado
import co.solventa.core.almacen.ArchivoIlegible
import co.solventa.core.network.NetworkClient
import java.io.IOException
import java.security.ProviderException
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/**
 * La billetera cifrada en un solo archivo (en la app, `noBackupFilesDir/billetera/billetera.bin` con la
 * llave `solventa.billetera`). Guardar, leer y borrar pasan por el mismo [Mutex] (§3): una escritura que
 * se cuela antes de un cambio de cuenta siempre termina antes del borrado que ese cambio dispara.
 *
 * Un archivo que no se puede descifrar o leer se trata como «sin billetera guardada» y se borra.
 */
class AlmacenBilletera(
    private val archivo: ArchivoCifrado,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val candado = Mutex()

    /**
     * Reemplaza la billetera si [vigente], evaluada dentro del candado justo antes de escribir, sigue
     * siendo cierta. @return true si quedó guardada.
     */
    suspend fun guardarSi(billetera: BilleteraGuardada, vigente: () -> Boolean): Boolean = bajoCandado {
        if (!vigente()) return@bajoCandado false
        val texto = NetworkClient.json.encodeToString(BilleteraGuardada.serializer(), billetera)
        try {
            archivo.escribir(texto.toByteArray(Charsets.UTF_8))
            true
        } catch (ignored: IOException) {
            // Queda la billetera anterior, que sigue siendo coherente.
            false
        } catch (ignored: ProviderException) {
            // El Keystore del equipo no pudo crear o usar la llave: igual que un fallo de escritura.
            false
        }
    }

    /**
     * La billetera si su dueño es aceptado por [esDuenio]; si es de otro dueño o no se puede leer, se
     * borra y devuelve null. Un archivo de otro dueño nunca se muestra (§3).
     */
    suspend fun leer(esDuenio: (String) -> Boolean): BilleteraGuardada? = bajoCandado {
        val billetera = leerSinCandado() ?: return@bajoCandado null
        if (esDuenio(billetera.clienteId)) {
            billetera
        } else {
            archivo.borrar()
            null
        }
    }

    /** Al iniciar sesión otra cuenta: la billetera de un dueño distinto de [cliente] se borra. */
    suspend fun borrarSiNoEsDe(cliente: String) = bajoCandado {
        val billetera = leerSinCandado()
        if (billetera != null && billetera.clienteId != cliente) archivo.borrar()
    }

    suspend fun borrar() = bajoCandado { archivo.borrar() }

    private suspend fun <T> bajoCandado(bloque: () -> T): T = candado.withLock { withContext(io) { bloque() } }

    /**
     * Un archivo que no se puede descifrar (incluido un error temporal del Keystore) o que no cumple el
     * formato se borra: la próxima sincronización lo vuelve a escribir. Mostrar datos a medias o cerrar la
     * app no es una opción.
     */
    private fun leerSinCandado(): BilleteraGuardada? {
        val leida = runCatching {
            archivo.leer()?.let { bytes ->
                NetworkClient.json.decodeFromString(BilleteraGuardada.serializer(), bytes.toString(Charsets.UTF_8))
                    .also { it.momento }
            }
        }
        val error = leida.exceptionOrNull()
        if (error != null) {
            if (!error.esIlegible()) throw error
            archivo.borrar()
        }
        return leida.getOrNull()
    }

    /** [ProviderException]: el Keystore del equipo falló al usar la llave; no debe cerrar la app. */
    private fun Throwable.esIlegible() = this is ArchivoIlegible || this is SerializationException ||
        this is IllegalArgumentException || this is DateTimeParseException || this is ProviderException
}
