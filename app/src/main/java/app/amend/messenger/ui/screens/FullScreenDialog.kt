package app.amend.messenger.ui.screens

import android.graphics.Color as AColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.amend.messenger.util.ScreenProtection
import app.amend.messenger.util.findActivity

/** Space taken by the status bar / notch (top), navigation bar (bottom) and keyboard (ime) in a full-screen dialog. */
data class DialogInsets(val top: Dp = 0.dp, val bottom: Dp = 0.dp, val ime: Dp = 0.dp)

val LocalDialogInsets = staticCompositionLocalOf { DialogInsets() }

/** Keep clear of the status bar / camera cut-out. */
fun Modifier.safeTop(): Modifier = composed { padding(top = LocalDialogInsets.current.top) }

/** Keep clear of the navigation bar (home / back buttons or gesture bar). */
fun Modifier.safeBottom(): Modifier = composed { padding(bottom = LocalDialogInsets.current.bottom) }

/** Keep clear of the navigation bar and, when it's open, the keyboard. */
fun Modifier.safeBottomAndIme(): Modifier = composed {
    val i = LocalDialogInsets.current
    padding(bottom = if (i.ime > i.bottom) i.ime else i.bottom)
}

/**
 * A dialog that really covers the whole screen, edge to edge, on every phone — and tells its
 * content exactly how much room the status bar, navigation bar and keyboard take
 * ([safeTop], [safeBottom], [safeBottomAndIme]). Compose dialogs otherwise get a window
 * that starts below the status bar but is as tall as the screen, so anything at the
 * bottom slides under the home button.
 */
@Composable
fun FullScreenDialog(onDismissRequest: () -> Unit, alwaysSecure: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // what the app's own window knows right now, so the first frame is already right
    val start = remember {
        val wi = context.findActivity()?.window?.decorView?.let { ViewCompat.getRootWindowInsets(it) }
        val bars = wi?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        with(density) {
            DialogInsets(
                top = (bars?.top ?: 0).toDp().coerceAtLeast(24.dp),
                bottom = (bars?.bottom ?: 0).toDp().coerceAtLeast(16.dp),
            )
        }
    }
    var insets by remember { mutableStateOf(start) }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            securePolicy = if (alwaysSecure || ScreenProtection.enabled) SecureFlagPolicy.SecureOn else SecureFlagPolicy.SecureOff,
        ),
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                window.setBackgroundDrawable(ColorDrawable(AColor.TRANSPARENT))
                WindowCompat.setDecorFitsSystemWindows(window, false)
                @Suppress("DEPRECATION")
                window.statusBarColor = AColor.TRANSPARENT
                @Suppress("DEPRECATION")
                window.navigationBarColor = AColor.TRANSPARENT
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                if (Build.VERSION.SDK_INT >= 28) {
                    window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
                }
                if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
                ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, wi ->
                    val bars = wi.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                    val ime = wi.getInsets(WindowInsetsCompat.Type.ime())
                    insets = with(density) {
                        DialogInsets(
                            top = bars.top.toDp().coerceAtLeast(start.top.coerceAtMost(48.dp)),
                            // never less than what the app window reported (some phones report 0 here)
                            bottom = maxOf(bars.bottom.toDp(), start.bottom),
                            ime = ime.bottom.toDp(),
                        )
                    }
                    ViewCompat.onApplyWindowInsets(v, wi)
                }
                ViewCompat.requestApplyInsets(window.decorView)
            }
            onDispose { window?.decorView?.let { ViewCompat.setOnApplyWindowInsetsListener(it, null) } }
        }
        CompositionLocalProvider(LocalDialogInsets provides insets) { content() }
    }
}
