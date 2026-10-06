package com.syntmusic.data

import android.net.Uri

const val UNKNOWN_ARTIST = "Unknown artist"

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val trackNumber: Int,
    val duration: Long,
    val uri: Uri,
    val path: String?,
) {
    val artwork: Uri get() = uri
}

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val tracks: List<Track>,
) {
    val artwork: Uri get() = tracks.first().artwork
}

data class Artist(
    val name: String,
    val tracks: List<Track>,
)
