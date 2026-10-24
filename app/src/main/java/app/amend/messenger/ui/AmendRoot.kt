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
import app.amend.messenger.AmendApp
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.ui.components.ParticleLayer
import app.amend.messenger.ui.components.rememberParticleField
import app.amend.messenger.ui.screens.ChatScreen
import app.amend.messenger.ui.screens.HomeScreen
import app.amend.messenger.ui.screens.OnboardingScreen
import app.amend.messenger.ui.theme.LocalEfficiency
import app.amend.messenger.util.AppVisibility

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
    androidx.compose.runtime.CompositionLocalProvider(LocalParticles provides particles) {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
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
        }
    }
}

@Composable
private fun MainNavigation(app: AmendApp, repo: AmendRepository, pendingPeer: MutableState<String?>) {
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val current = stack.last()

    LaunchedEffect(pendingPeer.value) {
        pendingPeer.value?.let { peer ->
            stack.removeAll { it is Route.Chat }
            stack.add(Route.Chat(peer))
            pendingPeer.value = null
        }
    }
    LaunchedEffect(current) {
        AppVisibility.openPeer = (current as? Route.Chat)?.peer
    }
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    AnimatedContent(
        targetState = current,
        transitionSpec = {
            if (targetState is Route.Chat) {
                slideInHorizontally(tween(380)) { it } + fadeIn() togetherWith slideOutHorizontally(tween(380)) { -it / 4 } + fadeOut()
            } else {
                slideInHorizontally(tween(380)) { -it / 4 } + fadeIn() togetherWith slideOutHorizontally(tween(380)) { it } + fadeOut()
            }
        },
        label = "nav",
    ) { route ->
        when (route) {
            Route.Home -> HomeScreen(
                app = app,
                repo = repo,
                openChat = { peer -> stack.add(Route.Chat(peer)) },
            )
            is Route.Chat -> ChatScreen(
                repo = repo,
                peer = route.peer,
                onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
            )
        }
    }
}
