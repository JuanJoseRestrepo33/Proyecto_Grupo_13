package co.solventa.feature.notificaciones

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificadorAndroidTest {
    private val contexto = ApplicationProvider.getApplicationContext<Application>()
    private val gestor = contexto.getSystemService(NotificationManager::class.java)
    private val notificador = NotificadorAndroid(contexto) { intentoApertura(contexto, Activity::class.java, it) }

    @Test
    fun `sin permiso no muestra nada`() {
        assertFalse(notificador.mostrar(notificacion("N-1")))

        assertTrue(shadowOf(gestor).allNotifications.isEmpty())
    }

    @Test
    fun `con permiso muestra titulo y cuerpo y al tocarla lleva el siniestro`() {
        shadowOf(contexto).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(notificador.mostrar(notificacion("N-1")))

        val mostrada = shadowOf(gestor).allNotifications.single()
        assertEquals("Tu siniestro cambió", mostrada.extras.getString("android.title"))
        assertEquals("Ya estamos evaluando tu caso.", mostrada.extras.getString("android.text"))
        assertEquals("siniestros", mostrada.channelId)
        val intento = shadowOf(mostrada.contentIntent).savedIntent
        assertEquals(AperturaPush("N-1", "SIN-N-1"), aperturaDe(intento))
    }

    @Test
    fun `con las notificaciones de la app apagadas no muestra nada`() {
        shadowOf(contexto).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(gestor).setNotificationsEnabled(false)

        assertFalse(notificador.mostrar(notificacion("N-1")))
    }

    @Test
    fun `la apertura se lee del intento y se consume`() {
        val intento = intentoApertura(contexto, Activity::class.java, notificacion("N-2"))

        assertEquals(AperturaPush("N-2", "SIN-N-2"), aperturaDe(intento))
        assertTrue(intento.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        consumirApertura(intento)
        assertNull(aperturaDe(intento))
        assertNull(aperturaDe(Intent()))
        assertNull(aperturaDe(null))
    }

    @Test
    fun `una notificacion que no es de siniestro abre la app sin destino`() {
        val intento = intentoApertura(contexto, Activity::class.java, notificacion("N-3", siniestro = null))

        assertEquals(AperturaPush("N-3", null), aperturaDe(intento))
    }

    @Test
    fun `las aperturas esperan hasta que la navegacion las atiende`() {
        val aperturas = AperturasPush()
        val primera = AperturaPush("N-1", "SIN-1")
        val segunda = AperturaPush("N-2", "SIN-2")

        aperturas.recibir(primera)
        aperturas.recibir(segunda)
        aperturas.atendida(primera)
        assertEquals("una vieja no borra la nueva", segunda, aperturas.pendiente)
        aperturas.atendida(segunda)
        assertNull(aperturas.pendiente)
    }

    @Test
    fun `el almacen guarda un token estable, las vistas por cliente y si se pidio el permiso`() {
        contexto.getSharedPreferences("push", Context.MODE_PRIVATE).edit().clear().commit()
        val almacen = AlmacenPushAndroid(contexto)
        val token = almacen.tokenInstalacion()

        assertTrue(token.startsWith("instalacion-"))
        assertEquals(token, AlmacenPushAndroid(contexto).tokenInstalacion())
        assertNull(almacen.vistas("cli-1"))
        almacen.guardarVistas("cli-1", setOf("N-1"))
        assertEquals(setOf("N-1"), almacen.vistas("cli-1"))
        assertNull(almacen.vistas("cli-2"))
        assertFalse(almacen.permisoPedido())
        almacen.marcarPermisoPedido()
        assertTrue(AlmacenPushAndroid(contexto).permisoPedido())
    }
}
