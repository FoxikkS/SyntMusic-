package com.syntmusic.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.palette.graphics.Palette as ColorPalette

private val NeutralTint = Color(0xFF262626)

private val colorCache = LruCache<Uri, Color>(256)

/**
 * A dark, muted accent derived from the track's artwork, animated on track change.
 * Lightness is capped so white text stays readable on top of it.
 */
@Composable
fun rememberArtworkColor(uri: Uri?): State<Color> {
    val context = LocalContext.current
    val color = remember { Animatable(uri?.let { colorCache.get(it) } ?: NeutralTint) }
    LaunchedEffect(uri) {
        val target = uri?.let { colorCache.get(it) ?: extractArtworkColor(context, it)?.also { c -> colorCache.put(it, c) } }
            ?: NeutralTint
        if (color.value != target) color.animateTo(target, tween(700))
    }
    return color.asState()
}

suspend fun extractArtworkColor(context: Context, uri: Uri): Color? {
    val request = ImageRequest.Builder(context)
        .data(ArtworkRequest(uri))
        .size(128)
        .allowHardware(false)
        .build()
    val bitmap = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap ?: return null
    return withContext(Dispatchers.Default) {
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val palette = ColorPalette.from(readable).maximumColorCount(16).generate()
        val swatch = palette.vibrantSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch
            ?: return@withContext null
        val hsl = swatch.hsl.copyOf()
        hsl[1] = hsl[1].coerceAtMost(0.55f)
        hsl[2] = hsl[2].coerceIn(0.16f, 0.30f)
        Color(ColorUtils.HSLToColor(hsl))
    }
}
