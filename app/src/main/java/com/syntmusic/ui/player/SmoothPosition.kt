package com.syntmusic.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.launch

private const val BACKWARD_TOLERANCE_MS = 250L

/**
 * Interpolates the player position on every display frame (60/90/120 Hz) between the
 * controller's periodic updates, so the progress bar and lyrics move fluidly.
 * Readers should consume it in the draw phase or through derivedStateOf.
 */
@Composable
fun rememberSmoothPosition(source: State<Long>, playing: Boolean): State<Long> {
    val smooth = remember { mutableLongStateOf(source.value) }
    LaunchedEffect(playing) {
        if (!playing) {
            snapshotFlow { source.value }.collect { smooth.longValue = it }
            return@LaunchedEffect
        }
        var anchor = source.value
        var anchorFrame = -1L
        launch {
            snapshotFlow { source.value }.collect { reported ->
                val predicted = smooth.longValue
                // Absorb tiny backwards corrections to keep motion monotonic; real seeks snap.
                anchor = if (reported < predicted && predicted - reported < BACKWARD_TOLERANCE_MS) predicted else reported
                anchorFrame = -1L
            }
        }
        while (true) {
            withFrameMillis { frame ->
                if (anchorFrame < 0) anchorFrame = frame
                smooth.longValue = anchor + (frame - anchorFrame)
            }
        }
    }
    return smooth
}
