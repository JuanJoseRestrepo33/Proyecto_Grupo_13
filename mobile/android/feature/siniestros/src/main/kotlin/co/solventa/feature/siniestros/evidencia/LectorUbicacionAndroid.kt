package co.solventa.feature.siniestros.evidencia

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

private const val TIEMPO_LIMITE_MS = 8_000L
private val PERMISOS = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** Una sola lectura al capturar; sin señal, sin permiso o sin Play Services, la foto va sin coordenadas. */
class LectorUbicacionAndroid(private val contexto: Context) : LectorUbicacion {
    private val cliente by lazy { LocationServices.getFusedLocationProviderClient(contexto) }

    override suspend fun leer(): Coordenadas? {
        if (!conPermiso()) return null
        return withTimeoutOrNull(TIEMPO_LIMITE_MS) { lectura() }
    }

    private fun conPermiso() = PERMISOS.any { contexto.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    // conPermiso() ya lo comprobó.
    @SuppressLint("MissingPermission")
    private suspend fun lectura(): Coordenadas? = suspendCancellableCoroutine { continuacion ->
        val cancelacion = CancellationTokenSource()
        continuacion.invokeOnCancellation { cancelacion.cancel() }
        cliente.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancelacion.token)
            .addOnSuccessListener { lugar ->
                continuacion.resume(lugar?.let { Coordenadas(it.latitude, it.longitude) })
            }
            .addOnFailureListener { continuacion.resume(null) }
    }
}
