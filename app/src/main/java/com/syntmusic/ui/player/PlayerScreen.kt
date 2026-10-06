package com.syntmusic.ui.player

import android.net.Uri
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.syntmusic.playback.SleepTimer
import com.syntmusic.ui.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.syntmusic.ui.ArtworkRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.syntmusic.data.Track
import com.syntmusic.playback.PlayerState
import com.syntmusic.ui.Artwork
import com.syntmusic.ui.Pill
import com.syntmusic.ui.horizontalBleed
import com.syntmusic.ui.pressScale
import com.syntmusic.ui.rememberArtworkColor
import com.syntmusic.ui.theme.Palette

@OptIn(ExperimentalSharedTransitionApi::class)
private val artworkBounds = BoundsTransform { _, _ -> spring(dampingRatio = 0.86f, stiffness = 320f) }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerScreen(viewModel: PlayerViewModel, onClose: () -> Unit, onOpenEqualizer: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val reportedPosition = viewModel.position.collectAsStateWithLifecycle()
    // Read only in the draw phase / derived state, so ticks never recompose the whole screen.
    val position = rememberSmoothPosition(reportedPosition, state.isPlaying)
    val lyricsVisible by viewModel.lyricsVisible.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val onlineLyrics by viewModel.onlineLyrics.collectAsStateWithLifecycle()
    val sleepTimer by viewModel.sleepTimer.collectAsStateWithLifecycle()
    var showSleepTimer by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::setLyricsFolder)
    }

    BackHandler(onBack = onClose)
    val track = state.currentTrack ?: return
    val tint = rememberArtworkColor(track.artwork)

    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dismissDistance = with(LocalDensity.current) { 140.dp.toPx() }
    val dragState = rememberDraggableState { delta -> dragOffset = (dragOffset + delta).coerceAtLeast(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val progress = (dragOffset / size.height).coerceIn(0f, 1f)
                translationY = dragOffset
                scaleX = 1f - progress * 0.1f
                scaleY = scaleX
                shape = RoundedCornerShape((progress * 160f).coerceAtMost(28f).dp)
                clip = true
            }
            .drawBehind { drawRect(Palette.Background) }
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    if (dragOffset > dismissDistance || velocity > 2_000f) {
                        onClose()
                    } else {
                        animate(dragOffset, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                            dragOffset = value
                        }
                    }
                },
            ),
    ) {
        ArtworkBackdrop(track.artwork, tint)
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            TopBar(
                onClose = onClose,
                onEqualizer = onOpenEqualizer,
                onLyricsFolder = { folderPicker.launch(null) },
                onlineLyrics = onlineLyrics,
                onToggleOnlineLyrics = viewModel::toggleOnlineLyrics,
            )

            SharedTransitionLayout(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = lyricsVisible,
                    transitionSpec = {
                        fadeIn(tween(260, delayMillis = 90)) togetherWith fadeOut(tween(140)) using
                            SizeTransform(clip = false)
                    },
                    label = "lyricsMode",
                ) { showLyrics ->
                    val artworkModifier = Modifier.sharedElement(
                        state = rememberSharedContentState(key = "artwork"),
                        animatedVisibilityScope = this@AnimatedContent,
                        boundsTransform = artworkBounds,
                    )
                    if (showLyrics) {
                        LyricsLayout(track, artworkModifier, lyrics, position, viewModel::seekTo)
                    } else {
                        CoverLayout(track, artworkModifier, state.isPlaying, tint, viewModel::next, viewModel::previous)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SeekBar(
                position = position,
                duration = state.duration,
                onSeek = viewModel::seekTo,
                preview = { ms ->
                    (lyrics as? LyricsUiState.Loaded)?.lyrics?.let { l -> l.lines.getOrNull(l.activeIndex(ms))?.text }
                },
            )
            PlaybackControls(
                state = state,
                onShuffle = viewModel::toggleShuffle,
                onPrevious = viewModel::previous,
                onPlayPause = viewModel::togglePlayPause,
                onNext = viewModel::next,
                onRepeat = viewModel::cycleRepeatMode,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                Pill("Up next", selected = false, onClick = { showQueue = true }, icon = Icons.AutoMirrored.Rounded.QueueMusic)
                Pill("Lyrics", selected = lyricsVisible, onClick = viewModel::toggleLyrics, icon = Icons.Rounded.MusicNote)
                Pill(
                    text = sleepTimerLabel(sleepTimer),
                    selected = sleepTimer != null,
                    onClick = { showSleepTimer = true },
                    icon = Icons.Rounded.Bedtime,
                )
            }
        }
    }

    if (showSleepTimer) {
        SleepTimerSheet(
            onSelect = { minutes ->
                viewModel.setSleepTimer(minutes)
                showSleepTimer = false
            },
            onDismiss = { showSleepTimer = false },
        )
    }

    if (showQueue) {
        QueueSheet(
            state = state,
            onSelect = viewModel::playQueueItem,
            onRemove = viewModel::removeFromQueue,
            onMove = viewModel::moveInQueue,
            onDismiss = { showQueue = false },
        )
    }
}

@Composable
private fun CoverLayout(
    track: Track,
    artworkModifier: Modifier,
    isPlaying: Boolean,
    tint: State<Color>,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val swipe = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val swipeState = rememberDraggableState { delta -> scope.launch { swipe.snapTo(swipe.value + delta) } }
    val coverScale by animateFloatAsState(
        if (isPlaying) 1f else 0.86f, spring(dampingRatio = 0.65f, stiffness = 220f), label = "coverScale",
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cover = minOf(maxWidth, maxHeight - 96.dp).coerceAtLeast(120.dp)
        val coverPx = with(LocalDensity.current) { cover.toPx() }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Artwork(
                uri = track.artwork,
                modifier = artworkModifier
                    .size(cover)
                    .align(Alignment.CenterHorizontally)
                    .graphicsLayer {
                        val drag = (swipe.value / coverPx).coerceIn(-1.5f, 1.5f)
                        translationX = swipe.value
                        rotationZ = drag * 7f
                        alpha = 1f - (kotlin.math.abs(drag) * 0.6f).coerceAtMost(0.8f)
                        scaleX = coverScale
                        scaleY = coverScale
                    }
                    .draggable(
                        state = swipeState,
                        orientation = Orientation.Horizontal,
                        onDragStopped = { velocity ->
                            val direction = when {
                                swipe.value < -coverPx * 0.25f || velocity < -1_500f -> -1
                                swipe.value > coverPx * 0.25f || velocity > 1_500f -> 1
                                else -> 0
                            }
                            if (direction == 0) {
                                swipe.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 400f))
                            } else {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                swipe.animateTo(direction * coverPx * 1.3f, tween(170))
                                if (direction < 0) onNext() else onPrevious()
                                swipe.snapTo(-direction * coverPx * 0.7f)
                                swipe.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 280f))
                            }
                        },
                    )
                    .shadow(
                        elevation = 28.dp,
                        shape = RoundedCornerShape(16.dp),
                        ambientColor = lerp(tint.value, Color.White, 0.3f),
                        spotColor = lerp(tint.value, Color.White, 0.3f),
                    ),
                cornerRadius = 16.dp,
            )
            Spacer(Modifier.height(28.dp))
            TrackTitle(track, large = true)
        }
    }
}

/** Blurred, slowly drifting artwork under a dark scrim (Android 12+); a tinted gradient before that. */
@Composable
private fun ArtworkBackdrop(uri: Uri, tint: State<Color>) {
    val context = LocalContext.current
    // The blurred image is scaled and rotated past the edges, so it must be clipped.
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .drawBehind { drawRect(Brush.verticalGradient(0f to tint.value, 0.8f to Palette.Background)) },
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val drift by rememberInfiniteTransition(label = "drift").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse),
                label = "driftValue",
            )
            Crossfade(uri, animationSpec = tween(800), label = "backdrop") { artwork ->
                // A tiny image is enough: it gets blurred anyway, and it keeps the GPU work small.
                val request = remember(artwork) {
                    ImageRequest.Builder(context).data(ArtworkRequest(artwork)).size(64).build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val s = 1.5f + drift * 0.2f
                            scaleX = s
                            scaleY = s
                            rotationZ = -6f + drift * 12f
                            alpha = 0.6f
                            renderEffect = BlurEffect(60.dp.toPx(), 60.dp.toPx(), TileMode.Clamp)
                        },
                )
            }
        }
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        0f to Palette.Background.copy(alpha = 0.25f),
                        0.55f to Palette.Background.copy(alpha = 0.7f),
                        1f to Palette.Background.copy(alpha = 0.92f),
                    ),
                )
            },
        )
    }
}

@Composable
private fun LyricsLayout(
    track: Track,
    artworkModifier: Modifier,
    lyrics: LyricsUiState,
    position: State<Long>,
    onSeek: (Long) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.artwork, artworkModifier.size(56.dp), cornerRadius = 8.dp)
            Spacer(Modifier.width(14.dp))
            TrackTitle(track, large = false, modifier = Modifier.weight(1f))
        }
        LyricsPane(
            state = lyrics,
            position = position,
            onSeek = onSeek,
            modifier = Modifier.weight(1f).fillMaxWidth().horizontalBleed(12.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackTitle(track: Track, large: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = track.title,
            style = TextStyle(
                fontSize = if (large) 24.sp else 17.sp,
                lineHeight = if (large) 30.sp else 22.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = Palette.Primary,
            maxLines = 1,
            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000),
        )
        Text(
            text = track.artist,
            style = TextStyle(fontSize = if (large) 17.sp else 14.sp),
            color = Palette.Primary.copy(alpha = 0.65f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TopBar(
    onClose: () -> Unit,
    onEqualizer: () -> Unit,
    onLyricsFolder: () -> Unit,
    onlineLyrics: Boolean,
    onToggleOnlineLyrics: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).horizontalBleed(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close player", modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Equalizer") },
                    leadingIcon = { Icon(Icons.Rounded.Equalizer, null) },
                    onClick = {
                        menuOpen = false
                        onEqualizer()
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (onlineLyrics) "Online lyrics: On" else "Online lyrics: Off") },
                    leadingIcon = {
                        Icon(Icons.Rounded.Language, null, tint = Palette.Primary.copy(alpha = if (onlineLyrics) 1f else 0.4f))
                    },
                    onClick = onToggleOnlineLyrics,
                )
                DropdownMenuItem(
                    text = { Text("Lyrics folder…") },
                    leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) },
                    onClick = {
                        menuOpen = false
                        onLyricsFolder()
                    },
                )
            }
        }
    }
}

@Composable
private fun PlaybackControls(
    state: PlayerState,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlButton(
            icon = Icons.Rounded.Shuffle,
            description = "Shuffle",
            onClick = onShuffle,
            iconSize = 24.dp,
            active = state.shuffleEnabled,
        )
        ControlButton(Icons.Rounded.SkipPrevious, "Previous", onPrevious, size = 60.dp, iconSize = 40.dp)
        PlayPauseButton(state.isPlaying, onPlayPause)
        ControlButton(Icons.Rounded.SkipNext, "Next", onNext, size = 60.dp, iconSize = 40.dp)
        ControlButton(
            icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            description = "Repeat",
            onClick = onRepeat,
            iconSize = 24.dp,
            active = state.repeatMode != Player.REPEAT_MODE_OFF,
        )
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    size: Dp = 48.dp,
    iconSize: Dp = 32.dp,
    active: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val tint by animateColorAsState(
        if (active) Palette.Primary else Palette.Primary.copy(alpha = 0.4f), tween(200), label = "controlTint",
    )
    Box(
        modifier = Modifier
            .size(size)
            .pressScale(interaction, 0.82f)
            .clip(CircleShape)
            .clickable(interaction, ripple(bounded = false, radius = size / 2), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(76.dp)
            .pressScale(interaction, 0.88f)
            .clip(CircleShape)
            .background(Palette.Primary)
            .clickable(interaction, ripple(color = Color.Black)) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = {
                (scaleIn(spring(dampingRatio = 0.6f, stiffness = 700f), initialScale = 0.5f) + fadeIn(tween(120))) togetherWith
                    (scaleOut(tween(120), targetScale = 0.5f) + fadeOut(tween(100)))
            },
            label = "playPause",
        ) { playing ->
            Icon(
                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = Palette.Background,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

@Composable
private fun sleepTimerLabel(timer: SleepTimer?): String {
    val remaining by produceState(0L, timer) {
        while (timer is SleepTimer.At) {
            value = (timer.endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            delay(1_000)
        }
    }
    return when (timer) {
        null -> ""
        SleepTimer.EndOfTrack -> "End"
        is SleepTimer.At -> formatTime(remaining + 999)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(onSelect: (Int?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Surface) {
        Text(
            "Sleep timer",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            "Music fades out gently before stopping.",
            color = Palette.Secondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        val options = listOf("Off" to null, "15 min" to 15, "30 min" to 30, "45 min" to 45, "1 hour" to 60, "End of track" to 0)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 16.dp, bottom = 32.dp),
        ) {
            items(options) { (label, minutes) ->
                Pill(label, selected = false, onClick = { onSelect(minutes) })
            }
        }
    }
}
