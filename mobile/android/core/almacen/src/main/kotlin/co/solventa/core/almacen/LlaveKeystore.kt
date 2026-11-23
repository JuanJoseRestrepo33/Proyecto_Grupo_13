package co.solventa.core.almacen

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Llave AES-256-GCM [alias] del Android Keystore (por hardware cuando el equipo lo tiene). Cada almacén
 * tiene la suya: [ALIAS_BORRADORES] y [ALIAS_BILLETERA]. No exige autenticación ni
 * `setUnlockedDeviceRequired`: el envío de borradores ocurre también con la pantalla bloqueada, la
 * billetera se abre sin pedir la huella cada vez, y la misma política vale desde API 26. La protección
 * viene del aislamiento de la app, de la llave ligada al Keystore y de que los archivos quedan fuera de
 * las copias de seguridad.
 */
class LlaveKeystore(private val alias: String) : FuenteLlave {
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
        /** Borradores de siniestros (MOB-F05-02). */
        const val ALIAS_BORRADORES = "solventa.borradores"

        /** Billetera de pólizas (MOB-F03). */
        const val ALIAS_BILLETERA = "solventa.billetera"
        private const val PROVEEDOR = "AndroidKeyStore"
        private const val BITS_LLAVE = 256
    }
}
