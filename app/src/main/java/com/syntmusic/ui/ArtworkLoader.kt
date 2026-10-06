package com.syntmusic.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.pxOrElse
import java.io.FileNotFoundException

data class ArtworkRequest(val uri: Uri)

/**
 * Loads embedded artwork downsampled to the requested size, so large covers are never
 * decoded at full resolution. Uses MediaStore thumbnails on Android 10+.
 */
class ArtworkFetcher(
    private val data: ArtworkRequest,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val size = maxOf(
            options.size.width.pxOrElse { DEFAULT_SIZE },
            options.size.height.pxOrElse { DEFAULT_SIZE },
        ).coerceIn(MIN_SIZE, MAX_SIZE)
        val bitmap = thumbnail(size) ?: embedded(size) ?: throw FileNotFoundException("No artwork")
        return DrawableResult(
            drawable = bitmap.toDrawable(options.context.resources),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    private fun thumbnail(size: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            options.context.contentResolver.loadThumbnail(data.uri, Size(size, size), null)
        }.getOrNull()
    }

    private fun embedded(size: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(options.context, data.uri)
            val bytes = retriever.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    class Factory : Fetcher.Factory<ArtworkRequest> {
        override fun create(data: ArtworkRequest, options: Options, imageLoader: ImageLoader): Fetcher =
            ArtworkFetcher(data, options)
    }

    private companion object {
        const val DEFAULT_SIZE = 512
        const val MIN_SIZE = 64
        const val MAX_SIZE = 1024
    }
}

class ArtworkKeyer : Keyer<ArtworkRequest> {
    override fun key(data: ArtworkRequest, options: Options): String = "artwork:${data.uri}"
}
