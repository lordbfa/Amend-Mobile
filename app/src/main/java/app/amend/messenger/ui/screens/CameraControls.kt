package app.amend.messenger.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amend.messenger.ui.Text
import app.amend.messenger.ui.components.rememberHaptics

/** Frosted round button for icons over photos and camera previews. Squeezes when pressed. */
@Composable
fun OverlayIconButton(icon: ImageVector, description: String, onClick: () -> Unit, size: Dp = 48.dp, iconRotation: Float = 0f) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "press")
    Box(
        Modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape).background(Color.Black.copy(alpha = 0.32f)).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size * 0.52f).graphicsLayer { rotationZ = iconRotation })
    }
}

/** Switch between front and back camera: the arrows spin half a turn each time. */
@Composable
fun CameraFlipButton(onFlip: () -> Unit, size: Dp = 56.dp) {
    val haptics = rememberHaptics()
    var turns by remember { mutableFloatStateOf(0f) }
    val angle by animateFloatAsState(turns * 180f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "flip")
    OverlayIconButton(Icons.Rounded.Cameraswitch, "Switch camera", onClick = { haptics.tick(); turns += 1f; onFlip() }, size = size, iconRotation = angle)
}

/** The big round shutter: white ring, green centre that shrinks while pressed. */
@Composable
fun ShutterButton(enabled: Boolean, onClick: () -> Unit, size: Dp = 80.dp) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val inner by animateFloatAsState(if (pressed) 0.72f else 0.86f, spring(Spring.DampingRatioMediumBouncy), label = "shutter")
    Box(
        Modifier.size(size).clip(CircleShape).border(4.dp, Color.White, CircleShape)
            .clickable(enabled = enabled, interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size * inner).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF5BE39B), Color(0xFF2BB673), Color(0xFF1C9A62))))
                .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
        )
    }
}

/** Camera bottom bar: shutter in the exact centre, flip on the right, on any screen width. */
@Composable
fun CameraBar(modifier: Modifier, captureEnabled: Boolean, onCapture: () -> Unit, onFlip: () -> Unit) {
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f))
        ShutterButton(captureEnabled, onCapture)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { CameraFlipButton(onFlip) }
    }
}

/** A pill with an icon and label, for actions over photos and stories. */
@Composable
fun OverlayChip(label: String, icon: ImageVector? = null, selected: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "chip")
    Row(
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(20.dp))
            .background(if (selected) Color.White.copy(alpha = 0.30f) else Color.Black.copy(alpha = 0.22f))
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = Color.White, fontSize = 14.sp, maxLines = 1)
    }
}
