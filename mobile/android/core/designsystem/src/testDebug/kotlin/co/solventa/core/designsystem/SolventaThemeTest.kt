package co.solventa.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SolventaThemeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `los colores preservan la paleta de marca y estados`() {
        val expected = listOf(
            Color(0xFF1E1B4B), Color(0xFF7C3AED), Color(0xFF6366F1), Color(0xFFF3F2FF),
            Color(0xFF10B981), Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFF111827),
            Color(0xFF374151), Color(0xFFFAFAFF),
        )
        val actual = listOf(
            SolventaColors.primary, SolventaColors.accent, SolventaColors.secondary, SolventaColors.surface,
            SolventaColors.success, SolventaColors.warning, SolventaColors.error, SolventaColors.textPrimary,
            SolventaColors.textSecondary, SolventaColors.background,
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `el espaciado conserva la escala y el objetivo tactil accesible`() {
        assertEquals(
            listOf(4.dp, 8.dp, 16.dp, 24.dp, 32.dp, 48.dp),
            listOf(SolventaSpacing.xs, SolventaSpacing.sm, SolventaSpacing.md,
                SolventaSpacing.lg, SolventaSpacing.xl, SolventaSpacing.xxl),
        )
        assertTrue(SolventaSpacing.touchTarget >= 48.dp)
        compose.setContent {
            SolventaTheme {
                Box(Modifier.sizeIn(
                    minWidth = SolventaSpacing.touchTarget,
                    minHeight = SolventaSpacing.touchTarget,
                ).testTag("touch-target"))
            }
        }
        compose.onNodeWithTag("touch-target").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `el tema se compone y entrega colores tipografia y formas al contenido`() {
        compose.setContent {
            SolventaTheme {
                val palette = MaterialTheme.colorScheme
                assertEquals(SolventaColors.accent, palette.primary)
                assertEquals(Color.White, palette.onPrimary)
                assertEquals(SolventaColors.surface, palette.primaryContainer)
                assertEquals(SolventaColors.primary, palette.onPrimaryContainer)
                assertEquals(SolventaColors.secondary, palette.secondary)
                assertEquals(Color.White, palette.onSecondary)
                assertEquals(SolventaColors.surface, palette.secondaryContainer)
                assertEquals(SolventaColors.primary, palette.onSecondaryContainer)
                assertEquals(SolventaColors.background, palette.background)
                assertEquals(SolventaColors.textPrimary, palette.onBackground)
                assertEquals(Color.White, palette.surface)
                assertEquals(SolventaColors.textPrimary, palette.onSurface)
                assertEquals(SolventaColors.textSecondary, palette.onSurfaceVariant)
                assertEquals(SolventaColors.error, palette.error)
                assertEquals(Color.Black, palette.onError)
                val type = MaterialTheme.typography
                val styles = listOf(type.displayLarge, type.headlineLarge, type.headlineMedium, type.headlineSmall,
                    type.bodyLarge, type.bodyMedium, type.bodySmall, type.labelLarge, type.labelMedium, type.labelSmall)
                val sizes = listOf(64, 48, 32, 24, 18, 16, 14, 14, 12, 12)
                val heights = listOf(72, 56, 40, 32, 28, 24, 20, 20, 16, 16)
                val weights = listOf(FontWeight.Bold, FontWeight.Bold, FontWeight.SemiBold, FontWeight.SemiBold,
                    FontWeight.Normal, FontWeight.Normal, FontWeight.Normal,
                    FontWeight.Medium, FontWeight.Medium, FontWeight.Medium)
                styles.forEachIndexed { index, style ->
                    assertEquals(sizes[index].sp, style.fontSize)
                    assertEquals(heights[index].sp, style.lineHeight)
                    assertEquals(weights[index], style.fontWeight)
                    assertEquals(FontFamily.SansSerif, style.fontFamily)
                }
                val shapes = MaterialTheme.shapes
                assertEquals(
                    listOf(4.dp, 8.dp, 12.dp, 16.dp, 24.dp).map {
                        androidx.compose.foundation.shape.RoundedCornerShape(it)
                    },
                    listOf(shapes.extraSmall, shapes.small, shapes.medium, shapes.large, shapes.extraLarge),
                )
                Text("Contenido del tema")
            }
        }
        compose.onNodeWithText("Contenido del tema").assertIsDisplayed()
    }
}
