package co.solventa.feature.polizas.detalle

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import co.solventa.feature.polizas.R
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Guarda el PDF de una póliza mientras llega del BFF y devuelve su ruta. */
fun interface DocumentosPoliza {
    /** @throws IOException si la red se corta a mitad de la descarga o no se puede escribir. */
    fun guardar(numero: String, contenido: InputStream): String
}

/** Abre un PDF ya guardado; false si no hay una app que lo pueda abrir. */
fun interface VisorPdf {
    fun abrir(ruta: String): Boolean
}

/** Carpeta de la caché que comparte el [ProveedorDocumentos]: ver `res/xml/documentos_polizas.xml`. */
private const val CARPETA = "polizas"

/**
 * El PDF va a `cacheDir/polizas/<número>.pdf`, sin permisos de almacenamiento. Se escribe primero en un
 * archivo parcial: una descarga cortada nunca deja un PDF incompleto que el visor abriría.
 */
class DocumentosPolizaAndroid(context: Context) : DocumentosPoliza {
    private val carpeta = File(context.applicationContext.cacheDir, CARPETA)

    override fun guardar(numero: String, contenido: InputStream): String {
        if (!carpeta.isDirectory && !carpeta.mkdirs()) throw IOException("No se pudo crear $carpeta")
        val destino = File(carpeta, nombreArchivo(numero))
        val parcial = File(carpeta, "${destino.name}.parcial")
        try {
            parcial.outputStream().use { salida -> contenido.copyTo(salida) }
            if (!parcial.renameTo(destino)) {
                destino.delete()
                if (!parcial.renameTo(destino)) throw IOException("No se pudo guardar $destino")
            }
        } finally {
            parcial.delete()
        }
        return destino.path
    }
}

/** El número de la póliza como nombre de archivo: solo letras, dígitos y guiones. */
fun nombreArchivo(numero: String): String = numero.replace(Regex("[^A-Za-z0-9-]"), "_") + ".pdf"

/** Proveedor propio para no chocar con otro `FileProvider` al combinar los manifiestos. */
class ProveedorDocumentos : FileProvider(R.xml.documentos_polizas)

/**
 * Abre el PDF con el visor del sistema (`ACTION_VIEW`) y permiso de lectura temporal. Con el contexto de
 * la Activity, el visor queda en la misma tarea y Atrás vuelve al detalle.
 */
class VisorPdfAndroid(private val context: Context) : VisorPdf {
    override fun abrir(ruta: String): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.polizas.documentos", File(ruta))
        val intento = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(intento)
            true
        } catch (ignored: ActivityNotFoundException) {
            false
        }
    }
}
