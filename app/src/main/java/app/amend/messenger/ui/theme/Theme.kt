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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

/**
 * A selectable colour theme. Only the accent colours change — the layout, type,
 * shapes, backgrounds and Efficiency mode stay exactly the same, so every screen
 * keeps working and only the mood of the colours shifts.
 */
class AppTheme(
    val id: String,
    val label: String,
    val emoji: String,
    val accentLight: Color,
    val accentDark: Color,
    val secondary: Color,
    val tertiary: Color,
    val isDefault: Boolean = false,
)

object AppThemes {
    val all = listOf(
        AppTheme("forest", "Forest", "🌿", Palette.Moss, Palette.LeafBright, Palette.Sun, Palette.Sky, isDefault = true),
        AppTheme("halloween", "Halloween", "🎃", Color(0xFFD9540B), Color(0xFFFF7A1A), Color(0xFF8E44AD), Color(0xFF66BB6A)),
        AppTheme("eid", "Eid", "🌙", Color(0xFF117A5B), Color(0xFF3FD9A0), Color(0xFFCBA14B), Color(0xFF66D1C4)),
        AppTheme("puja", "Puja", "🪷", Color(0xFFC0392B), Color(0xFFFF6F6B), Color(0xFFE2B007), Color(0xFFEF6C9A)),
        AppTheme("christmas", "Christmas", "🎄", Color(0xFF1B7a3d), Color(0xFF53D07E), Color(0xFFD7263D), Color(0xFFE8B923)),
        AppTheme("newyear", "New Year", "🎆", Color(0xFF8E44AD), Color(0xFFC792EA), Color(0xFFE2B007), Color(0xFF4FC3F7)),
        AppTheme("sakura", "Sakura", "🌸", Color(0xFFD6468A), Color(0xFFFF9EC4), Color(0xFF8E7CC3), Color(0xFF7FD1AE)),
        AppTheme("ocean", "Ocean", "🌊", Color(0xFF0C7C96), Color(0xFF34D0E6), Color(0xFF3FB6A8), Color(0xFF6C8EF5)),
        AppTheme("sunset", "Sunset", "🌅", Color(0xFFD85A3A), Color(0xFFFF9166), Color(0xFFE85C8A), Color(0xFFF2B33D)),
        AppTheme("autumn", "Autumn", "🍂", Color(0xFFB5641E), Color(0xFFF2A65A), Color(0xFFCE7B3C), Color(0xFF7BA05B)),
        AppTheme("lavender", "Lavender", "💜", Color(0xFF6C4FB3), Color(0xFFB79CF0), Color(0xFFE48FC4), Color(0xFF7FB7E8)),
        AppTheme("midnight", "Midnight", "🌌", Color(0xFF3B5BD9), Color(0xFF7D9BFF), Color(0xFF9B7BE0), Color(0xFF45C7C0)),
        AppTheme("rose", "Rosé", "🌹", Color(0xFFC0325B), Color(0xFFFF88A6), Color(0xFFCE8E4C), Color(0xFF8E7CC3)),
        AppTheme("mono", "Graphite", "⚫", Color(0xFF4A4A4A), Color(0xFFB8C0BC), Color(0xFF8A8F8C), Color(0xFF6E8F7E)),
    )

    private const val PREFS = "amend_theme"
    private var currentId by androidx.compose.runtime.mutableStateOf("forest")
    val current: AppTheme get() = all.firstOrNull { it.id == currentId } ?: all[0]

    fun load(context: android.content.Context) {
        currentId = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString("id", "forest") ?: "forest"
    }

    fun select(context: android.content.Context, id: String) {
        currentId = id
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString("id", id).apply()
    }
}

/** When true, animations and particle effects are reduced (Efficiency mode). */
val LocalEfficiency = staticCompositionLocalOf { false }
val LocalDark = staticCompositionLocalOf { true }

@Composable
fun AmendTheme(efficiency: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme() || efficiency // efficiency mode forces dark (saves power on OLED)
    CompositionLocalProvider(LocalEfficiency provides efficiency, LocalDark provides dark) {
        val base = if (dark) DarkColors else LightColors
        val theme = AppThemes.current
        val colors = if (theme.isDefault) base else {
            val accent = if (dark) theme.accentDark else theme.accentLight
            val onAccent = if (accent.luminance() > 0.5f) Color(0xFF0B130E) else Color.White
            base.copy(
                primary = accent,
                onPrimary = onAccent,
                primaryContainer = androidx.compose.ui.graphics.lerp(base.surface, accent, 0.30f),
                onPrimaryContainer = base.onSurface,
                secondary = theme.secondary,
                tertiary = theme.tertiary,
            )
        }
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
