package co.solventa.mobile.navigation

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import co.solventa.core.designsystem.SolventaTheme
import co.solventa.core.network.NetworkClient
import co.solventa.feature.consentimiento.DependenciasConsentimiento
import co.solventa.mobile.BuildConfig
import co.solventa.mobile.DebugMockInterceptor
import co.solventa.mobile.mock.MockBffMovil
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es")
class SolventaAppTest {
    @get:Rule
    val compose = createComposeRule()

    private val labels = listOf("Inicio", "Pólizas", "Avisos", "Perfil")

    @Test
    fun `los cuatro destinos cambian pantalla y seleccion y permiten volver a inicio`() {
        compose.setContent { SolventaTheme { SolventaApp() } }
        compose.onAllNodes(isSelectable()).assertCountEquals(4)
        assertDestination(0)
        listOf(1, 2, 3, 0).forEach { index ->
            compose.onNode(tab(labels[index])).assertIsDisplayed().performClick()
            assertDestination(index)
        }
    }

    @Test
    fun `reseleccionar un destino conserva su pantalla y seleccion unica`() {
        compose.setContent { SolventaTheme { SolventaApp() } }
        compose.onNode(tab("Pólizas")).performClick()
        repeat(2) { compose.onNode(tab("Pólizas")).performClick() }
        assertDestination(1)
    }

    @Test
    fun `sin identidad verificada inicio avisa que algunas funciones esperan la verificacion`() {
        compose.setContent { SolventaTheme { SolventaApp(identidadVerificada = false) } }
        compose.onNodeWithTag("aviso-identidad").assertIsDisplayed()
        compose.onNode(tab("Perfil")).performClick()
        compose.onNodeWithTag("aviso-identidad").assertDoesNotExist()
    }

    @Test
    fun `con identidad verificada no hay aviso`() {
        compose.setContent { SolventaTheme { SolventaApp(identidadVerificada = true) } }
        compose.onNodeWithTag("aviso-identidad").assertDoesNotExist()
    }

    @Test
    fun `perfil abre consentimiento y volver regresa a perfil con perfil seleccionado`() {
        val api = NetworkClient.create(
            BuildConfig.BASE_URL,
            listOf(DebugMockInterceptor(MockBffMovil { Instant.parse("2026-10-17T10:00:00Z") })),
            tokenAcceso = { null },
        )
        val dependencias = DependenciasConsentimiento(api)
        compose.setContent { SolventaTheme { SolventaApp(dependenciasConsentimiento = dependencias) } }
        compose.onNode(tab("Perfil")).performClick()
        compose.onNodeWithTag("ir-consentimiento").assertHeightIsAtLeast(48.dp).performClick()

        compose.onNodeWithTag("pantalla-consentimiento").assertIsDisplayed()
        compose.onNode(tab("Perfil")).assertIsSelected()

        compose.onNodeWithContentDescription("Volver a Perfil").performClick()
        compose.onNodeWithTag("screen-profile").assertIsDisplayed()
        compose.onNodeWithTag("pantalla-consentimiento").assertDoesNotExist()
    }

    @Test
    fun `sin dependencias de consentimiento perfil no ofrece el acceso`() {
        compose.setContent { SolventaTheme { SolventaApp() } }
        compose.onNode(tab("Perfil")).performClick()
        compose.onNodeWithTag("ir-consentimiento").assertDoesNotExist()
    }

    private fun tab(label: String): SemanticsMatcher = isSelectable() and hasText(label)

    private fun assertDestination(selectedIndex: Int) {
        compose.waitForIdle()
        val tag = "screen-${TopLevelDestination.entries[selectedIndex].route}"
        compose.onNodeWithTag(tag).assertIsDisplayed()
        compose.onNode(hasText(labels[selectedIndex]) and hasAnyAncestor(hasTestTag(tag))).assertIsDisplayed()
        compose.onNode(
            hasText("Esta sección estará disponible próximamente.") and hasAnyAncestor(hasTestTag(tag)),
        ).assertIsDisplayed()
        labels.forEachIndexed { index, label ->
            val node = compose.onNode(tab(label)).assertIsDisplayed()
            if (index == selectedIndex) node.assertIsSelected() else node.assertIsNotSelected()
        }
    }
}
