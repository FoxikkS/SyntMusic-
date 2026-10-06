package com.syntmusic.lyrics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.syntmusic.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Finds lyrics for a track, in order:
 * 1. a sidecar `.lrc` / `.txt` next to the audio file (when the OS allows direct file access);
 * 2. a matching file in a user-chosen lyrics folder (Storage Access Framework);
 * 3. lyrics embedded in the audio tags (ID3 USLT, MP4 ©lyr, Vorbis LYRICS);
 * 4. LRCLIB online, when nothing synced was found locally (can be turned off).
 */
class LyricsRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("lyrics", Context.MODE_PRIVATE)
    private val _folder = MutableStateFlow(prefs.getString(KEY_FOLDER, null)?.let(Uri::parse))
    val folder: StateFlow<Uri?> = _folder.asStateFlow()

    private val online = OnlineLyrics(context)
    private val _onlineEnabled = MutableStateFlow(prefs.getBoolean(KEY_ONLINE, true))
    val onlineEnabled: StateFlow<Boolean> = _onlineEnabled.asStateFlow()

    private val indexLock = Mutex()
    @Volatile private var index: Map<String, Uri>? = null

    fun setFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
        index = null
        _folder.value = uri
    }

    fun setOnlineEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ONLINE, enabled).apply()
        _onlineEnabled.value = enabled
    }

    suspend fun load(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        val local = (fromSidecar(track) ?: fromFolder(track) ?: fromEmbedded(track))?.let(LrcParser::parse)
        if (local?.type == LyricsType.SYNCED || !_onlineEnabled.value) return@withContext local
        val remote = online.find(track)
        // Prefer synced from anywhere; otherwise keep the user's own plain text.
        remote?.takeIf { it.type == LyricsType.SYNCED } ?: local ?: remote
    }

    private fun fromSidecar(track: Track): String? {
        val base = track.path?.substringBeforeLast('.') ?: return null
        return listOf("$base.lrc", "$base.txt").firstNotNullOfOrNull { path ->
            runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()
        }
    }

    private suspend fun fromFolder(track: Track): String? {
        val tree = _folder.value ?: return null
        val files = indexLock.withLock { index ?: buildIndex(tree).also { index = it } }
        val candidates = buildList {
            track.path?.let { add(it.substringAfterLast('/').substringBeforeLast('.')) }
            add("${track.artist} - ${track.title}")
            add(track.title)
        }
        val uri = candidates.firstNotNullOfOrNull { files[it.lowercase()] } ?: return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }

    /** Maps lowercase file base names to document URIs; `.lrc` wins over `.txt`. */
    private fun buildIndex(tree: Uri): Map<String, Uri> {
        val found = ArrayList<Pair<String, Uri>>()
        fun walk(documentId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < MAX_DEPTH) walk(id, depth + 1)
                    } else if (name.endsWith(".lrc", true) || name.endsWith(".txt", true)) {
                        found += name to DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    }
                }
            }
        }
        runCatching { walk(DocumentsContract.getTreeDocumentId(tree), 0) }
        return found
            .sortedBy { it.first.endsWith(".lrc", true) }
            .associate { it.first.substringBeforeLast('.').lowercase() to it.second }
    }

    @OptIn(UnstableApi::class)
    private suspend fun fromEmbedded(track: Track): String? = withTimeoutOrNull(EMBEDDED_TIMEOUT_MS) {
        val groups = runCatching {
            MetadataRetriever.retrieveMetadata(context, MediaItem.fromUri(track.uri)).await()
        }.getOrNull() ?: return@withTimeoutOrNull null
        for (g in 0 until groups.length) {
            val group = groups[g]
            for (f in 0 until group.length) {
                val metadata = group.getFormat(f).metadata ?: continue
                metadata.lyrics()?.let { return@withTimeoutOrNull it }
            }
        }
        null
    }

    @OptIn(UnstableApi::class)
    private fun Metadata.lyrics(): String? {
        for (i in 0 until length()) {
            val text = when (val entry = get(i)) {
                is BinaryFrame -> if (entry.id == "USLT") parseUslt(entry.data) else null
                is TextInformationFrame -> when {
                    entry.id == "USLT" -> entry.values.joinToString("\n")
                    entry.id == "TXXX" && entry.description.isLyricsKey() -> entry.values.joinToString("\n")
                    else -> null
                }
                is VorbisComment -> if (entry.key.isLyricsKey()) entry.value else null
                else -> null
            }
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun String?.isLyricsKey() =
        equals("LYRICS", ignoreCase = true) || equals("UNSYNCEDLYRICS", ignoreCase = true)

    /** ID3 USLT: encoding(1) language(3) descriptor(null-terminated) text. */
    private fun parseUslt(data: ByteArray): String? {
        if (data.size < 5) return null
        val encoding = data[0].toInt()
        val charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        var i = 4
        if (encoding == 1 || encoding == 2) {
            while (i + 1 < data.size && (data[i].toInt() != 0 || data[i + 1].toInt() != 0)) i += 2
            i += 2
        } else {
            while (i < data.size && data[i].toInt() != 0) i++
            i += 1
        }
        if (i >= data.size) return null
        return String(data, i, data.size - i, charset).trimEnd('\u0000')
    }

    private companion object {
        const val KEY_FOLDER = "folder"
        const val KEY_ONLINE = "online"
        const val MAX_DEPTH = 4
        const val EMBEDDED_TIMEOUT_MS = 3_000L
    }
}
