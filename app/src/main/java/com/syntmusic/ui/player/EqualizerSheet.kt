package com.syntmusic.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syntmusic.playback.EqualizerBand
import com.syntmusic.playback.EqualizerState
import com.syntmusic.ui.Pill
import com.syntmusic.ui.theme.Palette
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(
    state: EqualizerState,
    onEnabledChange: (Boolean) -> Unit,
    onBandChange: (Int, Int) -> Unit,
    onPreset: (Int) -> Unit,
    onBassChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface,
    ) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Equalizer", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = state.enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = state.available,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.Background,
                        checkedTrackColor = Palette.Primary,
                        uncheckedThumbColor = Palette.Secondary,
                        uncheckedTrackColor = Palette.SurfaceHigh,
                        uncheckedBorderColor = Palette.Inactive,
                    ),
                )
            }

            if (!state.available || state.bands.isEmpty()) {
                Text(
                    "Start playing a song to adjust the sound.",
                    color = Palette.Secondary,
                    modifier = Modifier.padding(20.dp),
                )
                return@Column
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 16.dp),
            ) {
                item {
                    Pill("Custom", selected = state.preset == EqualizerState.CUSTOM, onClick = {})
                }
                itemsIndexed(state.presets) { index, name ->
                    Pill(name, selected = state.preset == index, onClick = { onPreset(index) })
                }
            }

            EqualizerCurve(
                bands = state.bands,
                minLevel = state.minLevel,
                maxLevel = state.maxLevel,
                dimmed = !state.enabled,
                onBandChange = onBandChange,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            if (state.bassSupported) {
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Text("Bass boost", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("${state.bassStrength / 10}%", color = Palette.Secondary, style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = state.bassStrength / 1000f,
                    onValueChange = { onBassChange((it * 1000).roundToInt()) },
                    enabled = state.enabled,
                    colors = SliderDefaults.colors(
                        thumbColor = Palette.Primary,
                        activeTrackColor = Palette.Primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                    ),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun EqualizerCurve(
    bands: List<EqualizerBand>,
    minLevel: Int,
    maxLevel: Int,
    dimmed: Boolean,
    onBandChange: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnBandChange by rememberUpdatedState(onBandChange)
    var activeBand by remember { mutableIntStateOf(-1) }
    val alpha by animateFloatAsState(if (dimmed) 0.45f else 1f, tween(250), label = "eqAlpha")
    val count = bands.size
    val range = (maxLevel - minLevel).coerceAtLeast(1)

    Column(modifier.graphicsLayer { this.alpha = alpha }) {
        Row(Modifier.fillMaxWidth()) {
            bands.forEachIndexed { i, band ->
                Text(
                    text = formatDb(band.level),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == activeBand) Palette.Primary else Palette.Secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .pointerInput(count, minLevel, maxLevel) {
                    val pad = 14.dp.toPx()
                    fun levelAt(y: Float): Int {
                        val f = ((y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f)
                        return (maxLevel - f * range).roundToInt()
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val band = (down.position.x / size.width * count).toInt().coerceIn(0, count - 1)
                        activeBand = band
                        down.consume()
                        currentOnBandChange(band, levelAt(down.position.y))
                        drag(down.id) { change ->
                            change.consume()
                            currentOnBandChange(band, levelAt(change.position.y))
                        }
                        activeBand = -1
                    }
                }
                .drawBehind {
                    val pad = 14.dp.toPx()
                    val h = size.height - 2 * pad
                    fun y(level: Int) = pad + (maxLevel - level).toFloat() / range * h
                    val xs = List(count) { (it + 0.5f) * size.width / count }

                    xs.forEach { x ->
                        drawLine(Color.White.copy(alpha = 0.07f), Offset(x, pad), Offset(x, pad + h), 3.dp.toPx(), StrokeCap.Round)
                    }
                    drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, y(0)), Offset(size.width, y(0)), 1.dp.toPx())

                    val curve = Path().apply {
                        moveTo(0f, y(bands.first().level))
                        lineTo(xs.first(), y(bands.first().level))
                        for (i in 1 until count) {
                            val x0 = xs[i - 1]
                            val y0 = y(bands[i - 1].level)
                            val x1 = xs[i]
                            val y1 = y(bands[i].level)
                            val mid = (x0 + x1) / 2
                            cubicTo(mid, y0, mid, y1, x1, y1)
                        }
                        lineTo(size.width, y(bands.last().level))
                    }
                    val fill = Path().apply {
                        addPath(curve)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(fill, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent)))
                    drawPath(curve, Palette.Primary, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))

                    xs.forEachIndexed { i, x ->
                        val center = Offset(x, y(bands[i].level))
                        drawCircle(Palette.Primary, radius = (if (i == activeBand) 11 else 8).dp.toPx(), center = center)
                        drawCircle(Palette.Surface, radius = 3.dp.toPx(), center = center)
                    }
                },
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            bands.forEach { band ->
                Text(
                    text = formatHz(band.centerHz),
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.Secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun formatHz(hz: Int): String = when {
    hz >= 1000 -> {
        val k = hz / 1000f
        if (k >= 10 || k % 1f == 0f) "${k.roundToInt()}k" else "%.1fk".format(k)
    }
    else -> "$hz"
}

private fun formatDb(millibels: Int): String {
    val db = (millibels / 100f).roundToInt()
    return if (db > 0) "+$db" else "$db"
}
