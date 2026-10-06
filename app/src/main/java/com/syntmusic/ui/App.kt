package com.syntmusic.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.syntmusic.ui.library.LibraryScreen
import com.syntmusic.ui.library.LibraryViewModel
import com.syntmusic.ui.player.EqualizerSheet
import com.syntmusic.ui.player.MiniPlayer
import com.syntmusic.ui.player.PlayerScreen
import com.syntmusic.ui.player.PlayerViewModel
import com.syntmusic.ui.player.rememberSmoothPosition
import com.syntmusic.ui.theme.Palette

private val audioPermission =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

private val requestedPermissions =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(audioPermission, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(audioPermission)

private fun Context.hasAudioPermission() =
    ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED

@Composable
fun MusicAppRoot(
    libraryViewModel: LibraryViewModel = viewModel(),
    playerViewModel: PlayerViewModel = viewModel(),
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(context.hasAudioPermission()) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = context.hasAudioPermission()
        askedOnce = true
        if (hasPermission) libraryViewModel.refresh()
    }

    // Re-check on return (permission may be granted in Settings) and pick up new files.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        hasPermission = context.hasAudioPermission()
        if (hasPermission) libraryViewModel.refresh()
    }

    if (!hasPermission) {
        PermissionScreen(
            showSettings = askedOnce,
            onRequest = { launcher.launch(requestedPermissions) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
        )
        return
    }

    val playerState by playerViewModel.state.collectAsStateWithLifecycle()
    val reportedPosition = playerViewModel.position.collectAsStateWithLifecycle()
    val position = rememberSmoothPosition(reportedPosition, playerState.isPlaying)
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var equalizerOpen by rememberSaveable { mutableStateOf(false) }
    val hasTrack = playerState.currentTrack != null

    CompositionLocalProvider(LocalPlaybackActive provides playerState.isPlaying) {
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        LibraryScreen(
            viewModel = libraryViewModel,
            currentTrackId = playerState.currentTrack?.id,
            bottomPadding = if (hasTrack) 80.dp else 0.dp,
            onPlay = { tracks, index ->
                playerViewModel.play(tracks, index)
                playerOpen = true
            },
            onOpenEqualizer = { equalizerOpen = true },
            onPlayNext = { track ->
                playerViewModel.playNext(track)
                Toast.makeText(context, "Plays next", Toast.LENGTH_SHORT).show()
            },
            onAddToQueue = { track ->
                playerViewModel.addToQueue(track)
                Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
            },
            onShuffleAll = { tracks ->
                playerViewModel.playShuffled(tracks)
                playerOpen = true
            },
        )

        AnimatedVisibility(
            visible = hasTrack && !playerOpen,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(sheetSpring) { it } + fadeIn(),
            exit = slideOutVertically(sheetSpring) { it } + fadeOut(),
        ) {
            MiniPlayer(
                state = playerState,
                position = position,
                onPlayPause = playerViewModel::togglePlayPause,
                onOpen = { playerOpen = true },
                onNext = playerViewModel::next,
                onPrevious = playerViewModel::previous,
            )
        }

        AnimatedVisibility(
            visible = hasTrack && playerOpen,
            enter = slideInVertically(sheetSpring) { it },
            exit = slideOutVertically(sheetSpring) { it },
        ) {
            PlayerScreen(
                viewModel = playerViewModel,
                onClose = { playerOpen = false },
                onOpenEqualizer = { equalizerOpen = true },
            )
        }
    }
    }

    if (equalizerOpen) {
        val equalizer by playerViewModel.equalizer.collectAsStateWithLifecycle()
        EqualizerSheet(
            state = equalizer,
            onEnabledChange = playerViewModel::setEqualizerEnabled,
            onBandChange = playerViewModel::setBandLevel,
            onPreset = playerViewModel::useEqualizerPreset,
            onBassChange = playerViewModel::setBassStrength,
            onLoudnessChange = playerViewModel::setLoudness,
            onDismiss = { equalizerOpen = false },
        )
    }
}

private val sheetSpring = spring(
    dampingRatio = 0.9f,
    stiffness = 420f,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

@Composable
private fun PermissionScreen(showSettings: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.Background).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandTitle()
            Spacer(Modifier.height(12.dp))
            Text(
                "Allow access to audio files to play the music on this device.",
                color = Palette.Secondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = if (showSettings) onOpenSettings else onRequest) {
                Text(if (showSettings) "Open settings" else "Allow access")
            }
        }
    }
}
