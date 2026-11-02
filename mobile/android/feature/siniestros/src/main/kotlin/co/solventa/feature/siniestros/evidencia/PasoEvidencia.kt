@file:Suppress("TooManyFunctions")

package co.solventa.feature.siniestros.evidencia

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.solventa.core.designsystem.SolventaSpacing
import co.solventa.feature.siniestros.R
import co.solventa.feature.siniestros.comun.BotonPrincipal
import co.solventa.feature.siniestros.comun.BotonSecundario
import co.solventa.feature.siniestros.comun.CampoTexto
import co.solventa.feature.siniestros.comun.Mensaje
import co.solventa.feature.siniestros.reporte.formatoFechaHora
import co.solventa.feature.siniestros.reporte.formatoTamano
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LADO_MINIATURA = 64.dp
private const val MUESTRA_MINIATURA = 8

/** El paso es opcional: cada permiso negado tiene alternativa y nada impide continuar (P20). */
@Composable
internal fun PasoEvidencia(estado: EstadoEvidencia, gestor: GestorEvidencia, acciones: AccionesEvidencia) {
    Text(stringResource(R.string.evidencia_instrucciones), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.evidencia_formatos), style = MaterialTheme.typography.bodySmall)
    if (estado.explicando) {
        Explicacion(gestor, acciones)
    } else {
        Opciones(estado, gestor, acciones)
    }
    if (estado.preparando) Preparando(estado.progreso)
    estado.rechazo?.let { Mensaje(stringResource(it.mensaje())) }
    // Numera como la confirmación, que lista también lo ya enviado: "Foto 4" sigue siendo "Foto 4".
    estado.porEnviar.forEachIndexed { indice, adjunto ->
        FilaAdjunto(adjunto, indice, estado.adjuntos.indexOf(adjunto) + 1, gestor)
    }
}

@Composable
private fun Explicacion(gestor: GestorEvidencia, acciones: AccionesEvidencia) {
    Text(
        stringResource(R.string.permisos_explicacion),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag("explicacion-permisos"),
    )
    BotonPrincipal(
        texto = stringResource(R.string.permisos_entendido),
        onClick = {
            gestor.explicar(false)
            acciones.pedirPermisos()
        },
    )
    BotonSecundario(stringResource(R.string.permisos_ahora_no), onClick = { gestor.explicar(false) })
}

@Composable
private fun Opciones(estado: EstadoEvidencia, gestor: GestorEvidencia, acciones: AccionesEvidencia) {
    val conCamara = estado.permisoCamara != EstadoPermiso.PERMANENTE && !estado.camaraNoDisponible
    if (conCamara) {
        BotonPrincipal(
            texto = stringResource(R.string.evidencia_tomar_foto),
            habilitado = !estado.preparando,
            onClick = { if (estado.camaraLista) acciones.abrirCamara() else gestor.explicar(true) },
            modifier = Modifier.testTag("tomar-foto"),
        )
    }
    BotonSecundario(
        stringResource(R.string.evidencia_galeria),
        onClick = acciones.abrirGaleria,
        habilitado = !estado.preparando,
        modifier = Modifier.testTag("galeria"),
    )
    BotonSecundario(
        stringResource(R.string.evidencia_documento),
        onClick = acciones.abrirDocumentos,
        habilitado = !estado.preparando,
        modifier = Modifier.testTag("documento"),
    )
    AvisosPermisos(estado, acciones)
}

@Composable
private fun AvisosPermisos(estado: EstadoEvidencia, acciones: AccionesEvidencia) {
    val camara = estado.permisoCamara
    val ubicacion = estado.permisoUbicacion
    when {
        estado.camaraNoDisponible -> Nota(R.string.camara_no_disponible, "aviso-camara")
        camara == EstadoPermiso.PERMANENTE -> Nota(R.string.camara_permanente, "aviso-camara")
        camara == EstadoPermiso.NEGADO -> Nota(R.string.camara_negada, "aviso-camara")
    }
    when (ubicacion) {
        EstadoPermiso.PERMANENTE -> Nota(R.string.ubicacion_permanente, "aviso-ubicacion")
        EstadoPermiso.NEGADO -> Nota(R.string.ubicacion_negada, "aviso-ubicacion")
        else -> Unit
    }
    if (camara == EstadoPermiso.PERMANENTE || ubicacion == EstadoPermiso.PERMANENTE) {
        BotonSecundario(stringResource(R.string.permisos_abrir_ajustes), onClick = acciones.abrirAjustes)
    }
}

@Composable
private fun Nota(@StringRes texto: Int, etiqueta: String) {
    Mensaje(stringResource(texto), esError = false, modifier = Modifier.testTag(etiqueta))
}

@Composable
private fun Preparando(progreso: Float?) {
    val texto = if (progreso == null) {
        stringResource(R.string.evidencia_preparando)
    } else {
        stringResource(R.string.evidencia_comprimiendo, (progreso * PORCENTAJE).toInt())
    }
    Column(verticalArrangement = Arrangement.spacedBy(SolventaSpacing.xs), modifier = Modifier.testTag("preparando")) {
        Text(texto, style = MaterialTheme.typography.bodyMedium)
        val avance = Modifier.fillMaxWidth().semantics { contentDescription = texto }
        if (progreso == null) {
            LinearProgressIndicator(avance)
        } else {
            LinearProgressIndicator(progress = { progreso }, modifier = avance)
        }
    }
}

private const val PORCENTAJE = 100

@Composable
private fun FilaAdjunto(adjunto: Adjunto, indice: Int, numero: Int, gestor: GestorEvidencia) {
    val nombre = stringResource(adjunto.formato.etiqueta(), numero)
    Column(
        verticalArrangement = Arrangement.spacedBy(SolventaSpacing.xs),
        modifier = Modifier.fillMaxWidth().testTag("adjunto-$indice"),
    ) {
        HorizontalDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SolventaSpacing.md),
        ) {
            Miniatura(adjunto, nombre)
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(nombre, style = MaterialTheme.typography.titleSmall)
                Text(formatoTamano(adjunto.tamano), style = MaterialTheme.typography.bodySmall)
                Text(fechaDe(adjunto.tomadaEn), style = MaterialTheme.typography.bodySmall)
                if (adjunto.conCoordenadas) {
                    Text(
                        textoCoordenadas(adjunto.latitud, adjunto.longitud),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            val quitar = stringResource(R.string.evidencia_quitar, nombre)
            IconButton(
                onClick = { gestor.quitar(adjunto.id) },
                modifier = Modifier.size(SolventaSpacing.touchTarget).testTag("quitar-$indice"),
            ) {
                Icon(Icons.Default.Close, contentDescription = quitar)
            }
        }
        if (adjunto.estado == EstadoAdjunto.NO_CABE) {
            Mensaje(stringResource(R.string.evidencia_no_cabe))
        } else if (!adjunto.conCoordenadas) {
            CampoTexto(
                valor = adjunto.lugar,
                onCambio = { gestor.cambiarLugar(adjunto.id, it) },
                etiqueta = stringResource(R.string.evidencia_lugar),
                ayuda = stringResource(R.string.evidencia_lugar_ayuda, adjunto.lugar.length, LARGO_MAXIMO_LUGAR),
                modifier = Modifier.testTag("lugar-$indice"),
            )
        }
    }
}

@Composable
private fun fechaDe(tomadaEn: String?): String =
    tomadaEn?.let { stringResource(R.string.evidencia_tomada, formatoFechaHora(it)) }
        ?: stringResource(R.string.evidencia_sin_fecha)

internal fun textoCoordenadas(latitud: Double?, longitud: Double?) =
    String.format(Locale.ROOT, "%.5f, %.5f", latitud, longitud)

@Composable
private fun Miniatura(adjunto: Adjunto, descripcion: String) {
    var miniatura by remember(adjunto.ruta) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(adjunto.ruta) { miniatura = withContext(Dispatchers.IO) { leerMiniatura(adjunto) } }
    val imagen = miniatura
    if (imagen != null) {
        Image(
            imagen.asImageBitmap(),
            contentDescription = stringResource(R.string.evidencia_miniatura, descripcion),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(LADO_MINIATURA),
        )
    } else {
        // Sin vista previa (PDF, o mientras carga): el tipo de archivo, sin repetirlo al lector de pantalla.
        Box(
            Modifier
                .size(LADO_MINIATURA)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(
                adjunto.formato?.extension?.uppercase(Locale.ROOT).orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun leerMiniatura(adjunto: Adjunto): Bitmap? {
    if (adjunto.ruta.isEmpty() || !File(adjunto.ruta).isFile) return null
    return when (adjunto.formato) {
        FormatoEvidencia.JPEG, FormatoEvidencia.PNG, FormatoEvidencia.HEIC -> BitmapFactory.decodeFile(
            adjunto.ruta,
            BitmapFactory.Options().apply { inSampleSize = MUESTRA_MINIATURA },
        )
        FormatoEvidencia.MP4 -> cuadroDeVideo(adjunto.ruta)
        else -> null
    }
}

private fun cuadroDeVideo(ruta: String): Bitmap? {
    val lector = MediaMetadataRetriever()
    return try {
        lector.setDataSource(ruta)
        lector.frameAtTime
    } catch (ignored: IllegalArgumentException) {
        null
    } finally {
        lector.release()
    }
}

@StringRes
internal fun FormatoEvidencia?.etiqueta() = when (this) {
    FormatoEvidencia.MP4 -> R.string.evidencia_video
    FormatoEvidencia.PDF -> R.string.evidencia_pdf
    else -> R.string.evidencia_foto
}

@StringRes
internal fun MotivoRechazo.mensaje() = when (this) {
    MotivoRechazo.VACIO -> R.string.rechazo_vacio
    MotivoRechazo.FORMATO -> R.string.rechazo_formato
    MotivoRechazo.ILEGIBLE -> R.string.rechazo_ilegible
    MotivoRechazo.NO_CABE -> R.string.rechazo_no_cabe
}
