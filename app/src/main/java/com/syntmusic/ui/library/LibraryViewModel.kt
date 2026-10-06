package com.syntmusic.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syntmusic.MusicApp
import com.syntmusic.data.Album
import com.syntmusic.data.Artist
import com.syntmusic.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Collator

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as MusicApp).container
    private val collator = Collator.getInstance()

    val tracks: StateFlow<List<Track>> = container.library.tracks

    val albums: StateFlow<List<Album>> = tracks
        .map { list ->
            list.groupBy { it.albumId }.map { (id, albumTracks) ->
                val artists = albumTracks.mapTo(HashSet()) { it.artist }
                Album(
                    id = id,
                    title = albumTracks.first().album,
                    artist = artists.singleOrNull() ?: "Various artists",
                    tracks = albumTracks.sortedBy { it.trackNumber },
                )
            }.sortedWith(compareBy(collator) { it.title })
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists: StateFlow<List<Artist>> = tracks
        .map { list ->
            list.groupBy { it.artist }
                .map { (name, artistTracks) -> Artist(name, artistTracks) }
                .sortedWith(compareBy(collator) { it.name })
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recent: StateFlow<List<Track>> = combine(container.recentDao.observeIds(RECENT_COUNT), tracks) { ids, list ->
        val byId = list.associateBy { it.id }
        ids.mapNotNull(byId::get)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val searchResults: StateFlow<List<Track>> = combine(_query, tracks) { query, list ->
        val q = query.trim()
        if (q.isEmpty()) {
            emptyList()
        } else {
            list.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.artist.contains(q, ignoreCase = true) ||
                    it.album.contains(q, ignoreCase = true)
            }
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun refresh() {
        viewModelScope.launch { container.library.refresh() }
    }

    private companion object {
        const val RECENT_COUNT = 4
    }
}
