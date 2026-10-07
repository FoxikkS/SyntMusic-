package com.syntmusic.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import coil.imageLoader
import coil.request.ImageRequest
import com.syntmusic.MainActivity
import com.syntmusic.MusicApp
import com.syntmusic.R
import com.syntmusic.playback.PlayerController
import com.syntmusic.playback.PlayerState
import com.syntmusic.ui.ArtworkRequest
import com.syntmusic.ui.extractArtworkColor
import com.syntmusic.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Home screen player. Redrawn by MusicApp whenever the track or play state changes. */
class SyntWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = player(context).state.value
        val artworkUri = state.currentTrack?.artwork
        val artwork = artworkUri?.let { loadArtwork(context, it) }
        val tint = artworkUri?.let { extractArtworkColor(context, it) }
        val background = tint?.let { lerp(Palette.Surface, it, 0.55f) } ?: Palette.Surface
        provideContent { WidgetContent(state, artwork, background) }
    }
}

class SyntWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SyntWidget()
}

@Composable
private fun WidgetContent(state: PlayerState, artwork: Bitmap?, background: Color) {
    val track = state.currentTrack
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(20.dp)
            .background(background)
            .padding(10.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = if (artwork != null) ImageProvider(artwork) else ImageProvider(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = GlanceModifier.size(52.dp).cornerRadius(10.dp).background(Palette.SurfaceHigh),
        )
        Spacer(GlanceModifier.width(12.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(
                text = track?.title ?: "SyntMusic",
                style = TextStyle(color = ColorProvider(Palette.Primary), fontSize = 15.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            Text(
                text = track?.artist ?: "Nothing playing",
                style = TextStyle(color = ColorProvider(Palette.Primary.copy(alpha = 0.65f)), fontSize = 13.sp),
                maxLines = 1,
            )
        }
        WidgetButton(R.drawable.ic_skip_previous, "Previous", actionRunCallback<PreviousAction>())
        WidgetButton(
            icon = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            description = if (state.isPlaying) "Pause" else "Play",
            action = actionRunCallback<PlayPauseAction>(),
        )
        WidgetButton(R.drawable.ic_skip_next, "Next", actionRunCallback<NextAction>())
    }
}

@Composable
private fun WidgetButton(icon: Int, description: String, action: Action) {
    Image(
        provider = ImageProvider(icon),
        contentDescription = description,
        colorFilter = ColorFilter.tint(ColorProvider(Palette.Primary)),
        modifier = GlanceModifier.size(42.dp).cornerRadius(21.dp).padding(9.dp).clickable(action),
    )
}

// Widget actions may run off the main thread; the MediaController must be used on it.
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        withContext(Dispatchers.Main) { player(context).togglePlayPause() }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        withContext(Dispatchers.Main) { player(context).next() }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        withContext(Dispatchers.Main) { player(context).previous() }
}

private fun player(context: Context): PlayerController =
    (context.applicationContext as MusicApp).container.player

/** Widgets can't hold hardware bitmaps, so a software copy is made when needed. */
private suspend fun loadArtwork(context: Context, uri: Uri): Bitmap? {
    val request = ImageRequest.Builder(context)
        .data(ArtworkRequest(uri))
        .size(256)
        .allowHardware(false)
        .build()
    val bitmap = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap ?: return null
    return if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
}
