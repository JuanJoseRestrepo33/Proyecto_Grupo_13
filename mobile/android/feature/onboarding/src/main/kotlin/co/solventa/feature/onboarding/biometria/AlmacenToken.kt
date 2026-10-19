package co.solventa.feature.onboarding.biometria

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Guarda el token de refresco cifrado para el ingreso biométrico. Solo se puede leer con un
 * [Cipher] que la biometría haya desbloqueado; escribir no la requiere, lo que permite guardar el
 * token rotado tras cada renovación sin pedir la huella dos veces.
 */
interface AlmacenToken {
    fun hayToken(): Boolean

    /** @return false si el dispositivo no pudo crear la llave (p. ej. no hay biometría enrolada). */
    fun guardar(token: String): Boolean

    /** Cipher para que BiometricPrompt lo desbloquee; null si no hay token o la llave quedó invalidada. */
    fun prepararLectura(): Cipher?

    /** @return null si el contenido no se pudo descifrar; en ese caso el token se borra. */
    fun leer(desbloqueado: Cipher): String?

    fun borrar()
}

/**
 * [AlmacenToken] con cifrado híbrido: una llave RSA del Android Keystore, cuya parte privada exige
 * biometría fuerte en cada uso y se invalida si se enrola otra huella o rostro, envuelve una llave
 * AES-GCM de un solo uso que cifra el token. En las preferencias solo queda el sobre cifrado.
 */
class AlmacenTokenKeystore(context: Context) : AlmacenToken {
    private val preferencias = context.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVEEDOR).apply { load(null) } }

    override fun hayToken() = preferencias.contains(CLAVE_SOBRE) && keyStore.containsAlias(ALIAS)

    // Cualquier fallo del Keystore (llave invalidada por un enrolamiento nuevo, sobre corrupto, proveedor
    // sin biometría) deja sin ingreso biométrico: se borra todo y el usuario vuelve a la contraseña.

    override fun guardar(token: String): Boolean = runCatching {
        val aes = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES).apply { init(BITS_AES) }.generateKey()
        val envoltura = Cipher.getInstance(RSA).apply { init(Cipher.ENCRYPT_MODE, llavePublica(), OAEP) }
        val gcm = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, aes) }
        val sobre = listOf(envoltura.doFinal(aes.encoded), gcm.iv, gcm.doFinal(token.toByteArray()))
        preferencias.edit().putString(CLAVE_SOBRE, sobre.joinToString(SEPARADOR, transform = ::base64)).apply()
    }.onFailure { borrar() }.isSuccess

    override fun prepararLectura(): Cipher? = if (!hayToken()) {
        null
    } else {
        runCatching {
            val privada = keyStore.getKey(ALIAS, null) as PrivateKey
            Cipher.getInstance(RSA).apply { init(Cipher.DECRYPT_MODE, privada, OAEP) }
        }.onFailure { borrar() }.getOrNull()
    }

    override fun leer(desbloqueado: Cipher): String? = runCatching {
        val sobre = preferencias.getString(CLAVE_SOBRE, null).orEmpty().split(SEPARADOR).map(::bytes)
        val (envuelta, iv, cifrado) = sobre
        val aes = SecretKeySpec(desbloqueado.doFinal(envuelta), KeyProperties.KEY_ALGORITHM_AES)
        val gcm = Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(BITS_TAG, iv)) }
        String(gcm.doFinal(cifrado))
    }.onFailure { borrar() }.getOrNull()

    override fun borrar() {
        preferencias.edit().remove(CLAVE_SOBRE).apply()
        runCatching { if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS) }
    }

    /**
     * La parte pública se copia fuera del Keystore: cifrar con ella no exige biometría (solo la
     * privada la exige) y evita las restricciones de digest MGF1 del proveedor.
     */
    private fun llavePublica(): PublicKey {
        if (!keyStore.containsAlias(ALIAS)) crearLlave()
        val publica = keyStore.getCertificate(ALIAS).publicKey
        return KeyFactory.getInstance(publica.algorithm).generatePublic(X509EncodedKeySpec(publica.encoded))
    }

    private fun crearLlave() {
        val propositos = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val especificacion = KeyGenParameterSpec.Builder(ALIAS, propositos)
            .setKeySize(BITS_RSA)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            // Sin duración de validez: cada descifrado exige una lectura biométrica fuerte.
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVEEDOR).apply { initialize(especificacion) }
            .generateKeyPair()
    }

    private fun base64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun bytes(texto: String) = Base64.decode(texto, Base64.NO_WRAP)

    private companion object {
        const val PROVEEDOR = "AndroidKeyStore"
        const val ALIAS = "solventa.ingreso_biometrico"
        const val ARCHIVO = "solventa.ingreso_biometrico"
        const val CLAVE_SOBRE = "token_refresco_cifrado"
        const val SEPARADOR = "."
        const val RSA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val BITS_RSA = 2048
        const val BITS_AES = 256
        const val BITS_TAG = 128
        val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    }
}
