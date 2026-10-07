package com.syntmusic

import android.app.Application
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.syntmusic.data.AppDatabase
import com.syntmusic.data.MusicRepository
import com.syntmusic.lyrics.LyricsRepository
import com.syntmusic.playback.EqualizerController
import com.syntmusic.playback.PlayerController
import com.syntmusic.ui.ArtworkFetcher
import com.syntmusic.ui.ArtworkKeyer
import com.syntmusic.widget.WidgetRenderer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MusicApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        MainScope().launch {
            container.player.state
                .map { it.currentTrack?.id to it.isPlaying }
                .distinctUntilChanged()
                .collect { WidgetRenderer.updateAll(this@MusicApp) }
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(ArtworkFetcher.Factory())
            add(ArtworkKeyer())
        }
        .crossfade(true)
        .build()
}

class AppContainer(app: Application) {
    private val scope = MainScope()
    private val database = Room.databaseBuilder(app, AppDatabase::class.java, "music.db").build()

    val recentDao = database.recentDao()
    val library = MusicRepository(app)
    val lyrics = LyricsRepository(app)
    val player = PlayerController(app, scope, recentDao, library)
    val equalizer = EqualizerController(app)
}
