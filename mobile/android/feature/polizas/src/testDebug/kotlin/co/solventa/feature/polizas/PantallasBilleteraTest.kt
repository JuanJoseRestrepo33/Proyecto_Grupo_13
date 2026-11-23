package co.solventa.feature.polizas

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import co.solventa.feature.polizas.sinsesion.MarcoSinSesion
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.core.network.EstadoPoliza
import co.solventa.core.network.PolizaDetalle
import co.solventa.feature.polizas.billetera.BilleteraGuardada
import co.solventa.feature.polizas.comun.Pestana
import co.solventa.feature.polizas.credencial.EstadoCredencial
import co.solventa.feature.polizas.credencial.PantallaCredencial
import co.solventa.feature.polizas.credencial.SinCredencial
import co.solventa.feature.polizas.detalle.EstadoDetallePoliza
import co.solventa.feature.polizas.detalle.PantallaDetallePoliza
import co.solventa.feature.polizas.lista.EstadoMisPolizas
import co.solventa.feature.polizas.lista.ModoPolizas
import co.solventa.feature.polizas.lista.PantallaMisPolizas
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Billetera y credencial sin conexión (MOB-F03): las pantallas con cada estado, TalkBack y 48 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es-w411dp-h914dp")
class PantallasBilleteraTest {
    @get:Rule
    val compose = createComposeRule()

    private var actualizar = 0
    private var cotizar = 0
    private var credencial: String? = null
    private var abierta: String? = null
    private var reintentar = 0
    private var verCredencial = 0
    private var volvio = false

    private val hoy = LocalDate.parse("2026-11-18")
    private val billetera = billeteraDePrueba()

    private fun lista(estado: EstadoMisPolizas) {
        compose.setContent {
            SolventaTheme {
                PantallaMisPolizas(
                    estado = estado,
                    onActualizar = { actualizar += 1 },
                    onElegir = {},
                    onAbrir = { abierta = it },
                    onCotizar = { cotizar += 1 },
                    onCredencial = { credencial = it },
                    zona = ZONA,
                )
            }
        }
    }

    private fun sinConexion(modo: ModoPolizas = ModoPolizas.SIN_CONEXION, guardada: BilleteraGuardada? = billetera) =
        EstadoMisPolizas(
            cargando = false,
            modo = modo,
            billetera = guardada,
            billeteraLeida = true,
            hoy = hoy,
        )

    private fun detalle(estado: EstadoDetallePoliza) {
        compose.setContent {
            SolventaTheme {
                PantallaDetallePoliza(
                    estado = estado,
                    onVolver = { volvio = true },
                    onReintentar = { reintentar += 1 },
                    onDescargar = {},
                    onAvisoMostrado = {},
                    onCredencial = { verCredencial += 1 },
                    zona = ZONA,
                )
            }
        }
    }

    private fun credencial(estado: EstadoCredencial) {
        compose.setContent {
            SolventaTheme {
                PantallaCredencial(
                    estado,
                    onVolver = { volvio = true },
                    onReintentar = { reintentar += 1 },
                    zona = ZONA,
                )
            }
        }
    }

    private fun conTalkBack(texto: String) = hasContentDescription(texto, substring = true)

    // ── Mis pólizas ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `con conexion dice desde cuando esta disponible sin conexion y ofrece la credencial de las vigentes`() {
        lista(
            EstadoMisPolizas(
                polizas = listOf(VIGENTE, VENCIDA, POR_INICIAR),
                cargando = false,
                billetera = billetera,
                billeteraLeida = true,
                hoy = hoy,
            ),
        )

        compose.onNode(conTalkBack("Disponible sin conexión · sincronizada hoy 08:14")).assertIsDisplayed()
        compose.onNodeWithTag("franja-sin-conexion").assertDoesNotExist()
        compose.onNodeWithTag("credencial-$NUMERO")
            .assertHeightIsAtLeast(48.dp)
            .assert(conTalkBack("Ver la credencial de la póliza $NUMERO"))
            .performClick()
        assertEquals(NUMERO, credencial)
        assertEquals("tocar la credencial no abre el detalle", null, abierta)
    }

    @Test
    fun `sin una sincronizacion completa dice que aun no esta disponible sin conexion`() {
        lista(EstadoMisPolizas(polizas = listOf(VIGENTE), cargando = false, billeteraLeida = true, hoy = hoy))

        compose.onNodeWithTag("billetera-no-disponible").assert(conTalkBack("Aún no disponible sin conexión"))
    }

    @Test
    fun `sin conexion se ve la billetera con la franja y la fecha, legible por TalkBack`() {
        lista(sinConexion())

        compose.onNodeWithTag("franja-sin-conexion")
            .assertIsDisplayed()
        compose.onNode(conTalkBack("Sin conexión · datos del 18 nov")).assertExists()
        compose.onNode(conTalkBack("08:14")).assertExists()
        compose.onNodeWithTag("poliza-$NUMERO").assertIsDisplayed()
        compose.onNodeWithTag("credencial-$NUMERO").assertIsDisplayed()
        compose.onNodeWithTag("billetera-disponible").assertDoesNotExist()
    }

    @Test
    fun `un tiempo agotado dice no pudimos actualizar y ofrece reintentar`() {
        lista(sinConexion(ModoPolizas.SIN_ACTUALIZAR))

        compose.onNode(conTalkBack("No pudimos actualizar · datos del 18 nov")).assertExists()
        compose.onNodeWithTag("franja-reintentar").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, actualizar)
    }

    @Test
    fun `mientras sincroniza al reconectar dice que esta actualizando y sigue mostrando lo guardado`() {
        lista(sinConexion().copy(sincronizando = true))

        compose.onNode(conTalkBack("Conexión recuperada · actualizando tus pólizas…")).assertExists()
        compose.onNodeWithTag("poliza-$NUMERO").assertIsDisplayed()
    }

    @Test
    fun `sin billetera guardada pide conexion una vez y ofrece reintentar`() {
        lista(sinConexion(ModoPolizas.SIN_BILLETERA, guardada = null))

        compose.onNodeWithText("Necesitas conexión una vez para guardar tus pólizas en este teléfono.")
            .assertIsDisplayed()
        compose.onNodeWithTag("reintentar").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, actualizar)
    }

    @Test
    fun `una billetera sin polizas muestra el estado vacio con la fecha y cotizar necesita conexion`() {
        lista(sinConexion(guardada = billeteraDePrueba(polizas = emptyList(), detalles = emptyList())))

        compose.onNodeWithTag("sin-polizas").assertIsDisplayed()
        compose.onNode(conTalkBack("Sin conexión · datos del 18 nov")).assertExists()
        compose.onNodeWithTag("cotizar").assertIsNotEnabled()
        compose.onNode(conTalkBack("Necesitas conexión para cotizar.")).assertExists()
    }

    @Test
    fun `una poliza vencida por fecha despues de sincronizar se ve vencida y sin credencial`() {
        lista(sinConexion().copy(hoy = LocalDate.parse("2027-10-20"), pestana = Pestana.TODAS))

        compose.onNode(conTalkBack("Seguro de Auto — Plan Full. Estado: Vencida.")).assertExists()
        compose.onNodeWithTag("credencial-$NUMERO").assertDoesNotExist()
    }

    // ── Detalle ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `el detalle sin conexion muestra lo guardado, la credencial y el PDF inactivo con su explicacion`() {
        detalle(
            EstadoDetallePoliza(
                detalle = COMPLETA,
                cargando = false,
                sinConexion = true,
                sincronizadaEn = SINCRONIZADA,
            ),
        )

        compose.onNodeWithTag("franja-sin-conexion").assertIsDisplayed()
        compose.onNodeWithTag("coberturas").assertExists()
        compose.onNodeWithTag("descargar-pdf").performScrollTo().assertIsNotEnabled()
        compose.onNode(conTalkBack("Necesitas conexión para descargar el documento.")).assertExists()
        compose.onNodeWithTag("ver-credencial").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, verCredencial)
    }

    @Test
    fun `el detalle con conexion ofrece la credencial de una vigente y la descarga activa`() {
        detalle(EstadoDetallePoliza(detalle = COMPLETA, cargando = false))

        compose.onNodeWithTag("franja-sin-conexion").assertDoesNotExist()
        compose.onNodeWithTag("ver-credencial").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("descargar-pdf").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `un detalle no guardado dice que necesita conexion`() {
        detalle(EstadoDetallePoliza(cargando = false, detalleNoGuardado = true))

        compose.onNodeWithText("Necesitas conexión para ver este detalle.").assertIsDisplayed()
        compose.onNodeWithTag("volver-lista").performClick()
        assertEquals(true, volvio)
    }

    // ── Credencial ───────────────────────────────────────────────────────────────────────────

    private fun estadoCredencial(detalle: PolizaDetalle = COMPLETA, sinConexion: Boolean = false) = EstadoCredencial(
        cargando = false,
        detalle = detalle,
        titular = "Ana María Rojas",
        sincronizadaEn = SINCRONIZADA,
        sinConexion = sinConexion,
    )

    @Test
    fun `la credencial sin conexion lleva todo lo necesario para contrastarla y la franja`() {
        credencial(estadoCredencial(sinConexion = true))

        compose.onNodeWithTag("franja-solo-sin-conexion").assertIsDisplayed()
        compose.onNode(conTalkBack("Sin conexión")).assertExists()
        compose.onNodeWithText("Seguro de Auto — Plan Full").assertIsDisplayed()
        compose.onNodeWithText(NUMERO).assertIsDisplayed()
        compose.onNode(conTalkBack("Titular: Ana María Rojas")).assertExists()
        compose.onNode(conTalkBack("Vigencia: 15 oct. 2026 – 14 oct. 2027")).assertExists()
        compose.onNode(conTalkBack("Estado: Vigente según datos del 18 nov. 2026")).assertExists()
        compose.onNode(conTalkBack("Responsabilidad civil: $1.000.000.000")).assertExists()
        compose.onNode(conTalkBack("Valor asegurado: $60.000.000")).assertExists()
        compose.onNode(conTalkBack("Deducible: 5% del valor asegurado")).assertExists()
        compose.onNodeWithTag("credencial-pie").performScrollTo()
            .assert(hasText("Datos sincronizados el 18 nov. 2026, 08:14"))
    }

    @Test
    fun `con conexion la credencial no lleva la franja`() {
        credencial(estadoCredencial())

        compose.onNodeWithTag("franja-solo-sin-conexion").assertDoesNotExist()
        compose.onNodeWithTag("credencial").assertIsDisplayed()
    }

    @Test
    fun `una credencial con datos faltantes dice no disponible, nunca vacio`() {
        val sinNombre = VIGENTE.copy(nombreProducto = null, producto = "hogar")
        val semilla = PolizaDetalle.de(sinNombre, emptyList(), null, null, null)
        credencial(estadoCredencial(semilla).copy(titular = null))

        compose.onNodeWithText("Seguro de hogar").assertIsDisplayed()
        compose.onNode(conTalkBack("Titular: No disponible")).assertExists()
        compose.onNodeWithText("Esta póliza no tiene coberturas registradas.").assertExists()
        compose.onNode(conTalkBack("Valor asegurado: No disponible")).assertExists()
        compose.onNode(conTalkBack("Deducible: No disponible")).assertExists()
        compose.onNode(conTalkBack("null")).assertDoesNotExist()
    }

    @Test
    fun `sin la poliza guardada ni red explica que necesita conexion y ofrece reintentar`() {
        credencial(EstadoCredencial(cargando = false, sinConexion = true, motivo = SinCredencial.NECESITA_CONEXION))

        compose.onNodeWithTag("sin-credencial").assertIsDisplayed()
        compose.onNodeWithTag("reintentar").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, reintentar)
    }

    @Test
    fun `una poliza que ya no se muestra vigente no tiene credencial`() {
        credencial(
            estadoCredencial(COMPLETA.copy(estado = EstadoPoliza.VENCIDA)).copy(motivo = SinCredencial.NO_VIGENTE),
        )

        compose.onNodeWithText("La credencial solo está disponible para pólizas vigentes.").assertIsDisplayed()
        compose.onNodeWithTag("credencial").assertDoesNotExist()
    }

    // ── Sin sesión (entrega 2) ───────────────────────────────────────────────────────────────

    private var ingresar = 0

    private fun sinSesion(conectado: Boolean) {
        compose.setContent {
            SolventaTheme {
                MarcoSinSesion(conectado, billetera.momento, ZONA, onIngresar = { ingresar += 1 }) {
                    PantallaMisPolizas(
                        estado = sinConexion(),
                        onActualizar = { actualizar += 1 },
                        onElegir = {},
                        onAbrir = { abierta = it },
                        onCotizar = {},
                        onCredencial = { credencial = it },
                        zona = ZONA,
                        soloLectura = true,
                    )
                }
            }
        }
    }

    @Test
    fun `sin sesion la billetera se lee sin actualizar, con la franja una sola vez e Ingresar arriba`() {
        sinSesion(conectado = false)

        compose.onNodeWithTag("franja-sin-conexion").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("franja-sin-conexion")).assertCountEquals(1)
        compose.onNodeWithTag("actualizar").assertDoesNotExist()
        compose.onNodeWithTag("credencial-$NUMERO").performScrollTo().performClick()
        assertEquals(NUMERO, credencial)
        compose.onNodeWithTag("sin-sesion-ingresar").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, ingresar)
    }

    @Test
    fun `sin sesion y con conexion la franja invita a ingresar para actualizar`() {
        sinSesion(conectado = true)

        compose.onNode(hasContentDescription("Ya tienes conexión · Ingresa para actualizar")).assertIsDisplayed()
        compose.onNodeWithTag("sin-sesion-ingresar").assertDoesNotExist()
        compose.onNodeWithTag("franja-ingresar").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, ingresar)
    }
}
