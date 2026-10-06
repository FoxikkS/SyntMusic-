package com.syntmusic.ui.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syntmusic.MusicApp
import com.syntmusic.data.Track
import com.syntmusic.lyrics.Lyrics
import com.syntmusic.playback.EqualizerState
import com.syntmusic.playback.PlayerState
import com.syntmusic.playback.SleepTimer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

sealed interface LyricsUiState {
    data object Loading : LyricsUiState
    data object NotFound : LyricsUiState
    data class Loaded(val trackId: Long, val lyrics: Lyrics) : LyricsUiState
}

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as MusicApp).container
    private val player = container.player
    private val lyricsRepository = container.lyrics
    private val equalizerController = container.equalizer

    val equalizer: StateFlow<EqualizerState> = equalizerController.state
    val onlineLyrics: StateFlow<Boolean> = lyricsRepository.onlineEnabled

    val state: StateFlow<PlayerState> = player.state
    val position: StateFlow<Long> = player.position
    val sleepTimer: StateFlow<SleepTimer?> = player.sleepTimer

    private val _lyricsVisible = MutableStateFlow(false)
    val lyricsVisible: StateFlow<Boolean> = _lyricsVisible.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val lyrics: StateFlow<LyricsUiState> = combine(
        state.map { it.currentTrack }.distinctUntilChangedBy { it?.id },
        lyricsRepository.folder,
        lyricsRepository.onlineEnabled,
    ) { track, _, _ -> track }
        .transformLatest { track ->
            if (track == null) {
                emit(LyricsUiState.NotFound)
            } else {
                emit(LyricsUiState.Loading)
                val lyrics = lyricsRepository.load(track)
                emit(if (lyrics == null) LyricsUiState.NotFound else LyricsUiState.Loaded(track.id, lyrics))
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LyricsUiState.Loading)

    fun play(tracks: List<Track>, startIndex: Int) = player.play(tracks, startIndex)
    fun playShuffled(tracks: List<Track>) = player.playShuffled(tracks)
    fun playNext(track: Track) = player.playNext(track)
    fun addToQueue(track: Track) = player.addToQueue(track)
    fun removeFromQueue(index: Int) = player.removeFromQueue(index)
    fun moveInQueue(from: Int, to: Int) = player.moveInQueue(from, to)
    fun togglePlayPause() = player.togglePlayPause()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun playQueueItem(index: Int) = player.playQueueItem(index)
    fun setSleepTimer(minutes: Int?) = player.setSleepTimer(minutes)
    fun toggleShuffle() = player.toggleShuffle()
    fun cycleRepeatMode() = player.cycleRepeatMode()

    fun toggleLyrics() {
        _lyricsVisible.value = !_lyricsVisible.value
    }

    fun setLyricsFolder(uri: Uri) = lyricsRepository.setFolder(uri)
    fun toggleOnlineLyrics() = lyricsRepository.setOnlineEnabled(!onlineLyrics.value)

    fun setEqualizerEnabled(enabled: Boolean) = equalizerController.setEnabled(enabled)
    fun useEqualizerPreset(preset: Int) = equalizerController.usePreset(preset)
    fun setBassStrength(strength: Int) = equalizerController.setBassStrength(strength)

    fun setBandLevel(band: Int, level: Int) {
        if (!equalizer.value.enabled) equalizerController.setEnabled(true)
        equalizerController.setBandLevel(band, level)
    }
}
