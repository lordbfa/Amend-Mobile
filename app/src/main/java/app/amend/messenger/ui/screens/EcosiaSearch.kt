package app.amend.messenger.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amend.messenger.ui.Text

/**
 * "Search with Ecosia" on the chat list. Results open inside amend in a Custom Tab (the
 * phone's own browser engine), so amend never sees what is searched or which results are
 * opened, and Ecosia's ads keep funding its tree planting. amend isn't affiliated with Ecosia.
 */
object Ecosia {
    fun searchUrl(query: String): Uri = Uri.Builder()
        .scheme("https").authority("www.ecosia.org").path("search")
        .appendQueryParameter("method", "index")
        .appendQueryParameter("q", query.trim().take(500))
        .build()

    fun open(context: Context, query: String, toolbar: Color) {
        val uri = searchUrl(query)
        val colors = CustomTabColorSchemeParams.Builder().setToolbarColor(toolbar.toArgb()).build()
        val tab = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(colors)
            .setShowTitle(true)
            .setUrlBarHidingEnabled(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_ON)
            .build()
        try {
            tab.launchUrl(context, uri)
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                android.widget.Toast.makeText(context, "No browser found to show the results", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
fun EcosiaSearchBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val haptics = app.amend.messenger.ui.components.rememberHaptics()
    val colors = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    val search = {
        if (query.isNotBlank()) {
            haptics.tick()
            focus.clearFocus()
            Ecosia.open(context, query, colors.surface)
            query = ""
        }
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
                .background(colors.surfaceVariant).padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🌳", fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text("Search with Ecosia", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it.take(500) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isBlank()) {
                app.amend.messenger.ui.components.InfoTitle(
                    "Search with Ecosia", showTitle = false, info = "Ecosia uses its ad income to plant trees. Results open in your phone's browser engine; amend never sees your searches.",
                )
            }
            if (query.isNotBlank()) {
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFF2BB673))
                        .clickable { search() }.padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("Search", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
