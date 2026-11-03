package app.amend.messenger.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import app.amend.messenger.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amend.messenger.core.crypto.Nip19
import app.amend.messenger.core.nostr.KeyPair
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.eco.Species
import app.amend.messenger.ui.LocalParticles
import app.amend.messenger.ui.components.LivingSky
import app.amend.messenger.ui.components.ParticleKind
import app.amend.messenger.ui.components.PhotoPickerAvatar
import app.amend.messenger.ui.components.bouncy
import app.amend.messenger.ui.components.drawTree
import app.amend.messenger.ui.components.isNight
import app.amend.messenger.ui.components.rememberHaptics
import app.amend.messenger.ui.components.rememberHourOfDay
import app.amend.messenger.ui.components.rememberSway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step { WELCOME, PLANTING, NAME, RESTORE }

@Composable
fun OnboardingScreen(onIdentity: (KeyPair) -> AmendRepository) {
    var step by remember { mutableStateOf(Step.WELCOME) }
    var keys by remember { mutableStateOf<KeyPair?>(null) }
    val growth = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val particles = LocalParticles.current
    val hour = rememberHourOfDay()
    val sway by rememberSway()
    var groundCenter by remember { mutableStateOf(Offset.Zero) }

    Box(Modifier.fillMaxSize()) {
        LivingSky(Modifier.fillMaxSize(), hour = hour)
        // Rolling hill with the user's first tree
        Canvas(Modifier.fillMaxSize()) {
            val hillTop = size.height * 0.68f
            val hill = Path().apply {
                moveTo(0f, hillTop + 60f)
                quadraticBezierTo(size.width * 0.5f, hillTop - 70f, size.width, hillTop + 40f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            val night = isNight(hour)
            drawPath(hill, Brush.verticalGradient(
                if (night) listOf(Color(0xFF123526), Color(0xFF06140E)) else listOf(Color(0xFF7FD48C), Color(0xFF2E8B57)),
                startY = hillTop - 70f, endY = size.height,
            ))
            groundCenter = Offset(size.width / 2, hillTop - 30f)
            drawTree(Species.all[0], growth.value, 42, groundCenter, size.height * 0.42f, sway, night = night)
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = { (fadeIn(tween(500)) + slideInVertically { it / 6 }) togetherWith fadeOut(tween(250)) },
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            label = "onboarding",
        ) { s ->
            Column(
                Modifier.fillMaxSize().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (s) {
                    Step.WELCOME -> {
                        Spacer(Modifier.height(36.dp))
                        Text("amend", style = MaterialTheme.typography.displaySmall.copy(fontSize = 52.sp), color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Private messages that grow a forest.",
                            style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "No phone number. No email. No company server.\nYour identity is a key that only lives on your phone.",
                            style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.weight(1f))
                        val source = remember { MutableInteractionSource() }
                        Button(
                            onClick = {
                                haptics.heavy()
                                step = Step.PLANTING
                                scope.launch {
                                    val kp = withContext(Dispatchers.Default) { KeyPair.generate().also { it.publicKeyHex } }
                                    keys = kp
                                    growth.snapTo(0f)
                                    growth.animateTo(0.12f, tween(1400, easing = FastOutSlowInEasing))
                                    haptics.tick()
                                    particles.burst(groundCenter, ParticleKind.LEAVES, 30, 0.8f)
                                    growth.animateTo(0.42f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = 40f))
                                    haptics.success()
                                    delay(300)
                                    step = Step.NAME
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(58.dp).bouncy(source),
                            interactionSource = source,
                            shape = RoundedCornerShape(29.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2BB673), contentColor = Color.White),
                        ) { Text("🌱  Plant my seed", style = MaterialTheme.typography.titleMedium) }
                        TextButton(onClick = { step = Step.RESTORE }) {
                            Text("I already have a key", color = Color.White)
                        }
                        Spacer(Modifier.height(140.dp))
                    }

                    Step.PLANTING -> {
                        Spacer(Modifier.height(60.dp))
                        Text("Planting your seed…", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text("Creating your private key", color = Color.White.copy(alpha = 0.8f))
                    }

                    Step.NAME -> {
                        var name by remember { mutableStateOf("") }
                        var picture by remember { mutableStateOf("") }
                        Spacer(Modifier.height(40.dp))
                        Text("It sprouted! 🌿", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Every conversation grows its own shared garden, and your activity grows your personal forest.",
                            color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(20.dp))
                        keys?.let { kp -> PhotoPickerAvatar(kp.publicKeyHex, null, picture, onPicture = { picture = it }, size = 88.dp) }
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(40) },
                            label = { Text("What should friends call you?") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                        )
                        androidx.compose.material3.TextButton(onClick = { scanning = true }) {
                            Text("📱 Scan from my other phone", color = Color.White)
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                val kp = keys ?: return@Button
                                haptics.success()
                                particles.burst(groundCenter, ParticleKind.PETALS, 40, 1.1f)
                                val repo = onIdentity(kp)
                                scope.launch {
                                    repo.goOnline()
                                    repo.awaitOnline()
                                    repo.updateProfile(name.ifBlank { "Friend of the forest" }, "", "", -1f, picture)
                                    repo.publishInboxRelays()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(58.dp),
                            shape = RoundedCornerShape(29.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2BB673), contentColor = Color.White),
                        ) { Text("Enter the forest", style = MaterialTheme.typography.titleMedium) }
                        Spacer(Modifier.height(140.dp))
                    }

                    Step.RESTORE -> {
                        var input by remember { mutableStateOf("") }
                        var error by remember { mutableStateOf<String?>(null) }
                        var scanning by remember { mutableStateOf(false) }
                        if (scanning) {
                            androidx.compose.ui.window.Dialog(
                                onDismissRequest = { scanning = false },
                                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                            ) {
                                QrScannerScreen(myKey = "", linkMode = true, onFound = { input = it; error = null; scanning = false }, onClose = { scanning = false })
                            }
                        }
                        Spacer(Modifier.height(40.dp))
                        Text("Restore your key", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Paste your secret key (nsec1…). Works with any Nostr key — your messages will be fetched from the relays.",
                            color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(20.dp))
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it.trim(); error = null },
                            label = { Text("nsec1…") },
                            singleLine = true,
                            isError = error != null,
                            supportingText = { error?.let { Text(it) } },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                val sk = Nip19.parseSecretKey(input)
                                val kp = sk?.let { runCatching { KeyPair(it) }.getOrNull() }
                                if (kp == null) {
                                    haptics.reject()
                                    error = "That doesn't look like a valid secret key"
                                } else {
                                    haptics.success()
                                    val repo = onIdentity(kp)
                                    repo.goOnline()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(58.dp),
                            shape = RoundedCornerShape(29.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2BB673), contentColor = Color.White),
                        ) { Text("Restore", style = MaterialTheme.typography.titleMedium) }
                        TextButton(onClick = { step = Step.WELCOME }) { Text("Back", color = Color.White) }
                        Spacer(Modifier.height(120.dp))
                    }
                }
            }
        }
    }
}
