package com.syntmusic.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syntmusic.playback.PlayerState
import com.syntmusic.ui.Artwork
import com.syntmusic.ui.pressScale
import com.syntmusic.ui.rememberArtworkColor
import com.syntmusic.ui.theme.Palette

@Composable
fun MiniPlayer(
    state: PlayerState,
    position: State<Long>,
    onPlayPause: () -> Unit,
    onOpen: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val swipe = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var lift by remember { mutableFloatStateOf(0f) }
    val track = state.currentTrack ?: return
    val tint = rememberArtworkColor(track.artwork)
    val interaction = remember { MutableInteractionSource() }
    val duration = state.duration

    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .fillMaxWidth()
            .pressScale(interaction, 0.97f)
            .graphicsLayer { translationY = -lift.coerceAtLeast(0f) * 0.4f }
            .draggable(
                state = rememberDraggableState { delta -> lift -= delta },
                orientation = Orientation.Vertical,
                onDragStopped = {
                    if (lift > with(density) { 40.dp.toPx() }) onOpen()
                    animate(lift, 0f, animationSpec = spring(stiffness = 500f)) { value, _ -> lift = value }
                },
            )
            .draggable(
                state = rememberDraggableState { delta -> scope.launch { swipe.snapTo(swipe.value + delta) } },
                orientation = Orientation.Horizontal,
                onDragStopped = { velocity ->
                    val threshold = with(density) { 90.dp.toPx() }
                    when {
                        swipe.value < -threshold || velocity < -1_500f -> {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNext()
                        }
                        swipe.value > threshold || velocity > 1_500f -> {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onPrevious()
                        }
                    }
                    swipe.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 400f))
                },
            )
            .clip(RoundedCornerShape(16.dp))
            .drawBehind {
                drawRect(lerp(Palette.Surface, tint.value, 0.55f))
                val fraction = if (duration > 0) (position.value.toFloat() / duration).coerceIn(0f, 1f) else 0f
                val y = size.height - 1.dp.toPx()
                val stroke = 2.dp.toPx()
                drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, y), Offset(size.width, y), stroke)
                drawLine(Palette.Primary, Offset(0f, y), Offset(size.width * fraction, y), stroke)
            }
            .clickable(interaction, ripple(), onClick = onOpen)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.artwork, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier
                .weight(1f)
                .graphicsLayer {
                    translationX = swipe.value
                    alpha = 1f - (kotlin.math.abs(swipe.value) / size.width).coerceIn(0f, 0.7f)
                },
        ) {
            Text(
                track.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist, style = MaterialTheme.typography.bodyMedium, color = Palette.Primary.copy(alpha = 0.65f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onPlayPause) {
            AnimatedContent(
                targetState = state.isPlaying,
                transitionSpec = {
                    (scaleIn(spring(dampingRatio = 0.6f, stiffness = 700f), initialScale = 0.5f) + fadeIn(tween(120))) togetherWith
                        (scaleOut(tween(120), targetScale = 0.5f) + fadeOut(tween(100)))
                },
                label = "miniPlayPause",
            ) { playing ->
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                )
            }
        }
    }
}
