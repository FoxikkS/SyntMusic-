package com.syntmusic.ui.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ripple
import com.syntmusic.ui.Pill
import com.syntmusic.ui.pressScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syntmusic.data.Album
import com.syntmusic.data.Artist
import com.syntmusic.data.Track
import com.syntmusic.ui.Artwork
import com.syntmusic.ui.BrandTitle
import com.syntmusic.ui.CenteredMessage
import com.syntmusic.ui.SectionTitle
import com.syntmusic.ui.TrackRow
import com.syntmusic.ui.theme.Palette

private val tabs = listOf("Songs", "Albums", "Artists")

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    currentTrackId: Long?,
    bottomPadding: Dp,
    onPlay: (List<Track>, Int) -> Unit,
    onOpenEqualizer: () -> Unit,
    onShuffleAll: (List<Track>) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var openAlbumId by rememberSaveable { mutableStateOf<Long?>(null) }
    var openArtist by rememberSaveable { mutableStateOf<String?>(null) }

    val album = openAlbumId?.let { id -> albums.firstOrNull { it.id == id } }
    val artist = openArtist?.let { name -> artists.firstOrNull { it.name == name } }

    BackHandler(enabled = searching || openAlbumId != null || openArtist != null) {
        when {
            searching -> {
                searching = false
                viewModel.setQuery("")
            }
            openAlbumId != null -> openAlbumId = null
            else -> openArtist = null
        }
    }

    val trackMenu: @Composable ColumnScope.(Track, () -> Unit) -> Unit = { track, dismiss ->
        TrackMenuItems(
            track = track,
            dismiss = dismiss,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onGoToAlbum = {
                searching = false
                viewModel.setQuery("")
                openArtist = null
                openAlbumId = it.albumId
            },
            onGoToArtist = {
                searching = false
                viewModel.setQuery("")
                openAlbumId = null
                openArtist = it.artist
            },
        )
    }

    val listPadding = PaddingValues(
        bottom = bottomPadding + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp,
    )

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        when {
            searching -> {
                SearchBar(
                    query = query,
                    onQueryChange = viewModel::setQuery,
                    onClose = {
                        searching = false
                        viewModel.setQuery("")
                    },
                )
                if (query.isNotBlank() && results.isEmpty()) {
                    CenteredMessage("Nothing found")
                } else {
                    TrackList(results, currentTrackId, listPadding, onPlay, trackMenu)
                }
            }
            album != null -> {
                DetailHeader(album.title, "${album.artist} · ${album.tracks.size} songs") { openAlbumId = null }
                DetailActions(album.tracks, onPlay, onShuffleAll)
                TrackList(album.tracks, currentTrackId, listPadding, onPlay, trackMenu)
            }
            artist != null -> {
                DetailHeader(artist.name, "${artist.tracks.size} songs") { openArtist = null }
                DetailActions(artist.tracks, onPlay, onShuffleAll)
                TrackList(artist.tracks, currentTrackId, listPadding, onPlay, trackMenu)
            }
            else -> {
                LibraryHeader(onEqualizer = onOpenEqualizer, onSearch = { searching = true })
                Tabs(selected = tab, onSelect = { tab = it })
                Crossfade(tab, animationSpec = tween(220), label = "tab") { page ->
                    when (page) {
                        0 -> SongsTab(tracks, recent, currentTrackId, listPadding, onPlay, onShuffleAll, trackMenu)
                        1 -> AlbumsTab(albums, listPadding) { openAlbumId = it.id }
                        else -> ArtistsTab(artists, listPadding) { openArtist = it.name }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(onEqualizer: () -> Unit, onSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandTitle(Modifier.weight(1f))
        IconButton(
            onClick = onEqualizer,
            modifier = Modifier.padding(end = 8.dp).clip(CircleShape).background(Palette.Surface),
        ) {
            Icon(Icons.Rounded.Equalizer, contentDescription = "Equalizer")
        }
        IconButton(
            onClick = onSearch,
            modifier = Modifier.padding(end = 8.dp).clip(CircleShape).background(Palette.Surface),
        ) {
            Icon(Icons.Rounded.Search, contentDescription = "Search")
        }
    }
}

@Composable
private fun Tabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { index, title ->
            Pill(title, selected = index == selected, onClick = { onSelect(index) })
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
        }
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Songs, artists, albums") },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Palette.Primary,
                focusedPlaceholderColor = Palette.Inactive,
                unfocusedPlaceholderColor = Palette.Inactive,
            ),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Rounded.Close, contentDescription = "Clear")
            }
        }
    }
}

@Composable
private fun DetailHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
        }
        Column(Modifier.padding(start = 4.dp, end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.Secondary, maxLines = 1)
        }
    }
}

@Composable
private fun DetailActions(tracks: List<Track>, onPlay: (List<Track>, Int) -> Unit, onShuffle: (List<Track>) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Pill("Play", selected = true, onClick = { onPlay(tracks, 0) }, icon = Icons.Rounded.PlayArrow)
        Pill("Shuffle", selected = false, onClick = { onShuffle(tracks) }, icon = Icons.Rounded.Shuffle)
    }
}

@Composable
private fun ColumnScope.TrackMenuItems(
    track: Track,
    dismiss: () -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onGoToAlbum: (Track) -> Unit,
    onGoToArtist: (Track) -> Unit,
) {
    DropdownMenuItem(
        text = { Text("Play next") },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null) },
        onClick = { onPlayNext(track); dismiss() },
    )
    DropdownMenuItem(
        text = { Text("Add to queue") },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
        onClick = { onAddToQueue(track); dismiss() },
    )
    DropdownMenuItem(
        text = { Text("Go to album") },
        leadingIcon = { Icon(Icons.Rounded.Album, null) },
        onClick = { onGoToAlbum(track); dismiss() },
    )
    DropdownMenuItem(
        text = { Text("Go to artist") },
        leadingIcon = { Icon(Icons.Rounded.Person, null) },
        onClick = { onGoToArtist(track); dismiss() },
    )
}

@Composable
private fun SongsTab(
    tracks: List<Track>,
    recent: List<Track>,
    currentTrackId: Long?,
    padding: PaddingValues,
    onPlay: (List<Track>, Int) -> Unit,
    onShuffleAll: (List<Track>) -> Unit,
    trackMenu: @Composable ColumnScope.(Track, () -> Unit) -> Unit,
) {
    if (tracks.isEmpty()) {
        CenteredMessage("No music found on this device")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        if (recent.isNotEmpty()) {
            item(key = "recent") {
                SectionTitle("Recently played")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(recent, key = { _, t -> t.id }) { index, track ->
                        CoverCard(track.artwork, track.title, track.artist) { onPlay(recent, index) }
                    }
                }
            }
        }
        item(key = "all-title") {
            Row(
                Modifier.fillMaxWidth().padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("All songs", Modifier.weight(1f))
                Pill("Shuffle", selected = false, onClick = { onShuffleAll(tracks) }, icon = Icons.Rounded.Shuffle)
            }
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
            TrackRow(
                track = track,
                isCurrent = track.id == currentTrackId,
                onClick = { onPlay(tracks, index) },
                menu = { dismiss -> this.trackMenu(track, dismiss) },
            )
        }
    }
}

@Composable
private fun CoverCard(artwork: Uri, title: String, subtitle: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(140.dp)
            .pressScale(interaction, 0.95f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(interaction, ripple(), onClick = onClick),
    ) {
        Artwork(artwork, Modifier.size(140.dp), cornerRadius = 12.dp)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TrackList(
    tracks: List<Track>,
    currentTrackId: Long?,
    padding: PaddingValues,
    onPlay: (List<Track>, Int) -> Unit,
    trackMenu: @Composable ColumnScope.(Track, () -> Unit) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
            TrackRow(
                track = track,
                isCurrent = track.id == currentTrackId,
                onClick = { onPlay(tracks, index) },
                menu = { dismiss -> this.trackMenu(track, dismiss) },
            )
        }
    }
}

@Composable
private fun AlbumsTab(albums: List<Album>, padding: PaddingValues, onOpen: (Album) -> Unit) {
    if (albums.isEmpty()) {
        CenteredMessage("No albums")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp,
            bottom = padding.calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.id }) { album ->
            Column(Modifier.clickable { onOpen(album) }) {
                Artwork(album.artwork, Modifier.fillMaxWidth().aspectRatio(1f), cornerRadius = 10.dp)
                Spacer(Modifier.height(8.dp))
                Text(album.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    album.artist, style = MaterialTheme.typography.bodyMedium, color = Palette.Secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ArtistsTab(artists: List<Artist>, padding: PaddingValues, onOpen: (Artist) -> Unit) {
    if (artists.isEmpty()) {
        CenteredMessage("No artists")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        items(artists, key = { it.name }) { artist ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(artist) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(artist.tracks.first().artwork, Modifier.size(48.dp), cornerRadius = 24.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${artist.tracks.size} songs", style = MaterialTheme.typography.bodyMedium, color = Palette.Secondary)
                }
            }
        }
    }
}
