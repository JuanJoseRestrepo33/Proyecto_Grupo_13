package co.solventa.mobile.e2e

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Capturas de pantalla para la guía de usuario (#194, #203, #210, #214).
 *
 * Las toma `screencap` con el usuario `shell` y quedan en [CARPETA] del emulador; el workflow
 * `e2e-emulador` las baja con `adb pull` y las publica como artefacto. Nunca hacen fallar una prueba: si
 * la captura no se puede tomar, la prueba sigue. No se capturan pantallas con la clave escrita.
 */
object Capturas {
    const val CARPETA = "/data/local/tmp/capturas"

    // La captura es un extra para la guía: cualquier fallo se ignora para no tumbar la prueba.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun tomar(nombre: String) {
        try {
            val instrumentacion = InstrumentationRegistry.getInstrumentation()
            instrumentacion.waitForIdleSync()
            ejecutar("mkdir -p $CARPETA")
            ejecutar("screencap -p $CARPETA/$nombre.png")
        } catch (error: Exception) {
            // Sin captura; la prueba sigue.
        }
    }

    /** Espera a que el comando termine leyendo su salida completa. */
    private fun ejecutar(comando: String) {
        val salida = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(comando)
        ParcelFileDescriptor.AutoCloseInputStream(salida).use { it.readBytes() }
    }
}
