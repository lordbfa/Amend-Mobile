package org.thoughtcrime.securesms.ui.theme

import androidx.compose.ui.graphics.Color
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.TextSecurePreferences.Companion.BLUE_ACCENT
import org.session.libsession.utilities.TextSecurePreferences.Companion.GREEN_ACCENT
import org.session.libsession.utilities.TextSecurePreferences.Companion.ORANGE_ACCENT
import org.session.libsession.utilities.TextSecurePreferences.Companion.PINK_ACCENT
import org.session.libsession.utilities.TextSecurePreferences.Companion.PURPLE_ACCENT
import org.session.libsession.utilities.TextSecurePreferences.Companion.RED_ACCENT
import org.session.libsession.utilities.TextSecurePreferences.Companion.YELLOW_ACCENT
import org.session.libsession.utilities.TextSecurePreferences.Companion.AMEND_ACCENT
import org.session.libsession.utilities.TextSecurePreferences.Companion.LIME_ACCENT


/**
 * Returns the compose theme based on saved preferences
 * Some behaviour is hardcoded to cater for legacy usage of people with themes already set
 * But future themes will be picked and set directly from the "Appearance" screen
 */
fun TextSecurePreferences.getColorsProvider(): ThemeColorsProvider {
    val selectedTheme = getThemeStyle()

    val isOcean = "ocean" in selectedTheme
    val isAmend = "amend" in selectedTheme

    // get the chosen accent color from the preferences, falling back to the theme family's own accent
    val selectedAccent = accentColor(
        default = when {
            isAmend -> primaryAmend
            isOcean -> primaryBlue
            else -> primaryGreen
        }
    )

    val createLight: (Color) -> ThemeColors = when {
        isAmend -> ::AmendLight
        isOcean -> ::OceanLight
        else -> ::ClassicLight
    }
    val createDark: (Color) -> ThemeColors = when {
        isAmend -> ::AmendDark
        isOcean -> ::OceanDark
        else -> ::ClassicDark
    }

    return when {
        getFollowSystemSettings() -> FollowSystemThemeColorsProvider(
            light = createLight(selectedAccent),
            dark = createDark(selectedAccent)
        )
        "light" in selectedTheme -> ThemeColorsProvider(createLight(selectedAccent))
        else -> ThemeColorsProvider(createDark(selectedAccent))
    }
}

fun TextSecurePreferences.accentColor(default: Color = primaryGreen): Color = when(getSelectedAccentColor()) {
    GREEN_ACCENT -> primaryGreen
    BLUE_ACCENT -> primaryBlue
    PURPLE_ACCENT -> primaryPurple
    PINK_ACCENT -> primaryPink
    RED_ACCENT -> primaryRed
    ORANGE_ACCENT -> primaryOrange
    YELLOW_ACCENT -> primaryYellow
    AMEND_ACCENT -> primaryAmend
    LIME_ACCENT -> primaryLime
    else -> default
}
