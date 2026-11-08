package co.solventa.feature.siniestros.borrador

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Llave AES-256-GCM `solventa.borradores` del Android Keystore (por hardware cuando el equipo lo tiene).
 * No exige autenticación ni `setUnlockedDeviceRequired`: el envío ocurre también con la pantalla
 * bloqueada, y la misma política vale desde API 26. La protección viene del aislamiento de la app, de
 * la llave ligada al Keystore y de que la carpeta queda fuera de las copias de seguridad.
 */
class LlaveKeystore(private val alias: String = ALIAS) : FuenteLlave {
    private val candado = Any()

    override fun paraCifrar(): SecretKey = synchronized(candado) { existente() ?: crear() }

    override fun paraDescifrar(): SecretKey = synchronized(candado) { existente() ?: throw LlaveInexistente() }

    private fun existente(): SecretKey? {
        val almacen = KeyStore.getInstance(PROVEEDOR).apply { load(null) }
        return almacen.getKey(alias, null) as? SecretKey
    }

    private fun crear(): SecretKey {
        val especificacion = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(BITS_LLAVE)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVEEDOR)
            .apply { init(especificacion) }
            .generateKey()
    }

    companion object {
        const val ALIAS = "solventa.borradores"
        private const val PROVEEDOR = "AndroidKeyStore"
        private const val BITS_LLAVE = 256
    }
}
