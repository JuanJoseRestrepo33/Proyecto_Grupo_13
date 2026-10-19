package co.solventa.mobile.navigation

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import co.solventa.core.designsystem.SolventaTheme
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
