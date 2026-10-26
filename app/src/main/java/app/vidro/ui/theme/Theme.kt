package app.vidro.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object VidroPalette {
    val Lime = Color(0xFFD8F56A)
    val OnLime = Color(0xFF1A2200)
    val Bg = Color(0xFF10120E)
    val Bar = Color(0xF21B1E16)
    val Card = Color(0xFF22261C)
    val CardHigh = Color(0xFF2C3124)
    val Line = Color(0xFF3A4030)
    val Text = Color(0xFFF3F5EA)
    val Muted = Color(0xFFA8AD9C)
    val Danger = Color(0xFFFF8B7A)
}

private val Colors = darkColorScheme(
    primary = VidroPalette.Lime,
    onPrimary = VidroPalette.OnLime,
    secondary = VidroPalette.Lime,
    onSecondary = VidroPalette.OnLime,
    background = VidroPalette.Bg,
    onBackground = VidroPalette.Text,
    surface = VidroPalette.Card,
    onSurface = VidroPalette.Text,
    surfaceVariant = VidroPalette.CardHigh,
    onSurfaceVariant = VidroPalette.Muted,
    outline = VidroPalette.Line,
    error = VidroPalette.Danger,
    onError = Color(0xFF2B0000),
)

private val Type = Typography(
    headlineLarge = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.2).sp, color = VidroPalette.Text),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun VidroTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type, shapes = AppShapes, content = content)
}
