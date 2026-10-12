package co.solventa.core.network

import kotlinx.serialization.Serializable
import retrofit2.http.GET

// Ejemplo técnico provisional; no representa un contrato publicado del BFF.
interface BffApi {
    @GET("health")
    suspend fun health(): HealthResponse
}

@Serializable
data class HealthResponse(val status: String, val version: String)
