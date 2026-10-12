package co.solventa.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Fuente vigente: DesignSystem/Solventa_Design_System_v2.pdf.
object SolventaColors {
    val primary = Color(0xFF1E1B4B)
    val accent = Color(0xFF7C3AED)
    val secondary = Color(0xFF6366F1)
    val surface = Color(0xFFF3F2FF)
    val success = Color(0xFF10B981)
    val warning = Color(0xFFF59E0B)
    val error = Color(0xFFEF4444)
    val textPrimary = Color(0xFF111827)
    val textSecondary = Color(0xFF374151)
    val background = Color(0xFFFAFAFF)
}

object SolventaSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val touchTarget = 48.dp
}

private val colors = lightColorScheme(
    primary = SolventaColors.accent,
    onPrimary = Color.White,
    primaryContainer = SolventaColors.surface,
    onPrimaryContainer = SolventaColors.primary,
    secondary = SolventaColors.secondary,
    onSecondary = Color.White,
    secondaryContainer = SolventaColors.surface,
    onSecondaryContainer = SolventaColors.primary,
    background = SolventaColors.background,
    onBackground = SolventaColors.textPrimary,
    surface = Color.White,
    onSurface = SolventaColors.textPrimary,
    onSurfaceVariant = SolventaColors.textSecondary,
    error = SolventaColors.error,
    // Texto oscuro sobre error v2 para mantener contraste suficiente.
    onError = Color.Black,
)

private fun text(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = size.sp,
    lineHeight = height.sp,
    fontWeight = weight,
)

private val typography = Typography(
    displayLarge = text(64, 72, FontWeight.Bold),
    headlineLarge = text(48, 56, FontWeight.Bold),
    headlineMedium = text(32, 40, FontWeight.SemiBold),
    headlineSmall = text(24, 32, FontWeight.SemiBold),
    bodyLarge = text(18, 28),
    bodyMedium = text(16, 24),
    bodySmall = text(14, 20),
    labelLarge = text(14, 20, FontWeight.Medium),
    labelMedium = text(12, 16, FontWeight.Medium),
    labelSmall = text(12, 16, FontWeight.Medium),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun SolventaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
