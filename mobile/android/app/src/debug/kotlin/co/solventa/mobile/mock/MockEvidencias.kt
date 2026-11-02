package co.solventa.mobile.mock

import co.solventa.core.network.ErrorCampo
import co.solventa.core.network.Evidencia
import co.solventa.core.network.Siniestro

/** Un archivo de `POST /siniestros/{id}/evidencias`, ya separado del `multipart/form-data`. */
@Suppress("LongParameterList")
class CargaMock(
    val nombre: String?,
    val tipo: String?,
    val contenido: ByteArray,
    val tomadaEn: String? = null,
    val latitud: Double? = null,
    val longitud: Double? = null,
    val lugar: String? = null,
)

/** Marcas en `lugar` para provocar cada rechazo del BFF desde la app. */
const val MARCA_EVIDENCIA_413 = "#413"
const val MARCA_EVIDENCIA_422 = "#422"
const val MARCA_EVIDENCIA_409 = "#409"
const val MARCA_EVIDENCIA_503 = "#503"

/** La evidencia queda guardada, pero la app recibe un tiempo agotado: el reintento no debe duplicarla. */
const val MARCA_EVIDENCIA_PERDIDA = "#perdida"

private const val TAMANO_MAXIMO = 10L * 1024 * 1024

/** Evidencia de los siniestros del mock: `GET` y `POST /siniestros/{id}/evidencias`. */
internal class MockEvidencias(private val estado: EstadoMock, private val siniestros: MockSiniestros) {
    private val guardadas = mutableMapOf<String, MutableList<Evidencia>>()
    private val perdidas = mutableSetOf<String>()

    fun listar(autorizacion: String?, id: String): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        return siniestros.delCliente(cuenta.id, id)
            ?.let { respuesta(200, guardadas[it.id].orEmpty().toList()) }
            ?: fallo(404, "siniestro_no_encontrado")
    }

    @Suppress("ReturnCount")
    fun adjuntar(autorizacion: String?, id: String, carga: CargaMock): RespuestaMock {
        val cuenta = estado.cuentaAutenticada(autorizacion) ?: return noAutenticado()
        val siniestro = siniestros.delCliente(cuenta.id, id) ?: return fallo(404, "siniestro_no_encontrado")
        val lugar = carga.lugar.orEmpty()
        rechazo(carga, lugar)?.let { return it }
        val evidencia = guardar(siniestro, carga)
        val perder = MARCA_EVIDENCIA_PERDIDA in lugar && perdidas.add(carga.nombre.orEmpty())
        return respuesta(201, evidencia).copy(perdida = perder)
    }

    private fun rechazo(carga: CargaMock, lugar: String): RespuestaMock? = when {
        MARCA_EVIDENCIA_409 in lugar -> fallo(409, "siniestro_cerrado")
        MARCA_EVIDENCIA_503 in lugar -> fallo(503, "almacenamiento_no_disponible")
        MARCA_EVIDENCIA_413 in lugar || carga.contenido.size > TAMANO_MAXIMO -> fallo(413, "archivo_demasiado_grande")
        carga.contenido.isEmpty() -> fallo(422, "archivo_vacio", listOf(ErrorCampo("archivo", "archivo_vacio")))
        MARCA_EVIDENCIA_422 in lugar || carga.tipo !in TIPOS ->
            fallo(422, "formato_no_admitido", listOf(ErrorCampo("archivo", "formato_no_admitido")))
        else -> null
    }

    private fun guardar(siniestro: Siniestro, carga: CargaMock): Evidencia {
        val lista = guardadas.getOrPut(siniestro.id) { mutableListOf() }
        val evidencia = Evidencia(
            id = "EVI-${lista.size + 1}",
            tipoContenido = carga.tipo.orEmpty(),
            tamano = carga.contenido.size.toLong(),
            nombreOriginal = carga.nombre,
            canal = "movil",
            cargadaEn = estado.reloj().toString(),
            tomadaEn = carga.tomadaEn,
            latitud = carga.latitud,
            longitud = carga.longitud,
            lugar = carga.lugar,
        )
        lista += evidencia
        return evidencia
    }

    private companion object {
        val TIPOS = setOf("image/jpeg", "image/png", "image/heic", "video/mp4", "application/pdf")
    }
}
