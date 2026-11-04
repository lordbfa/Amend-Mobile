package app.amend.messenger.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import app.amend.messenger.util.SecureScreen
import app.amend.messenger.util.findActivity

/** Keep clear of the status bar and camera cut-out. */
fun Modifier.safeTop(): Modifier = composed {
    windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top))
}

/** Keep clear of the navigation bar (3 buttons or gesture bar). */
fun Modifier.safeBottom(): Modifier = navigationBarsPadding()

/** Keep clear of the navigation bar and, when it's open, the keyboard. */
fun Modifier.safeBottomAndIme(): Modifier = navigationBarsPadding().imePadding()

/**
 * Full-screen screens (cameras, stories, Instants, photo viewers) are not drawn in a
 * separate dialog window any more: dialog windows got the wrong size on many phones, so
 * buttons slid under the navigation bar. Instead they are drawn on top of the app, inside
 * its own edge-to-edge window, where the system bars and keyboard are measured correctly on
 * every phone. [FullScreenHost] (placed once at the root) draws them; [FullScreenDialog]
 * puts one on screen for as long as it is in the composition.
 */
private class Overlay(val id: Any, var secure: Boolean, var onDismiss: () -> Unit, var content: @Composable () -> Unit)

private val overlays = mutableStateListOf<Overlay>()

@Composable
fun FullScreenDialog(onDismissRequest: () -> Unit, alwaysSecure: Boolean = false, content: @Composable () -> Unit) {
    val key = remember { Any() }
    val latestDismiss = rememberUpdatedState(onDismissRequest)
    val latestContent = rememberUpdatedState(content)
    DisposableEffect(key) {
        overlays.add(Overlay(key, alwaysSecure, { latestDismiss.value() }, { latestContent.value() }))
        onDispose { overlays.removeAll { it.id === key } }
    }
    SideEffect { overlays.firstOrNull { it.id === key }?.secure = alwaysSecure }
}

/** Draws open full-screen screens above everything else. Put it last in the root Box. */
@Composable
fun FullScreenHost() {
    val activity = LocalContext.current.findActivity()
    val showing = overlays.isNotEmpty()
    // light status-bar icons over the dark camera / story screens
    DisposableEffect(showing) {
        val ctl = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val before = ctl?.isAppearanceLightStatusBars
        val beforeNav = ctl?.isAppearanceLightNavigationBars
        if (showing && ctl != null) { ctl.isAppearanceLightStatusBars = false; ctl.isAppearanceLightNavigationBars = false }
        onDispose { if (showing && ctl != null) { before?.let { ctl.isAppearanceLightStatusBars = it }; beforeNav?.let { ctl.isAppearanceLightNavigationBars = it } } }
    }
    overlays.toList().forEachIndexed { i, o ->
        androidx.compose.runtime.key(o.id) {
            val fade = remember { Animatable(0f) }
            LaunchedEffect(Unit) { fade.animateTo(1f, tween(180)) }
            if (o.secure) SecureScreen(always = true) else SecureScreen()
            BackHandler(enabled = i == overlays.lastIndex) { o.onDismiss() }
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }
                    // swallow touches so nothing underneath reacts
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) { o.content() }
        }
    }
}
