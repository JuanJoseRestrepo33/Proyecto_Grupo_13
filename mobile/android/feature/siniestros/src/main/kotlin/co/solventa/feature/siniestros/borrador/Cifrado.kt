package co.solventa.feature.siniestros.borrador

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifrado en flujo de los borradores (RC-04). Las pruebas JVM usan un doble o [CifradoAesGcm] con una
 * llave de la JCA; la app, [CifradoAesGcm] con la llave del Android Keystore.
 */
interface PuertoCifrado {
    /** Lo escrito en el flujo devuelto llega cifrado a [destino]; cerrarlo no cierra [destino]. */
    fun cifrar(destino: OutputStream): OutputStream

    /** @throws BorradorIlegible si no se puede abrir: temporal (reintentar) o permanente. */
    fun descifrar(origen: InputStream): InputStream
}

/** La llave AES-256 del almacén. Descifrar nunca la crea: sin llave el borrador es ilegible. */
interface FuenteLlave {
    fun paraCifrar(): SecretKey

    /** @throws LlaveInexistente si se borró (p. ej. al quitar el bloqueo de pantalla en algunos equipos). */
    fun paraDescifrar(): SecretKey
}

class LlaveInexistente : GeneralSecurityException("La llave de los borradores no existe")

/**
 * El borrador no se pudo leer. [permanente]: la llave ya no existe o el texto cifrado fue alterado, y
 * reintentar no sirve; si no, se conserva y se reintenta después.
 */
class BorradorIlegible(val permanente: Boolean, causa: Throwable? = null) : IOException(causa)

/**
 * AES-256-GCM con un IV aleatorio por archivo, generado por el proveedor (el Keystore no deja elegirlo).
 * Formato: versión (1 byte), largo del IV (1 byte), IV y texto cifrado con la etiqueta de 128 bits.
 * GCM no entrega texto en claro sin verificar la etiqueta: un archivo alterado no se descifra.
 */
class CifradoAesGcm(private val llave: FuenteLlave) : PuertoCifrado {
    override fun cifrar(destino: OutputStream): OutputStream {
        val cifrador = try {
            Cipher.getInstance(TRANSFORMACION).apply { init(Cipher.ENCRYPT_MODE, llave.paraCifrar()) }
        } catch (error: GeneralSecurityException) {
            throw IOException("No se pudo preparar el cifrado", error)
        }
        val iv = cifrador.iv
        destino.write(VERSION)
        destino.write(iv.size)
        destino.write(iv)
        return CipherOutputStream(SinCerrar(destino), cifrador)
    }

    override fun descifrar(origen: InputStream): InputStream {
        val datos = DataInputStream(origen)
        val iv = try {
            if (datos.readUnsignedByte() != VERSION) throw BorradorIlegible(permanente = true)
            ByteArray(datos.readUnsignedByte()).also(datos::readFully)
        } catch (truncado: EOFException) {
            throw BorradorIlegible(permanente = true, truncado)
        }
        val cifrador = try {
            Cipher.getInstance(TRANSFORMACION).apply {
                init(Cipher.DECRYPT_MODE, llave.paraDescifrar(), GCMParameterSpec(BITS_ETIQUETA, iv))
            }
        } catch (error: GeneralSecurityException) {
            throw clasificar(error)
        } catch (invalido: IllegalArgumentException) {
            // Un IV de largo cero: el encabezado fue alterado.
            throw BorradorIlegible(permanente = true, invalido)
        }
        return LecturaClasificada(CipherInputStream(datos, cifrador))
    }

    private companion object {
        const val TRANSFORMACION = "AES/GCM/NoPadding"
        const val VERSION = 1
        const val BITS_ETIQUETA = 128
    }
}

/**
 * Permanente: sin llave, llave invalidada (`KeyPermanentlyInvalidatedException` es una
 * [InvalidKeyException]) o etiqueta que no coincide. Lo demás (`KeyStoreException`, E/S) es temporal.
 */
internal fun clasificar(error: Throwable): BorradorIlegible {
    if (error is BorradorIlegible) return error
    val causas = generateSequence(error) { it.cause }.take(PROFUNDIDAD_CAUSAS).toList()
    val permanente = causas.any {
        it is LlaveInexistente || it is InvalidKeyException || it is AEADBadTagException || it is BadPaddingException
    }
    return BorradorIlegible(permanente, error)
}

private const val PROFUNDIDAD_CAUSAS = 8

/** `CipherInputStream` envuelve el fallo de la etiqueta en una IOException: se traduce aquí. */
private class LecturaClasificada(private val origen: InputStream) : InputStream() {
    override fun read(): Int = clasificando { origen.read() }

    override fun read(b: ByteArray, off: Int, len: Int): Int = clasificando { origen.read(b, off, len) }

    override fun close() = origen.close()

    private inline fun clasificando(lectura: () -> Int): Int = try {
        lectura()
    } catch (error: IOException) {
        throw clasificar(error)
    }
}

/** Cerrar el flujo cifrado escribe la etiqueta, pero no debe cerrar el archivo antes del `fsync`. */
private class SinCerrar(private val destino: OutputStream) : OutputStream() {
    override fun write(b: Int) = destino.write(b)

    override fun write(b: ByteArray, off: Int, len: Int) = destino.write(b, off, len)

    override fun flush() = destino.flush()

    override fun close() = destino.flush()
}
