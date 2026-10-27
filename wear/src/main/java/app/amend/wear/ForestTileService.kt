package app.amend.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.CircularProgressIndicator
import androidx.wear.protolayout.material.ProgressIndicatorColors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.EdgeContentLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale

/** Tile: today's steps as a ring, with the forest underneath. Tap to open amend. */
class ForestTileService : TileService() {
    private val green = 0xFF2BB673.toInt()
    private val track = 0xFF23402F.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val muted = 0xFFB9C4BD.toInt()

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val s = WatchStore.load(this)
        val layout = layout(s, requestParams.deviceConfiguration)
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RES_VERSION)
            .setFreshnessIntervalMillis(30 * 60 * 1000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build())

    private fun text(t: String, typography: Int, color: Int) =
        Text.Builder(this, t).setTypography(typography).setColor(argb(color)).setMaxLines(2).build()

    private fun layout(s: WatchState?, device: DeviceParameters): LayoutElementBuilders.LayoutElement {
        val body: LayoutElementBuilders.LayoutElement
        val progress: Float
        val footer: String
        if (s == null) {
            body = text("Open amend on your phone", Typography.TYPOGRAPHY_BODY1, white)
            progress = 0f
            footer = "🌱"
        } else if (!s.stepsEnabled) {
            progress = s.growing.toFloat() / s.perTree
            body = LayoutElementBuilders.Column.Builder()
                .addContent(text("${s.growing}/${s.perTree}", Typography.TYPOGRAPHY_DISPLAY2, white))
                .addContent(text("next tree", Typography.TYPOGRAPHY_CAPTION1, muted))
                .build()
            footer = "🌳 ${s.trees} · ${s.points} pts"
        } else {
            progress = s.stepsToday.toFloat() / s.stepsGoal
            body = LayoutElementBuilders.Column.Builder()
                .addContent(text(String.format(Locale.US, "%,d", s.stepsToday), Typography.TYPOGRAPHY_DISPLAY2, white))
                .addContent(text(String.format(Locale.US, "of %,d steps", s.stepsGoal), Typography.TYPOGRAPHY_CAPTION1, muted))
                .build()
            footer = "🌳 ${s.trees} · ${s.points} pts"
        }
        val ring = CircularProgressIndicator.Builder()
            .setProgress(progress.coerceIn(0f, 1f))
            .setCircularProgressIndicatorColors(ProgressIndicatorColors(argb(green), argb(track)))
            .build()
        val content = EdgeContentLayout.Builder(device)
            .setEdgeContent(ring)
            .setPrimaryLabelTextContent(text("amend", Typography.TYPOGRAPHY_CAPTION1, green))
            .setContent(body)
            .setSecondaryLabelTextContent(text(footer, Typography.TYPOGRAPHY_CAPTION2, muted))
            .build()
        val open = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name)
                    .build(),
            ).build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(open).build())
                    .build(),
            )
            .addContent(content)
            .build()
    }

    companion object { private const val RES_VERSION = "1" }
}
