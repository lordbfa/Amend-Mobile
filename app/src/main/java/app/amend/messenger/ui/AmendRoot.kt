package app.amend.messenger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.amend.messenger.AmendApp
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.ui.components.ParticleLayer
import app.amend.messenger.ui.components.rememberParticleField
import app.amend.messenger.ui.screens.ChatScreen
import app.amend.messenger.ui.screens.HomeScreen
import app.amend.messenger.ui.screens.OnboardingScreen
import app.amend.messenger.ui.screens.SosOverlay
import app.amend.messenger.ui.screens.AgeGateScreen
import app.amend.messenger.ui.screens.AchievementCelebration
import app.amend.messenger.ui.screens.FullScreenHost
import app.amend.messenger.util.AgeCheck
import app.amend.messenger.ui.screens.FakeCallOverlay
import app.amend.messenger.ui.screens.CallOverlay
import app.amend.messenger.ui.screens.WalkingReportsScreen
import app.amend.messenger.ui.screens.EcoMapScreen
import app.amend.messenger.ui.screens.SettingsScreen
import app.amend.messenger.ui.screens.SETTINGS_ROUTE
import app.amend.messenger.ui.screens.ECO_MAP_ROUTE
import app.amend.messenger.ui.screens.GROVE_ROUTE
import app.amend.messenger.ui.screens.GroveScreen
import app.amend.messenger.ui.screens.ECO_MAP_PLANT_ROUTE
import app.amend.messenger.ui.theme.LocalEfficiency
import app.amend.messenger.util.AppVisibility
import app.amend.messenger.util.StepTracker

sealed class Route {
    data object Home : Route()
    data class Chat(val peer: String) : Route()
}

/** Global particle overlay, so any screen can celebrate. */
val LocalParticles = androidx.compose.runtime.staticCompositionLocalOf<app.amend.messenger.ui.components.ParticleField> {
    error("no particle field")
}

@Composable
fun AmendRoot(app: AmendApp, repository: AmendRepository?, pendingPeer: MutableState<String?>) {
    val particles = rememberParticleField()
    particles.reduced = LocalEfficiency.current
    // Arabic and Urdu read right-to-left: mirror the whole layout
    val direction = if (I18n.isRtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
    androidx.compose.runtime.CompositionLocalProvider(LocalParticles provides particles, androidx.compose.ui.platform.LocalLayoutDirection provides direction) {
        Box(Modifier.fillMaxSize()) {
            if (AgeCheck.group == AgeCheck.Group.UNKNOWN) AgeGateScreen() else AnimatedContent(
                targetState = repository,
                transitionSpec = { (fadeIn(tween(700)) + scaleIn(initialScale = 1.08f)) togetherWith fadeOut(tween(400)) },
                label = "root",
            ) { repo ->
                if (repo == null) {
                    OnboardingScreen(onIdentity = { keys -> app.createIdentity(keys) })
                } else {
                    MainNavigation(app, repo, pendingPeer)
                }
            }
            ParticleLayer(particles, Modifier.fillMaxSize())
            // in the small picture-in-picture window only the video call (and an SOS) is shown
            val inPip = (androidx.compose.ui.platform.LocalContext.current as? app.amend.messenger.MainActivity)?.inPictureInPicture?.value == true
            // calls above the app, SOS countdown / active screen above everything
            CallOverlay(repository)
            SosOverlay(repository)
            if (!inPip) {
                FakeCallOverlay()
                AchievementCelebration(repository)
                // cameras, stories, Instants and photo viewers, above everything
                FullScreenHost()
            }
        }
    }
}

@Composable
private fun MainNavigation(app: AmendApp, repo: AmendRepository, pendingPeer: MutableState<String?>) {
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val nav = remember { SwipeNavState(stack) }

    LaunchedEffect(pendingPeer.value) {
        pendingPeer.value?.let { peer ->
            stack.removeAll { it is Route.Chat }
            nav.push(Route.Chat(peer))
            pendingPeer.value = null
        }
    }
    LaunchedEffect(stack.lastOrNull()) {
        AppVisibility.openPeer = (stack.lastOrNull() as? Route.Chat)?.peer
    }

    SwipeNavHost(nav, swipeEnabled = { route -> route is Route.Chat && route.peer != ECO_MAP_ROUTE && route.peer != ECO_MAP_PLANT_ROUTE }) { route ->
        when (route) {
            Route.Home -> HomeScreen(
                app = app,
                repo = repo,
                openChat = { peer -> nav.push(Route.Chat(peer)) },
            )
            is Route.Chat -> if (route.peer.startsWith(SETTINGS_ROUTE)) {
                SettingsScreen(app, repo, route.peer, open = { nav.push(Route.Chat(it)) }, onBack = { nav.pop() })
            } else if (route.peer == GROVE_ROUTE) {
                GroveScreen(repo, onBack = { nav.pop() }, openChat = { key -> nav.push(Route.Chat(key)) })
            } else if (route.peer == StepTracker.REPORTS_PEER) {
                WalkingReportsScreen(onBack = { nav.pop() })
            } else if (route.peer == ECO_MAP_ROUTE || route.peer == ECO_MAP_PLANT_ROUTE) {
                EcoMapScreen(repo, startPlanting = route.peer == ECO_MAP_PLANT_ROUTE, onBack = { nav.pop() })
            } else ChatScreen(
                repo = repo,
                peer = route.peer,
                onBack = { nav.pop() },
                onOpenChat = { key -> nav.push(Route.Chat(key)) },
            )
        }
    }
}

/**
 * Telegram-style navigation: a pushed screen slides in on a spring; going back
 * follows your finger (swipe right anywhere on the screen, or Android's
 * predictive back gesture) with the previous screen gliding in underneath.
 */
@androidx.compose.runtime.Stable
class SwipeNavState(val stack: androidx.compose.runtime.snapshots.SnapshotStateList<Route>) {
    /** 0 = top screen fully shown, 1 = top screen moved off to the right. */
    val offset = androidx.compose.animation.core.Animatable(0f)
    internal var scope: kotlinx.coroutines.CoroutineScope? = null
    private var popping = false

    fun push(route: Route) {
        if (stack.lastOrNull() == route) return
        val s = scope
        if (s == null) { stack.add(route); return }
        s.launch {
            // both changes land in the same frame: the new screen starts off-screen, no flicker
            offset.snapTo(1f)
            stack.add(route)
            offset.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 420f))
        }
    }

    fun pop() {
        if (stack.size < 2 || popping) return
        popping = true
        scope?.launch {
            try {
                offset.animateTo(1f, androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutLinearInEasing))
                if (stack.size > 1) stack.removeAt(stack.lastIndex)
                offset.snapTo(0f)
            } finally {
                popping = false
            }
        } ?: run { stack.removeAt(stack.lastIndex); popping = false }
    }

    suspend fun settleBack() {
        offset.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f))
    }
}

@Composable
private fun SwipeNavHost(nav: SwipeNavState, swipeEnabled: (Route) -> Boolean, content: @Composable (Route) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    nav.scope = scope
    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val stack = nav.stack
    val top = stack.last()
    val under = stack.getOrNull(stack.lastIndex - 1)
    // the screen underneath is only composed while it can be seen (during a swipe / animation)
    val showUnder by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { nav.offset.value > 0.001f && nav.stack.size > 1 } }

    // Android 13+ predictive back: the screen follows the system back swipe
    androidx.activity.compose.PredictiveBackHandler(enabled = stack.size > 1) { events ->
        try {
            events.collect { e -> nav.offset.snapTo(e.progress * 0.9f) }
            nav.pop()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            scope.launch { nav.settleBack() }
            throw e
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        // One keyed list: a screen keeps its composition when it moves between "under" and "top".
        val visible = if (under != null && showUnder) listOf(under, top) else listOf(top)
        visible.forEach { route ->
            androidx.compose.runtime.key(route) {
                val isTop = route == top
                val layer = if (isTop) {
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            // the first screen never moves; pushed screens slide
                            translationX = if (stack.size > 1) width * nav.offset.value else 0f
                            shadowElevation = if (stack.size > 1 && nav.offset.value > 0.001f) 18.dp.toPx() else 0f
                        }
                        .then(if (stack.size > 1 && swipeEnabled(top)) Modifier.swipeToGoBack(nav, width) else Modifier)
                } else {
                    Modifier.fillMaxSize()
                        .graphicsLayer { translationX = -width * 0.28f * (1f - nav.offset.value) }
                        .drawWithContent {
                            drawContent()
                            drawRect(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f * (1f - nav.offset.value)))
                        }
                }
                Box(layer) { holder.SaveableStateProvider(route.toString()) { content(route) } }
            }
        }
    }
}

/** Right-swipe anywhere (like Telegram). Vertical scrolling and left swipes (reply) still go to the content. */
private fun Modifier.swipeToGoBack(nav: SwipeNavState, width: Float): Modifier = pointerInput(nav, width) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        val velocity = VelocityTracker()
        var dx = 0f
        var dy = 0f
        var dragging = false
        velocity.addPosition(down.uptimeMillis, down.position)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val delta = change.positionChange()
            velocity.addPosition(change.uptimeMillis, change.position)
            if (!dragging) {
                dx += delta.x
                dy += delta.y
                if (kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) > kotlin.math.abs(dx)) return@awaitEachGesture
                if (dx < -slop) return@awaitEachGesture
                if (dx > slop * 1.5f && dx > kotlin.math.abs(dy) * 1.6f) dragging = true else continue
            }
            change.consume()
            val next = (nav.offset.value + delta.x / width).coerceIn(0f, 1f)
            nav.scope?.launch { nav.offset.snapTo(next) }
        }
        if (dragging) {
            val v = velocity.calculateVelocity().x
            if (nav.offset.value > 0.33f || v > 900f) nav.pop() else nav.scope?.launch { nav.settleBack() }
        }
    }
}
