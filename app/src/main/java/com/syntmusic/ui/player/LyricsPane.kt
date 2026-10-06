package com.syntmusic.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syntmusic.lyrics.Lyrics
import com.syntmusic.lyrics.LyricsType
import com.syntmusic.ui.theme.Palette
import com.syntmusic.ui.verticalFadingEdges
import kotlinx.coroutines.delay

/** Where the active line rests, as a fraction of the viewport height. */
private const val ANCHOR_FRACTION = 0.3f
private const val RESUME_AFTER_USER_SCROLL_MS = 2_500L
private const val INACTIVE_ALPHA = 0.35f
/** Longer touches are treated as hesitation / reading, not a seek. */
private const val MAX_TAP_MS = 350L
private const val UNSUNG_ALPHA = 0.45f

private val syncedStyle = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
private val plainStyle = TextStyle(fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun LyricsPane(
    state: LyricsUiState,
    position: State<Long>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        LyricsUiState.Loading -> Box(modifier, contentAlignment = Alignment.Center) {
            Text("Looking for lyrics…", color = Palette.Primary.copy(alpha = 0.45f))
        }
        LyricsUiState.NotFound -> NoLyrics(modifier)
        is LyricsUiState.Loaded -> key(state.trackId) {
            if (state.lyrics.type == LyricsType.SYNCED) {
                SyncedLyrics(state.lyrics, position, onSeek, modifier)
            } else {
                PlainLyrics(state.lyrics, modifier)
            }
        }
    }
}

@Composable
private fun SyncedLyrics(
    lyrics: Lyrics,
    position: State<Long>,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    // Changes only when the line changes, although position updates every frame.
    val positionIndex by remember(lyrics) { derivedStateOf { lyrics.activeIndex(position.value) } }

    // A tapped line lights up instantly, before the seek is reflected in the position.
    var tappedIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(tappedIndex) {
        if (tappedIndex >= 0) {
            delay(1_200)
            tappedIndex = -1
        }
    }
    LaunchedEffect(positionIndex) {
        if (positionIndex == tappedIndex) tappedIndex = -1
    }
    val activeIndex = if (tappedIndex >= 0) tappedIndex else positionIndex

    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var userScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(isDragged) {
        if (isDragged) {
            userScrolling = true
        } else if (userScrolling) {
            delay(RESUME_AFTER_USER_SCROLL_MS)
            userScrolling = false
        }
    }

    var pressedIndex by remember { mutableIntStateOf(-1) }
    val haptics = LocalHapticFeedback.current

    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(activeIndex, userScrolling, pressedIndex >= 0) {
        if (userScrolling || pressedIndex >= 0) return@LaunchedEffect
        listState.scrollToAnchor(activeIndex.coerceAtLeast(0), animate = initialized)
        initialized = true
    }

    BoxWithConstraints(modifier) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = maxHeight * ANCHOR_FRACTION, bottom = maxHeight * 0.7f),
            modifier = Modifier.fillMaxSize().verticalFadingEdges(),
        ) {
            itemsIndexed(lyrics.lines) { index, line ->
                val isActive = index == activeIndex
                val alpha by animateFloatAsState(if (isActive) 1f else INACTIVE_ALPHA, tween(320), label = "lineAlpha")
                val scale by animateFloatAsState(
                    if (isActive) 1f else 0.96f, spring(dampingRatio = 0.8f, stiffness = 300f), label = "lineScale",
                )
                val highlight by animateFloatAsState(
                    if (pressedIndex == index) 0.1f else 0f, tween(150), label = "linePress",
                )
                val seekToLine = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    userScrolling = false
                    tappedIndex = index
                    line.timestamp?.let(onSeek)
                    Unit
                }
                val currentSeekToLine by rememberUpdatedState(seekToLine)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            if (highlight > 0f) {
                                drawRoundRect(Color.White.copy(alpha = highlight), cornerRadius = CornerRadius(12.dp.toPx()))
                            }
                        }
                        .lineTap(
                            key = index,
                            // A touch that stops the user's own fling only stops it; it doesn't seek.
                            canTap = { !(userScrolling && listState.isScrollInProgress) },
                            onPress = { pressed -> pressedIndex = if (pressed) index else -1 },
                            onTap = { currentSeekToLine() },
                        )
                        .semantics {
                            role = Role.Button
                            onClick { seekToLine(); true }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        },
                ) {
                    if (line.text.isBlank()) {
                        val start = line.timestamp ?: 0L
                        val end = lyrics.lines.getOrNull(index + 1)?.timestamp ?: (start + 5_000)
                        InstrumentalDots(start, end, position, isActive)
                    } else {
                        val lineStart = line.timestamp ?: 0L
                        val next = lyrics.lines.getOrNull(index + 1)?.timestamp
                        // Sung part of the line: estimated from the line's length, capped by the next line.
                        val fillMs = maxOf(1_200L, line.text.length * 75L)
                            .let { if (next != null) minOf(it, next - lineStart - 150) else it }
                            .coerceAtLeast(1L)
                        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                        Text(
                            text = line.text,
                            style = syncedStyle,
                            color = Palette.Primary,
                            onTextLayout = { layout = it },
                            modifier = if (isActive) {
                                Modifier.karaokeFill(
                                    layout = { layout },
                                    progress = { ((position.value - lineStart).toFloat() / fillMs).coerceIn(0f, 1f) },
                                )
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Lights the active line up from left to right as it's sung: a soft-edged mask sweeps across
 * each wrapped line in turn. Drawn per frame in the draw phase only.
 */
private fun Modifier.karaokeFill(layout: () -> TextLayoutResult?, progress: () -> Float): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val text = layout() ?: return@drawWithContent
        val p = progress()
        if (p >= 1f) return@drawWithContent
        val reached = p * text.layoutInput.text.length
        val soft = 36.dp.toPx()
        for (line in 0 until text.lineCount) {
            val start = text.getLineStart(line)
            val end = text.getLineEnd(line)
            val left = text.getLineLeft(line)
            val right = text.getLineRight(line)
            val edge = when {
                reached >= end -> right + soft
                reached <= start -> left - soft
                else -> left + (right - left) * (reached - start) / (end - start)
            }
            val top = text.getLineTop(line)
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black, Color.Black.copy(alpha = UNSUNG_ALPHA)),
                    startX = edge - soft / 2,
                    endX = edge + soft / 2,
                ),
                topLeft = Offset(0f, top),
                size = Size(size.width, text.getLineBottom(line) - top),
                blendMode = BlendMode.DstIn,
            )
        }
    }

@Composable
private fun InstrumentalDots(start: Long, end: Long, position: State<Long>, active: Boolean) {
    Box(
        Modifier
            .padding(vertical = 8.dp)
            .size(width = 54.dp, height = 18.dp)
            .drawBehind {
                val progress = if (active && end > start) {
                    ((position.value - start).toFloat() / (end - start)).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val radius = 5.dp.toPx()
                val step = size.width / 3
                for (i in 0..2) {
                    val fill = (progress * 3 - i).coerceIn(0f, 1f)
                    drawCircle(
                        color = Color.White.copy(alpha = 0.3f + 0.7f * fill),
                        radius = radius * (0.85f + 0.3f * fill),
                        center = Offset(step * (i + 0.5f), size.height / 2),
                    )
                }
            },
    )
}

@Composable
private fun PlainLyrics(lyrics: Lyrics, modifier: Modifier) {
    LazyColumn(
        modifier = modifier.verticalFadingEdges(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 64.dp),
    ) {
        item {
            Text(
                "Not synced",
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                color = Palette.Primary.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        itemsIndexed(lyrics.lines) { _, line ->
            if (line.text.isBlank()) {
                Spacer(Modifier.height(18.dp))
            } else {
                Text(
                    text = line.text,
                    style = plainStyle,
                    color = Palette.Primary.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun NoLyrics(modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("No lyrics available", style = plainStyle, color = Palette.Primary.copy(alpha = 0.8f))
            Text(
                "Add .lrc files via ⋮ → Lyrics folder",
                color = Palette.Primary.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Tap detection that also works while the list is auto-scrolling: it observes the Initial
 * pass, so the list's own scroll handling can't swallow the tap. Moving past touch slop
 * cancels it, so real scroll gestures never seek.
 */
private fun Modifier.lineTap(
    key: Any,
    canTap: () -> Boolean,
    onPress: (Boolean) -> Unit,
    onTap: () -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!canTap()) return@awaitEachGesture
        onPress(true)
        var tapped = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.changedToUpIgnoreConsumed()) {
                tapped = true
                break
            }
            val moved = (change.position - down.position).getDistance() > viewConfiguration.touchSlop / 2
            val held = change.uptimeMillis - down.uptimeMillis > MAX_TAP_MS
            if (moved || held) break
        }
        onPress(false)
        if (tapped) onTap()
    }
}

private suspend fun LazyListState.scrollToAnchor(index: Int, animate: Boolean) {
    fun visibleItem() = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (visibleItem() == null) {
        scrollToItem(index)
        withFrameNanos { }
    }
    val item = visibleItem() ?: return
    val start = layoutInfo.viewportStartOffset
    val anchor = start + (layoutInfo.viewportEndOffset - start) * ANCHOR_FRACTION
    val delta = item.offset - anchor
    if (delta == 0f) return
    if (animate) {
        animateScrollBy(delta, tween(durationMillis = 520, easing = FastOutSlowInEasing))
    } else {
        scrollBy(delta)
    }
}
