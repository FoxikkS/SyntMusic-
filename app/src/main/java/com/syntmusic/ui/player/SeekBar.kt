package com.syntmusic.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.syntmusic.ui.formatTime
import com.syntmusic.ui.theme.Palette

/**
 * Progress bar with drag and tap-to-seek. Progress is drawn in the draw phase, so following
 * a per-frame position costs a redraw, not a recomposition.
 */
@Composable
fun SeekBar(
    position: State<Long>,
    duration: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    preview: (Long) -> String? = { null },
) {
    val haptics = LocalHapticFeedback.current
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val currentOnSeek by rememberUpdatedState(onSeek)
    val durationState = rememberUpdatedState(duration)
    val dragging = dragFraction != null
    val thickness by animateDpAsState(if (dragging) 6.dp else 4.dp, spring(stiffness = 700f), label = "barThickness")
    val thumbRadius by animateDpAsState(if (dragging) 9.dp else 6.dp, spring(stiffness = 700f), label = "thumb")
    val shownSecond by remember {
        derivedStateOf {
            val ms = dragFraction?.let { (it * durationState.value).toLong() } ?: position.value
            ms / 1000
        }
    }

    Column(modifier.fillMaxWidth()) {
        ScrubBubble(
            visible = dragging,
            fraction = { dragFraction ?: 0f },
            duration = { durationState.value },
            preview = preview,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val d = durationState.value
                        if (d > 0) currentOnSeek(((offset.x / size.width).coerceIn(0f, 1f) * d).toLong())
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            val d = durationState.value
                            dragFraction?.let { if (d > 0) currentOnSeek((it * d).toLong()) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }
                .drawBehind {
                    val d = durationState.value
                    val fraction = dragFraction ?: if (d > 0) (position.value.toFloat() / d).coerceIn(0f, 1f) else 0f
                    val y = size.height / 2
                    val x = size.width * fraction
                    val stroke = thickness.toPx()
                    drawLine(Color.White.copy(alpha = 0.2f), Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
                    drawLine(Palette.Primary, Offset(0f, y), Offset(x, y), stroke, StrokeCap.Round)
                    drawCircle(Palette.Primary, radius = thumbRadius.toPx(), center = Offset(x, y))
                },
        )
        Row(Modifier.fillMaxWidth()) {
            TimeLabel(shownSecond * 1000)
            Spacer(Modifier.weight(1f))
            TimeLabel(duration)
        }
    }
}

@Composable
private fun TimeLabel(ms: Long) {
    Text(
        text = formatTime(ms),
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = Palette.Primary.copy(alpha = 0.6f),
    )
}

/** Zero-height anchor; the bubble floats above the bar while scrubbing. */
@Composable
private fun ScrubBubble(
    visible: Boolean,
    fraction: () -> Float,
    duration: () -> Long,
    preview: (Long) -> String?,
) {
    Box(Modifier.fillMaxWidth().height(0.dp)) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(120)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 600f), initialScale = 0.8f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.9f),
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val center = fraction() * constraints.maxWidth
                val x = (center - placeable.width / 2).toInt().coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                layout(constraints.maxWidth, 0) { placeable.place(x, -placeable.height - 6.dp.roundToPx()) }
            },
        ) {
            val ms = (fraction() * duration()).toLong()
            Column(
                Modifier
                    .widthIn(max = 260.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.Primary)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    formatTime(ms),
                    color = Palette.Background,
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                )
                preview(ms)?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = Palette.Background.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
