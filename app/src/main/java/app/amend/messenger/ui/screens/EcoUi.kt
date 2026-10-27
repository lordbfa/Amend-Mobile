package app.amend.messenger.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import app.amend.messenger.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amend.messenger.core.json.Json
import app.amend.messenger.core.json.str
import app.amend.messenger.data.AlertLevel
import app.amend.messenger.data.AmendRepository
import app.amend.messenger.data.ChatMessage
import app.amend.messenger.data.EcoAlerts
import app.amend.messenger.data.EcoCategory
import app.amend.messenger.data.EcoReport
import app.amend.messenger.data.EcoReports
import app.amend.messenger.ui.components.GlassCard
import app.amend.messenger.ui.components.rememberHaptics
import app.amend.messenger.util.EcoMap
import app.amend.messenger.util.EcoWeather
import app.amend.messenger.util.SosManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pseudo chat id that opens the full-screen eco map. */
const val ECO_MAP_ROUTE = "amend:ecomap"
const val ECO_MAP_PLANT_ROUTE = "amend:ecomap:plant"

// ======================================================================= remote images

private object ImageCache {
    val http by lazy { OkHttpClient() }
    val cache = LruCache<String, ImageBitmap>(40)
}

/** Loads a public https image (report photos), scaled down, cached in memory. */
@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    var img by remember(url) { mutableStateOf(url?.let { ImageCache.cache.get(it) }) }
    LaunchedEffect(url) {
        if (url == null || img != null) return@LaunchedEffect
        img = withContext(Dispatchers.IO) {
            runCatching {
                ImageCache.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    val bytes = r.body?.bytes() ?: return@use null
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    var sample = 1
                    while (opts.outWidth / sample > 1080) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }
            }.getOrNull()
        }?.also { ImageCache.cache.put(url, it) }
    }
    Box(modifier.background(Color(0x22000000)), contentAlignment = Alignment.Center) {
        val i = img
        if (i != null) Image(i, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        else if (url != null) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}

/** Re-encodes a photo to JPEG ≤ 1280 px. This also drops all EXIF data (camera GPS, device…). */
private fun compressPhoto(context: Context, uri: Uri): ByteArray? = runCatching { compressPhotoOrThrow(context, uri) }.getOrNull()

private fun compressPhotoOrThrow(context: Context, uri: Uri): ByteArray? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    var sample = 1
    while (maxOf(opts.outWidth, opts.outHeight) / sample > 2560) sample *= 2
    val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
    // respect the camera's rotation flag, since we drop EXIF
    val rotation = runCatching {
        context.contentResolver.openInputStream(uri)?.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees } ?: 0
    }.getOrDefault(0)
    val scale = minOf(1f, 1280f / maxOf(raw.width, raw.height))
    val m = android.graphics.Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
    val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    return ByteArrayOutputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 80, out); out.toByteArray() }
}

// ======================================================================= weather & air

@Composable
fun WeatherCard(repo: AmendRepository) {
    val context = LocalContext.current
    val snap by EcoWeather.state.collectAsStateWithLifecycle()
    val loading by EcoWeather.loading.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var hasPermission by remember { mutableStateOf(EcoWeather.hasLocationPermission(context)) }
    var alertsOn by remember { mutableStateOf(EcoWeather.alertsOn(context)) }
    var tellPeople by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        hasPermission = r.values.any { it }
        if (hasPermission) scope.launch { EcoWeather.refresh(context, force = true) }
    }
    LaunchedEffect(hasPermission) {
        EcoWeather.restore(context)
        if (hasPermission) EcoWeather.refresh(context)
        EcoWeather.schedule(context)
    }
    val alerts = remember(snap) { snap?.let { EcoAlerts.alerts(it) }.orEmpty() }

    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Weather & air near you", style = MaterialTheme.typography.titleMedium)
                    snap?.place?.takeIf { it.isNotBlank() }?.let { Text("📍 $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { scope.launch { EcoWeather.refresh(context, force = true) } }) { Icon(Icons.Rounded.Refresh, contentDescription = "Refresh") }
            }
            if (!hasPermission) {
                Text(
                    "Allow location to get heatwave, heavy-rain, river-flood, storm and air-quality alerts for where you are. Only your area (rounded to ~1 km) is sent to Open-Meteo.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { launcher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) }, shape = RoundedCornerShape(16.dp)) {
                    Text("Allow location")
                }
                return@Column
            }
            val s = snap
            if (s == null) {
                Text("Getting the forecast…", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (emoji, words) = EcoAlerts.describe(s.weatherCode)
                Text(emoji, fontSize = 40.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.tempC?.let { "%.0f°C".format(Locale.US, it) } ?: "—", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        words + (s.feelsLikeC?.let { " · feels %.0f°".format(Locale.US, it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                s.usAqi?.let { aqi -> AqiBadge(aqi) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                s.rainTodayMm?.let { MiniStat("🌧️", "%.0f mm".format(Locale.US, it), "rain today") }
                s.maxGustKmh?.let { MiniStat("💨", "%.0f km/h".format(Locale.US, it), "gusts") }
                s.uvMax?.let { MiniStat("☀️", "%.0f".format(Locale.US, it), "UV max") }
                s.riverNow?.let { MiniStat("🏞️", "%.0f m³/s".format(Locale.US, it), "river") }
            }
            if (alerts.isEmpty()) {
                Text("✅ No warnings for your area right now.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF2BB673))
            } else {
                alerts.forEach { AlertRow(it) }
                Button(
                    onClick = { tellPeople = true },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("💚 Tell my people I'm safe") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Notify me about new warnings", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(checked = alertsOn, onCheckedChange = { alertsOn = it; EcoWeather.setAlertsOn(context, it) })
            }
            Text(
                "Data: Open-Meteo.com (CC BY 4.0). An early nudge — always follow official warnings (e.g. BMD, FFWC, FMI).",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (tellPeople) {
        TellPeopleDialog(repo, EcoAlerts.safeMessage(alerts, snap?.place ?: ""), onDismiss = { tellPeople = false })
    }
}

@Composable
private fun AqiBadge(aqi: Int) {
    val color = when {
        aqi <= 50 -> Color(0xFF2BB673)
        aqi <= 100 -> Color(0xFFF9A825)
        aqi <= 150 -> Color(0xFFFF7043)
        aqi <= 200 -> Color(0xFFE53935)
        else -> Color(0xFF8E24AA)
    }
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("AQI $aqi", color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Text(EcoAlerts.aqiLabel(aqi), color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun MiniStat(emoji: String, value: String, label: String) {
    Column {
        Text("$emoji $value", style = MaterialTheme.typography.labelLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AlertRow(a: app.amend.messenger.data.EcoAlert) {
    val color = when (a.level) { AlertLevel.DANGER -> Color(0xFFE53935); AlertLevel.WARNING -> Color(0xFFFF8F00); AlertLevel.INFO -> Color(0xFF1E88E5) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.12f)).padding(10.dp)) {
        Text(a.emoji, fontSize = 22.sp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(a.title, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(a.advice, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Pick friends and send them one "I'm safe" message each. */
@Composable
private fun TellPeopleDialog(repo: AmendRepository, text: String, onDismiss: () -> Unit) {
    val conversations by repo.conversations.collectAsStateWithLifecycle()
    val friends = remember(conversations) {
        conversations.values.filter { repo.isAccepted(it.peer) && it.peer != repo.me }.sortedByDescending { it.messages.lastOrNull()?.createdAt ?: 0 }
    }
    val chosen = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(friends) { friends.forEach { chosen.putIfAbsent(it.peer, true) } }
    var message by remember { mutableStateOf(text) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tell your people you're safe") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(message, { message = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Spacer(Modifier.height(8.dp))
                if (friends.isEmpty()) Text("No chats yet.", style = MaterialTheme.typography.bodySmall)
                friends.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { chosen[c.peer] = chosen[c.peer] != true }) {
                        Checkbox(checked = chosen[c.peer] == true, onCheckedChange = { chosen[c.peer] = it })
                        Text(repo.displayName(c.peer))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.values.any { it } && message.isNotBlank(), onClick = {
                val to = chosen.filterValues { it }.keys.toList()
                haptics.success()
                scope.launch { to.forEach { runCatching { repo.sendText(it, message) } } }
                onDismiss()
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ======================================================================= eco map in the Forest tab

@Composable
fun EcoMapCard(onOpen: () -> Unit, onPlant: () -> Unit) {
    val context = LocalContext.current
    val reports by EcoMap.reports.collectAsStateWithLifecycle()
    val here = remember { SosManager.lastKnownLocation(context) }
    LaunchedEffect(here) { here?.let { EcoMap.loadAround(it.lat, it.lon) } }
    val nearby = remember(reports, here) {
        reports.values.filter { here == null || it.distanceKm(here.lat, here.lon) <= 25 }
    }
    val open = nearby.count { it.category.isProblem && !it.cleaned }
    val cleaned = nearby.count { it.cleaned }
    val trees = nearby.count { it.category == EcoCategory.PLANTED }
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🗺️ Community eco map", style = MaterialTheme.typography.titleMedium)
            Text(
                "Report rubbish dumps, polluted water, burning or trees cut down — with a photo and the place. Neighbours check it (✅), and when it's cleaned up (🧹) everyone involved earns points.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MiniStat("⚠️", "$open", "open nearby")
                MiniStat("🧹", "$cleaned", "cleaned")
                MiniStat("🌳", "$trees", "trees planted")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onOpen, shape = RoundedCornerShape(16.dp)) { Text("Open map") }
                OutlinedButton(onClick = onPlant, shape = RoundedCornerShape(16.dp)) { Text("🌱 I planted a tree") }
            }
        }
    }
}

/** Your real trees: each needs two people to check it, then a special tree grows in your forest. */
@Composable
fun RealTreesCard(onPlant: () -> Unit) {
    val reports by EcoMap.reports.collectAsStateWithLifecycle()
    val mine = remember(reports) { EcoMap.myPlanted() }
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🌳 Real trees you planted", style = MaterialTheme.typography.titleMedium)
            Text(
                "Plant a real tree, take a photo where it stands, and ask friends to check it. With ${EcoReports.CONFIRMATIONS} checks it becomes a confirmed tree: +${app.amend.messenger.eco.EcoPoints.PER_TREE} points — a whole new tree in your forest.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (mine.isEmpty()) {
                Button(onClick = onPlant, shape = RoundedCornerShape(16.dp)) { Text("🌱 Add a tree I planted") }
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    mine.forEach { t ->
                        Column(Modifier.width(120.dp)) {
                            Box {
                                RemoteImage(t.imageUrl, Modifier.size(120.dp).clip(RoundedCornerShape(14.dp)))
                                if (t.treeConfirmed) Text("✅", fontSize = 20.sp, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                            }
                            Text(
                                if (t.treeConfirmed) "Confirmed 🌳" else "${t.verifiedBy.size}/${EcoReports.CONFIRMATIONS} checks",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (t.treeConfirmed) Color(0xFF2BB673) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Box(
                        Modifier.size(120.dp).clip(RoundedCornerShape(14.dp)).border(2.dp, Color(0xFF2BB673), RoundedCornerShape(14.dp)).clickable { onPlant() },
                        contentAlignment = Alignment.Center,
                    ) { Text("＋ Add", color = Color(0xFF2BB673), fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ======================================================================= full-screen map

private fun markerIcon(context: Context, category: EcoCategory, done: Boolean): BitmapDrawable {
    val d = context.resources.displayMetrics.density
    val size = (40 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = if (done) 0xFF2BB673.toInt() else category.color.toInt()
    c.drawCircle(size / 2f, size / 2f, size / 2f - 2 * d, paint)
    paint.color = android.graphics.Color.WHITE
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2.5f * d
    c.drawCircle(size / 2f, size / 2f, size / 2f - 2 * d, paint)
    paint.style = Paint.Style.FILL
    paint.textSize = 20 * d
    paint.textAlign = Paint.Align.CENTER
    val emoji = if (done) "🧹" else category.emoji
    c.drawText(emoji, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2, paint)
    return BitmapDrawable(context.resources, bmp)
}

private enum class MapFilter(val label: String) { ALL("All"), OPEN("Open"), CLEANED("Cleaned"), TREES("Trees") }

@Composable
fun EcoMapScreen(repo: AmendRepository, startPlanting: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val reports by EcoMap.reports.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(MapFilter.ALL) }
    var selected by remember { mutableStateOf<String?>(null) }
    var reporting by remember { mutableStateOf(if (startPlanting) EcoCategory.PLANTED else null) }
    val here = remember { SosManager.lastKnownLocation(context) }
    val mapRef = remember { arrayOfNulls<MapView>(1) }
    val markers = remember { mutableMapOf<String, Marker>() }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        if (here == null) locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        else EcoMap.loadAround(here.lat, here.lon)
    }
    BackHandler { if (selected != null) selected = null else onBack() }

    val visible = remember(reports, filter) {
        reports.values.filter {
            when (filter) {
                MapFilter.ALL -> true
                MapFilter.OPEN -> it.category.isProblem && !it.cleaned
                MapFilter.CLEANED -> it.cleaned
                MapFilter.TREES -> it.category == EcoCategory.PLANTED
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    isTilesScaledToDpi = true
                    controller.setZoom(if (here != null) 14.0 else 4.0)
                    controller.setCenter(here?.let { GeoPoint(it.lat, it.lon) } ?: GeoPoint(23.7, 90.4))
                    mapRef[0] = this
                }
            },
            update = { map ->
                val keep = visible.map { it.address }.toSet()
                markers.keys.filter { it !in keep }.forEach { k -> markers.remove(k)?.let { map.overlays.remove(it) } }
                visible.forEach { r ->
                    val m = markers.getOrPut(r.address) {
                        Marker(map).also {
                            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            it.setInfoWindow(null)
                            it.setOnMarkerClickListener { _, _ -> selected = r.address; true }
                            map.overlays.add(it)
                        }
                    }
                    m.position = GeoPoint(r.lat, r.lon)
                    m.icon = markerIcon(context, r.category, r.cleaned)
                }
                map.invalidate()
            },
        )
        DisposableEffect(Unit) { onDispose { mapRef[0]?.onDetach(); mapRef[0] = null } }

        // top bar
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.width(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MapFilter.entries.forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                    }
                }
            }
        }
        Text(
            "© OpenStreetMap contributors · reports are public",
            fontSize = 9.sp, color = Color(0xFF333333),
            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().background(Color(0xCCFFFFFF)).padding(horizontal = 4.dp, vertical = 1.dp),
        )
        // my location + report buttons
        Column(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(
                onClick = { SosManager.lastKnownLocation(context)?.let { mapRef[0]?.controller?.animateTo(GeoPoint(it.lat, it.lon)) } },
                modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
            ) { Icon(Icons.Rounded.MyLocation, contentDescription = "My location") }
            Row(
                Modifier.clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF1C9A62), Color(0xFF2BB673))))
                    .clickable { reporting = EcoCategory.TRASH }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("Report", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        // selected report
        val sel = selected?.let { reports[it] }
        AnimatedVisibility(sel != null, modifier = Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            sel?.let { ReportSheet(repo, it, onClose = { selected = null }) }
        }
    }

    reporting?.let { start ->
        NewReportDialog(
            repo = repo, initial = start,
            center = { mapRef[0]?.mapCenter?.let { it.latitude to it.longitude } ?: here?.let { it.lat to it.lon } },
            onDone = { reporting = null },
        )
    }
}

@Composable
private fun ReportSheet(repo: AmendRepository, r: EcoReport, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var busy by remember { mutableStateOf(false) }
    val mine = r.author == repo.me
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.category.emoji + " " + r.category.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close") }
        }
        if (r.imageUrl != null) RemoteImage(r.imageUrl, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)))
        if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodyMedium)
        Text(
            (if (mine) "You · " else repo.displayName(r.author) + " · ") +
                Instant.ofEpochSecond(r.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) +
                " · ✅ ${r.verifiedBy.size}" + if (r.category.isProblem) " · 🧹 ${r.cleanedBy.size}" else "",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            r.cleaned -> Text("🧹 Cleaned up — thank you, everyone!", color = Color(0xFF2BB673), fontWeight = FontWeight.SemiBold)
            r.treeConfirmed -> Text("🌳 Confirmed real tree", color = Color(0xFF2BB673), fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!mine) {
                val iVerified = repo.me in r.verifiedBy
                Button(enabled = !busy && !iVerified, onClick = {
                    busy = true
                    scope.launch { if (EcoMap.react(r, EcoReports.VERIFY)) haptics.success(); busy = false }
                }, shape = RoundedCornerShape(16.dp)) { Text(if (iVerified) "✅ Checked" else "✅ It's real") }
                if (r.category.isProblem && !r.cleaned) {
                    OutlinedButton(enabled = !busy && repo.me !in r.cleanedBy, onClick = {
                        busy = true
                        scope.launch { if (EcoMap.react(r, EcoReports.CLEANED)) haptics.success(); busy = false }
                    }, shape = RoundedCornerShape(16.dp)) { Text("🧹 Cleaned") }
                }
            } else if (r.category.isProblem && !r.cleaned) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch { if (EcoMap.markCleanedByAuthor(r)) haptics.success(); busy = false }
                }, shape = RoundedCornerShape(16.dp)) { Text("🧹 It's cleaned up") }
            }
            TextButton(onClick = {
                val uri = Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f", r.lat, r.lon, r.lat, r.lon))
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }) { Text("Directions") }
        }
    }
}

/**
 * New report: photo (camera or gallery), what kind, a note, and the place — the
 * map's centre (move the map under the cross-hair to adjust).
 */
@Composable
private fun NewReportDialog(repo: AmendRepository, initial: EcoCategory, center: () -> Pair<Double, Double>?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var category by remember { mutableStateOf(initial) }
    var note by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askFriends by remember { mutableStateOf<EcoReport?>(null) }
    val photoFile = remember { File(context.cacheDir, "eco/photo.jpg").also { it.parentFile?.mkdirs() } }
    val photoUri = remember { FileProvider.getUriForFile(context, context.packageName + ".files", photoFile) }

    fun take(uri: Uri) {
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { compressPhoto(context, uri) }
            photo = bytes
            preview = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) take(photoUri) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { take(it) } }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) camera.launch(photoUri) }

    if (askFriends != null) {
        AskFriendsDialog(repo, askFriends!!, onDone = onDone)
        return
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text(if (category == EcoCategory.PLANTED) "🌱 A tree you planted" else "Report a problem") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(16.dp)).background(Color(0x22000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    val p = preview
                    if (p != null) Image(p, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Text("📷 Add a photo", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) camera.launch(photoUri)
                        else cameraPermission.launch(Manifest.permission.CAMERA)
                    }, shape = RoundedCornerShape(14.dp)) { Text("Camera") }
                    OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, shape = RoundedCornerShape(14.dp)) { Text("Gallery") }
                }
                if (category != EcoCategory.PLANTED) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EcoCategory.problems.forEach { c ->
                            FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.emoji + " " + c.label) })
                        }
                    }
                }
                OutlinedTextField(
                    note, { if (it.length <= 300) note = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text(if (category == EcoCategory.PLANTED) "What tree? (e.g. mango sapling)" else "What did you see?") },
                )
                Text(
                    "📍 The place is the centre of the map behind this window — close it and move the map first if needed. " +
                        "Reports are public: everyone can see the photo and the place.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (photo != null || category != EcoCategory.PLANTED), onClick = {
                val c = center()
                if (c == null) { error = "Turn on location (or move the map) to set the place."; return@TextButton }
                busy = true
                error = null
                scope.launch {
                    val r = runCatching { EcoMap.submit(category, note.trim(), c.first, c.second, photo) }
                    busy = false
                    val rep = r.getOrNull()
                    when {
                        rep == null -> error = r.exceptionOrNull()?.message ?: "No relay accepted the report — try again."
                        rep.category == EcoCategory.PLANTED -> { haptics.success(); askFriends = rep }
                        else -> { haptics.success(); onDone() }
                    }
                }
            }) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Publish") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDone) { Text("Cancel") } },
    )
}

/** After planting: ask friends to check the tree (sends them a chat card). */
@Composable
private fun AskFriendsDialog(repo: AmendRepository, report: EcoReport, onDone: () -> Unit) {
    val conversations by repo.conversations.collectAsStateWithLifecycle()
    val friends = remember(conversations) { conversations.values.filter { repo.isAccepted(it.peer) && it.peer != repo.me } }
    val chosen = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("🌳 Tree added! Ask friends to check it") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Two checks confirm it. Friends nearby can also find it on the map.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                friends.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { chosen[c.peer] = chosen[c.peer] != true }) {
                        Checkbox(checked = chosen[c.peer] == true, onCheckedChange = { chosen[c.peer] = it })
                        Text(repo.displayName(c.peer))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.values.any { it }, onClick = {
                val to = chosen.filterValues { it }.keys.toList()
                scope.launch { to.forEach { runCatching { repo.sendTreeProof(it, report) } } }
                onDone()
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Later") } },
    )
}

// ======================================================================= tree proof in chat

/** A friend's "I planted a tree" card: photo, place and a Verify button. */
@Composable
fun EcoProofContent(repo: AmendRepository, message: ChatMessage, textColor: Color) {
    val info = remember(message.extra) { runCatching { Json.parseObject(message.extra ?: "") }.getOrNull() }
    val address = info?.str("a")
    val image = info?.str("image")
    LaunchedEffect(address) { address?.let { EcoMap.watch(it) } }
    val reports by EcoMap.reports.collectAsStateWithLifecycle()
    val report = address?.let { reports[it] }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.width(250.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RemoteImage(report?.imageUrl ?: image, Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(14.dp)))
        Text(message.text, color = textColor, style = MaterialTheme.typography.bodyMedium)
        val checks = report?.verifiedBy?.size ?: 0
        Text(
            if (report?.treeConfirmed == true) "🌳 Confirmed real tree" else "✅ $checks/${EcoReports.CONFIRMATIONS} checks",
            color = textColor.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium,
        )
        if (!message.fromMe && report != null) {
            val done = repo.me in report.verifiedBy
            Text(
                if (done) "✅ You verified it" else "✅ Verify this tree",
                color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(textColor.copy(alpha = 0.15f))
                    .clickable(enabled = !done && !busy) {
                        busy = true
                        scope.launch { if (EcoMap.react(report, EcoReports.VERIFY)) haptics.success(); busy = false }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Suppress("unused")
private val keepToArgb = Color.Black.toArgb()
