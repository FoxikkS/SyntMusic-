package com.syntmusic.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.syntmusic.data.MusicRepository
import com.syntmusic.data.RecentDao
import com.syntmusic.data.RecentEntry
import com.syntmusic.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** [key] is unique per queue entry (the same song can be queued twice). */
data class QueueItem(val index: Int, val key: String, val track: Track)

sealed interface SleepTimer {
    /** Fires at [endsAt] (SystemClock.elapsedRealtime), fading out over the last seconds. */
    data class At(val endsAt: Long) : SleepTimer
    data object EndOfTrack : SleepTimer
}

/**
 * Player state without the position; [PlayerController.position] is published separately
 * so that only position-dependent UI (progress bar, lyrics) updates while playing.
 */
data class PlayerState(
    val currentTrack: Track? = null,
    val currentIndex: Int = C.INDEX_UNSET,
    val isPlaying: Boolean = false,
    val duration: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueItem> = emptyList(),
)

/**
 * The single entry point for playback commands and state. Talks to [PlaybackService] through a
 * MediaController, so in-app controls, the notification, the lock screen and headset buttons
 * all act on the same player and are reflected here.
 */
class PlayerController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val recentDao: RecentDao,
    private val library: MusicRepository,
) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    private var restoreAttempted = false
    private var saveQueueJob: Job? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _sleepTimer = MutableStateFlow<SleepTimer?>(null)
    val sleepTimer: StateFlow<SleepTimer?> = _sleepTimer.asStateFlow()
    private var sleepJob: Job? = null

    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (_sleepTimer.value == SleepTimer.EndOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller?.pause()
                _sleepTimer.value = null
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                publishQueue(player)
            }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                player.currentMediaItem?.trackId()?.let(::markPlayed)
            }
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) scheduleQueueSave(player)
            savePlaybackPointer(player)
            publishState(player)
            publishPosition()
        }
    }

    init {
        connect()
        // Poll the position only while something is playing and someone is looking at it.
        scope.launch {
            combine(
                _position.subscriptionCount.map { it > 0 }.distinctUntilChanged(),
                _state.map { it.isPlaying }.distinctUntilChanged(),
            ) { observed, playing -> observed && playing }
                .distinctUntilChanged()
                .collectLatest { tick ->
                    publishPosition()
                    while (tick) {
                        delay(POSITION_INTERVAL_MS)
                        publishPosition()
                    }
                }
        }
        // Keep the saved position fresh in case the process is killed mid-song.
        scope.launch {
            _state.map { it.isPlaying }.distinctUntilChanged().collectLatest { playing ->
                while (playing) {
                    delay(SAVE_POSITION_INTERVAL_MS)
                    controller?.let(::savePlaybackPointer)
                }
            }
        }
    }

    private fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    this@PlayerController.controller = null
                    connect()
                }
            })
            .buildAsync()
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = connected
            connected.addListener(listener)
            publishQueue(connected)
            publishState(connected)
            publishPosition()
            while (pending.isNotEmpty()) pending.removeFirst()(connected)
            if (!restoreAttempted) {
                restoreAttempted = true
                if (connected.mediaItemCount == 0) restoreQueue()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: pending.addLast(block)
    }

    fun play(tracks: List<Track>, startIndex: Int) = withController {
        it.setMediaItems(tracks.map(Track::toMediaItem), startIndex, 0L)
        it.prepare()
        it.play()
    }

    fun playShuffled(tracks: List<Track>) = withController {
        if (tracks.isEmpty()) return@withController
        it.shuffleModeEnabled = true
        it.setMediaItems(tracks.map(Track::toMediaItem), Random.nextInt(tracks.size), 0L)
        it.prepare()
        it.play()
    }

    fun playNext(track: Track) = withController {
        if (it.mediaItemCount == 0) play(listOf(track), 0) else it.addMediaItem(it.currentMediaItemIndex + 1, track.toMediaItem())
    }

    fun addToQueue(track: Track) = withController {
        if (it.mediaItemCount == 0) play(listOf(track), 0) else it.addMediaItem(track.toMediaItem())
    }

    fun removeFromQueue(index: Int) = withController { it.removeMediaItem(index) }

    fun moveInQueue(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }

    fun togglePlayPause() = withController {
        if (it.playWhenReady && it.playbackState != Player.STATE_ENDED) {
            it.pause()
        } else {
            if (it.playbackState == Player.STATE_IDLE) it.prepare()
            if (it.playbackState == Player.STATE_ENDED) it.seekToDefaultPosition()
            it.play()
        }
    }

    fun next() = withController { it.seekToNext() }

    fun previous() = withController { it.seekToPrevious() }

    fun seekTo(positionMs: Long) = withController {
        it.seekTo(positionMs)
        _position.value = positionMs
    }

    fun playQueueItem(index: Int) = withController {
        it.seekToDefaultPosition(index)
        it.play()
    }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeatMode() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** [minutes]: null turns the timer off, 0 means "at the end of this track". */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        controller?.volume = 1f
        when {
            minutes == null -> _sleepTimer.value = null
            minutes == 0 -> _sleepTimer.value = SleepTimer.EndOfTrack
            else -> {
                val endsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
                _sleepTimer.value = SleepTimer.At(endsAt)
                sleepJob = scope.launch {
                    delay((endsAt - SLEEP_FADE_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                    while (true) {
                        val left = endsAt - SystemClock.elapsedRealtime()
                        if (left <= 0) break
                        controller?.volume = (left.toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f)
                        delay(100)
                    }
                    controller?.pause()
                    controller?.volume = 1f
                    _sleepTimer.value = null
                }
            }
        }
    }

    private fun publishPosition() {
        controller?.let { _position.value = it.currentPosition }
    }

    private fun publishState(player: Player) {
        val track = player.currentMediaItem?.toTrack()
        val playing = player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        _state.update {
            it.copy(
                currentTrack = track,
                currentIndex = player.currentMediaItemIndex,
                isPlaying = playing,
                duration = player.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 } ?: track?.duration ?: 0L,
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
            )
        }
    }

    private fun publishQueue(player: Player) {
        val timeline = player.currentTimeline
        val queue = ArrayList<QueueItem>(timeline.windowCount)
        val window = Timeline.Window()
        val shuffle = player.shuffleModeEnabled
        var index = timeline.getFirstWindowIndex(shuffle)
        while (index != C.INDEX_UNSET && queue.size < timeline.windowCount) {
            val item = timeline.getWindow(index, window).mediaItem
            queue += QueueItem(index, item.mediaId, item.toTrack())
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
        }
        _state.update { it.copy(queue = queue) }
    }

    private fun scheduleQueueSave(player: Player) {
        saveQueueJob?.cancel()
        saveQueueJob = scope.launch {
            delay(QUEUE_SAVE_DEBOUNCE_MS)
            val ids = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).trackId() }
            prefs.edit().putString(KEY_QUEUE, ids.joinToString(",")).apply()
        }
    }

    private fun savePlaybackPointer(player: Player) {
        if (player.mediaItemCount == 0) return
        prefs.edit()
            .putInt(KEY_INDEX, player.currentMediaItemIndex)
            .putLong(KEY_POSITION, player.currentPosition)
            .putBoolean(KEY_SHUFFLE, player.shuffleModeEnabled)
            .putInt(KEY_REPEAT, player.repeatMode)
            .apply()
    }

    /** Brings back the last queue (paused, at the saved position) after the process was killed. */
    private fun restoreQueue() {
        val ids = prefs.getString(KEY_QUEUE, null)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
        if (ids.isEmpty()) return
        scope.launch {
            if (library.tracks.value.isEmpty()) runCatching { library.refresh() }
            val byId = library.tracks.first { it.isNotEmpty() }.associateBy { it.id }
            val c = controller ?: return@launch
            if (c.mediaItemCount > 0) return@launch
            val tracks = ids.mapNotNull(byId::get)
            if (tracks.isEmpty()) return@launch
            val savedId = ids.getOrNull(prefs.getInt(KEY_INDEX, 0))
            val index = tracks.indexOfFirst { it.id == savedId }
            c.shuffleModeEnabled = prefs.getBoolean(KEY_SHUFFLE, false)
            c.repeatMode = prefs.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF)
            c.setMediaItems(
                tracks.map(Track::toMediaItem),
                index.coerceAtLeast(0),
                if (index >= 0) prefs.getLong(KEY_POSITION, 0L) else 0L,
            )
            c.prepare()
        }
    }

    private fun markPlayed(trackId: Long) {
        scope.launch(Dispatchers.IO) {
            recentDao.upsert(RecentEntry(trackId, System.currentTimeMillis()))
            recentDao.trim(RECENT_LIMIT)
        }
    }

    private companion object {
        const val POSITION_INTERVAL_MS = 200L
        const val RECENT_LIMIT = 50
        const val SLEEP_FADE_MS = 15_000L
        const val SAVE_POSITION_INTERVAL_MS = 5_000L
        const val QUEUE_SAVE_DEBOUNCE_MS = 500L
        const val KEY_QUEUE = "queue"
        const val KEY_INDEX = "index"
        const val KEY_POSITION = "position"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat"
    }
}

private const val EXTRA_ALBUM_ID = "album_id"
private const val EXTRA_TRACK_NUMBER = "track_number"
private const val EXTRA_DURATION = "duration"
private const val EXTRA_PATH = "path"

private val queueEntryCounter = AtomicLong()

/** Media IDs are "trackId:entry" so every queue entry has its own stable identity. */
fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId("$id:${queueEntryCounter.incrementAndGet()}")
    .setUri(uri)
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(
                bundleOf(
                    EXTRA_ALBUM_ID to albumId,
                    EXTRA_TRACK_NUMBER to trackNumber,
                    EXTRA_DURATION to duration,
                    EXTRA_PATH to path,
                ),
            )
            .build(),
    )
    .build()

fun MediaItem.trackId(): Long? = mediaId.substringBefore(':').toLongOrNull()

fun MediaItem.toTrack(): Track {
    val metadata = mediaMetadata
    val extras = metadata.extras
    return Track(
        id = trackId() ?: -1L,
        title = metadata.title?.toString().orEmpty(),
        artist = metadata.artist?.toString().orEmpty(),
        album = metadata.albumTitle?.toString().orEmpty(),
        albumId = extras?.getLong(EXTRA_ALBUM_ID) ?: -1L,
        trackNumber = extras?.getInt(EXTRA_TRACK_NUMBER) ?: 0,
        duration = extras?.getLong(EXTRA_DURATION) ?: 0L,
        uri = requestMetadata.mediaUri ?: localConfiguration?.uri ?: Uri.EMPTY,
        path = extras?.getString(EXTRA_PATH),
    )
}
