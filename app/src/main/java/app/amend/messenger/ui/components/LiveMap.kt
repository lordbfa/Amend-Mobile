package app.amend.messenger.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.amend.messenger.data.SosLocation
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/**
 * A small OpenStreetMap map (via osmdroid — open source, no Google) showing a
 * location with an accuracy circle. It follows the location as it updates, so it
 * shows a person's live position during an SOS. Tap it to open the full map app.
 *
 * Map data © OpenStreetMap contributors (ODbL); the credit is shown on the map
 * as the licence requires.
 */
@Composable
fun LiveMap(location: SosLocation, modifier: Modifier = Modifier, live: Boolean = false, zoom: Double = 16.5) {
    val context = LocalContext.current
    val markerColor = Color(0xFFE53935)
    val state = remember { arrayOfNulls<Any>(2) } // [Marker, Polygon] reused across updates

    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFFE8EDE9))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(false)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    isTilesScaledToDpi = true
                    isHorizontalMapRepetitionEnabled = false
                    isVerticalMapRepetitionEnabled = false
                    controller.setZoom(zoom)
                    controller.setCenter(GeoPoint(location.lat, location.lon))
                    // The map is a preview inside a message; touches go to the overlay on top.
                    setOnTouchListener { _, _ -> true }
                }
            },
            update = { map ->
                val point = GeoPoint(location.lat, location.lon)
                val circle = (state[1] as? Polygon) ?: Polygon(map).also {
                    it.fillPaint.color = markerColor.copy(alpha = 0.18f).toArgb()
                    it.outlinePaint.color = markerColor.copy(alpha = 0.6f).toArgb()
                    it.outlinePaint.strokeWidth = 3f
                    map.overlays.add(it)
                    state[1] = it
                }
                circle.setPoints(Polygon.pointsAsCircle(point, location.accuracyM.coerceIn(5f, 2_000f).toDouble()))
                val marker = (state[0] as? Marker) ?: Marker(map).also {
                    it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    it.setInfoWindow(null)
                    map.overlays.add(it)
                    state[0] = it
                }
                marker.position = point
                map.controller.animateTo(point)
                map.invalidate()
            },
        )
        // Tap anywhere: open the phone's map app (or the web map)
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(location.geoUri()))) }
                    .onFailure { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(location.mapLink()))) }
            }
        )
        if (live) LiveBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
        Text(
            "© OpenStreetMap contributors",
            fontSize = 9.sp, color = Color(0xFF333333),
            modifier = Modifier.align(Alignment.BottomEnd).background(Color(0xCCFFFFFF)).padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
    DisposableEffect(Unit) { onDispose { state.fill(null) } }
}

/** Pulsing red "LIVE" pill. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "liveAlpha")
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color(0xE6000000)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFFF3B30).copy(alpha = a)))
        Spacer(Modifier.width(5.dp))
        Text("LIVE", color = Color.White, fontSize = 10.sp)
    }
}
