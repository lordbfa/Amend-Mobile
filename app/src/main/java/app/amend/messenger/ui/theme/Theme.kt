package app.amend.messenger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import app.amend.messenger.R

object Palette {
    val Leaf = Color(0xFF2BB673)
    val LeafBright = Color(0xFF5BE39B)
    val Moss = Color(0xFF1C7A4E)
    val Sun = Color(0xFFFFC145)
    val Sky = Color(0xFF4FC3F7)
    val Blossom = Color(0xFFFF7AA2)
    val Soil = Color(0xFF7A5134)
    val Night = Color(0xFF06140E)
    val NightSurface = Color(0xFF0D2219)
    val NightVariant = Color(0xFF16352A)
    val Mist = Color(0xFFF2FAF4)
    val MistVariant = Color(0xFFE0F1E6)
    val Ink = Color(0xFF0B2A1C)
}

val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Palette.LeafBright,
    onPrimary = Color(0xFF00210F),
    primaryContainer = Palette.Moss,
    onPrimaryContainer = Color(0xFFD7FFE6),
    secondary = Palette.Sun,
    onSecondary = Color(0xFF2A1C00),
    tertiary = Palette.Sky,
    background = Palette.Night,
    onBackground = Color(0xFFE6F5EC),
    surface = Palette.NightSurface,
    onSurface = Color(0xFFE6F5EC),
    surfaceVariant = Palette.NightVariant,
    onSurfaceVariant = Color(0xFFA9C9B6),
    outline = Color(0xFF3C6250),
    error = Color(0xFFFF8A80),
)

private val LightColors: ColorScheme = lightColorScheme(
    primary = Palette.Moss,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBDF0D2),
    onPrimaryContainer = Palette.Ink,
    secondary = Color(0xFFB57800),
    onSecondary = Color.White,
    tertiary = Color(0xFF0277BD),
    background = Palette.Mist,
    onBackground = Palette.Ink,
    surface = Color.White,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.MistVariant,
    onSurfaceVariant = Color(0xFF3F6150),
    outline = Color(0xFF9CC2AD),
    error = Color(0xFFC62828),
)

private val AmendType = Typography(
    displaySmall = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
)

private val AmendShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

/** When true, animations and particle effects are reduced (Efficiency mode). */
val LocalEfficiency = staticCompositionLocalOf { false }
val LocalDark = staticCompositionLocalOf { true }

@Composable
fun AmendTheme(efficiency: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme() || efficiency // efficiency mode forces dark (saves power on OLED)
    CompositionLocalProvider(LocalEfficiency provides efficiency, LocalDark provides dark) {
        val colors = if (dark) DarkColors else LightColors
        MaterialTheme(
            colorScheme = colors,
            typography = AmendType,
            shapes = AmendShapes,
        ) {
            // Text and icons outside a Surface/Scaffold (e.g. full-screen pages) follow the theme too,
            // so they flip to light text in dark mode.
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides colors.onBackground, content = content)
        }
    }
}
