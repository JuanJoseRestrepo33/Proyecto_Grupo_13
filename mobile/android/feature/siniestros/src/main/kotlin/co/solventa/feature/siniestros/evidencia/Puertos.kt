package co.solventa.feature.siniestros.evidencia

import java.io.File
import java.time.OffsetDateTime

data class Coordenadas(val latitud: Double, val longitud: Double)

/** Por qué un archivo no se puede adjuntar; se decide antes de enviar. */
enum class MotivoRechazo { VACIO, FORMATO, ILEGIBLE, NO_CABE }

sealed interface Preparado {
    /** [ruta] ya cumple el contrato: tipo real, hasta 10 MB y sin coordenadas en sus metadatos. */
    data class Listo(val ruta: String, val nombre: String, val formato: FormatoEvidencia, val tamano: Long) : Preparado

    data class Rechazado(
        val motivo: MotivoRechazo,
        val formato: FormatoEvidencia? = null,
        val tamano: Long = 0,
    ) : Preparado
}

/** Archivos temporales de la evidencia; viven en la caché de la app, fuera de las copias de seguridad. */
interface AlmacenEvidencias {
    /** Copia, normaliza y, si hace falta, comprime lo que hay en [uri], fuera del hilo principal. */
    suspend fun preparar(uri: String, onProgreso: (Float) -> Unit): Preparado

    /** Archivo donde la cámara escribe la foto. */
    fun nuevaCaptura(): File

    /** El archivo de [ruta], o null si el sistema lo borró. */
    fun archivo(ruta: String): File?

    fun borrar(ruta: String)

    /** Borra lo que no esté en [rutas]: lo que dejó un reporte abandonado. */
    fun conservarSolo(rutas: Set<String>)

    /** Sin almacén no se puede adjuntar, y el reporte sigue funcionando. */
    object Nulo : AlmacenEvidencias {
        override suspend fun preparar(uri: String, onProgreso: (Float) -> Unit) =
            Preparado.Rechazado(MotivoRechazo.ILEGIBLE)

        override fun nuevaCaptura(): File = File.createTempFile("captura", ".jpg")
        override fun archivo(ruta: String): File? = null
        override fun borrar(ruta: String) = Unit
        override fun conservarSolo(rutas: Set<String>) = Unit
    }
}

/** Lo que la evidencia necesita del dispositivo; sin ellas el reporte funciona igual. */
class FuentesEvidencia(
    val almacen: AlmacenEvidencias = AlmacenEvidencias.Nulo,
    val ubicacion: LectorUbicacion = LectorUbicacion { null },
    val ahora: () -> OffsetDateTime = OffsetDateTime::now,
)

fun interface LectorUbicacion {
    /** Una lectura con tiempo límite; null si no hay permiso o señal. */
    suspend fun leer(): Coordenadas?
}
