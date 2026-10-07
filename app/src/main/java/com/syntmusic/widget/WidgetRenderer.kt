package com.syntmusic.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.media.ThumbnailUtils
import android.net.Uri
import android.util.LruCache
import android.widget.RemoteViews
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import coil.imageLoader
import coil.request.ImageRequest
import com.syntmusic.MainActivity
import com.syntmusic.MusicApp
import com.syntmusic.R
import com.syntmusic.ui.ArtworkRequest
import com.syntmusic.ui.extractArtworkColor
import com.syntmusic.ui.theme.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

enum class WidgetKind(
    val layout: Int,
    val artworkPx: Int,
    val hasSkipButtons: Boolean,
    /** Play button drawn as a filled circle (inverted colors). */
    val roundPlayButton: Boolean,
) {
    MINI(R.layout.widget_mini, 120, hasSkipButtons = false, roundPlayButton = false),
    SMALL(R.layout.widget_small, 320, hasSkipButtons = false, roundPlayButton = true),
    MEDIUM(R.layout.widget_medium, 160, hasSkipButtons = true, roundPlayButton = false),
    LARGE(R.layout.widget_large, 320, hasSkipButtons = true, roundPlayButton = true);

    val providerClass: Class<out AppWidgetProvider>
        get() = when (this) {
            MINI -> MiniWidgetProvider::class.java
            SMALL -> SmallWidgetProvider::class.java
            MEDIUM -> MediumWidgetProvider::class.java
            LARGE -> LargeWidgetProvider::class.java
        }

    companion object {
        fun of(providerClassName: String?): WidgetKind? = entries.firstOrNull { it.providerClass.name == providerClassName }
    }
}

const val ACTION_PLAY_PAUSE = "com.syntmusic.widget.PLAY_PAUSE"
const val ACTION_NEXT = "com.syntmusic.widget.NEXT"
const val ACTION_PREVIOUS = "com.syntmusic.widget.PREVIOUS"

private const val LIGHT_BACKGROUND = 0xFFF2F2F2.toInt()
private const val DARK_CONTENT = 0xFF111111.toInt()
private const val DARK_CONTENT_SECONDARY = 0xFF5C5C5C.toInt()
private const val LIGHT_CONTENT = 0xFFFFFFFF.toInt()
private const val LIGHT_CONTENT_SECONDARY = 0xB3FFFFFF.toInt()

/** Builds widget RemoteViews from the current player state and pushes them to the launcher. */
object WidgetRenderer {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val artworkCache = LruCache<String, Bitmap>(8)

    suspend fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        for (kind in WidgetKind.entries) {
            for (id in manager.getAppWidgetIds(ComponentName(context, kind.providerClass))) {
                update(context, manager, kind, id)
            }
        }
    }

    suspend fun update(context: Context, manager: AppWidgetManager, kind: WidgetKind, widgetId: Int) {
        manager.updateAppWidget(widgetId, build(context, kind, WidgetSettings.load(context, widgetId)))
    }

    suspend fun build(context: Context, kind: WidgetKind, style: WidgetStyle): RemoteViews {
        val state = (context.applicationContext as MusicApp).container.player.state.value
        val track = state.currentTrack
        val artwork = track?.let { artwork(context, it.artwork, kind.artworkPx) }
        val background = when (style.background) {
            WidgetBackground.ARTWORK -> track?.let { extractArtworkColor(context, it.artwork) }
                ?.let { lerp(Palette.Surface, it, 0.55f).toArgb() }
                ?: Palette.Surface.toArgb()
            WidgetBackground.DARK -> Palette.Surface.toArgb()
            WidgetBackground.LIGHT -> LIGHT_BACKGROUND
            WidgetBackground.CUSTOM -> style.customColor
        }
        // Mostly transparent widgets sit on the wallpaper, where light text is the safer bet.
        val lightBackground = style.opacity >= 40 && ColorUtils.calculateLuminance(background) > 0.5
        val primary = if (lightBackground) DARK_CONTENT else LIGHT_CONTENT
        val secondary = if (lightBackground) DARK_CONTENT_SECONDARY else LIGHT_CONTENT_SECONDARY

        return RemoteViews(context.packageName, kind.layout).apply {
            setInt(R.id.widget_bg, "setColorFilter", background)
            setInt(R.id.widget_bg, "setImageAlpha", style.opacity * 255 / 100)

            setTextViewText(R.id.widget_title, track?.title ?: context.getString(R.string.app_name))
            setTextViewText(R.id.widget_artist, track?.artist ?: context.getString(R.string.widget_idle))
            setTextColor(R.id.widget_title, primary)
            setTextColor(R.id.widget_artist, secondary)

            if (artwork != null) {
                setImageViewBitmap(R.id.widget_art, artwork)
            } else {
                setImageViewResource(R.id.widget_art, R.drawable.widget_art_placeholder)
            }

            setImageViewResource(R.id.widget_play, if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            setContentDescription(
                R.id.widget_play,
                context.getString(if (state.isPlaying) R.string.widget_pause else R.string.widget_play),
            )
            if (kind.roundPlayButton) {
                setInt(R.id.widget_play_bg, "setColorFilter", primary)
                setInt(R.id.widget_play, "setColorFilter", if (lightBackground) LIGHT_CONTENT else DARK_CONTENT)
            } else {
                setInt(R.id.widget_play, "setColorFilter", primary)
            }
            setOnClickPendingIntent(R.id.widget_play, broadcast(context, kind, ACTION_PLAY_PAUSE))

            if (kind.hasSkipButtons) {
                setInt(R.id.widget_prev, "setColorFilter", primary)
                setInt(R.id.widget_next, "setColorFilter", primary)
                setOnClickPendingIntent(R.id.widget_prev, broadcast(context, kind, ACTION_PREVIOUS))
                setOnClickPendingIntent(R.id.widget_next, broadcast(context, kind, ACTION_NEXT))
            }

            setOnClickPendingIntent(R.id.widget_root, openApp(context))
        }
    }

    private fun broadcast(context: Context, kind: WidgetKind, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            action.hashCode() * 31 + kind.ordinal,
            Intent(context, kind.providerClass).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Square, rounded, software bitmap — what RemoteViews can carry. Cached per track and size. */
    private suspend fun artwork(context: Context, uri: Uri, size: Int): Bitmap? {
        val key = "$uri@$size"
        artworkCache.get(key)?.let { return it }
        val request = ImageRequest.Builder(context)
            .data(ArtworkRequest(uri))
            .size(size)
            .allowHardware(false)
            .build()
        val source = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap ?: return null
        return withContext(Dispatchers.Default) {
            val software = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
            ThumbnailUtils.extractThumbnail(software, size, size).rounded(size * 0.12f)
        }.also { artworkCache.put(key, it) }
    }

    private fun Bitmap.rounded(radius: Float): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(this@rounded, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(output).drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, paint)
        return output
    }
}
