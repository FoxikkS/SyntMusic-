package com.syntmusic.playback

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.log10
import kotlin.math.roundToInt

/** A target curve, gain (dB) at frequency (Hz), interpolated onto the device's bands. */
private class CurvePreset(val name: String, val points: List<Pair<Int, Float>>) {
    fun gainAt(hz: Int): Float {
        val f = log10(hz.coerceAtLeast(1).toFloat())
        val first = points.first()
        val last = points.last()
        if (hz <= first.first) return first.second
        if (hz >= last.first) return last.second
        val i = points.indexOfFirst { it.first >= hz }
        val (f0, g0) = points[i - 1]
        val (f1, g1) = points[i]
        val t = (f - log10(f0.toFloat())) / (log10(f1.toFloat()) - log10(f0.toFloat()))
        return g0 + (g1 - g0) * t
    }
}

private val curvePresets = listOf(
    // Approximate: no public measurements exist for these. Assumes the usual consumer ANC
    // over-ear signature (mid-bass bloom, recessed presence region) and nudges it towards
    // a Harman-like balance. Fine-tune by ear; any edit becomes "Custom".
    CurvePreset(
        "Honor Choice Pro",
        listOf(
            30 to 1.5f, 60 to 1f, 120 to -1.5f, 230 to -2f, 500 to -0.5f, 910 to 0f,
            2_000 to 1f, 3_600 to 2.5f, 6_000 to 1f, 10_000 to 0.5f, 14_000 to 2f,
        ),
    ),
)

/** [level] is in millibels; [centerHz] in Hz. */
data class EqualizerBand(val centerHz: Int, val level: Int)

data class EqualizerState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val minLevel: Int = -1500,
    val maxLevel: Int = 1500,
    val bands: List<EqualizerBand> = emptyList(),
    val presets: List<String> = emptyList(),
    /** Index into [presets], or [CUSTOM]. */
    val preset: Int = CUSTOM,
    val bassSupported: Boolean = false,
    /** 0..1000 */
    val bassStrength: Int = 0,
) {
    companion object {
        const val CUSTOM = -1
    }
}

/**
 * System audio effects bound to the player's audio session. Settings persist and are
 * re-applied whenever the session changes.
 */
class EqualizerController(context: Context) {

    private val prefs = context.getSharedPreferences("equalizer", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(EqualizerState(enabled = prefs.getBoolean(KEY_ENABLED, false)))
    val state: StateFlow<EqualizerState> = _state.asStateFlow()

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null

    fun attach(audioSessionId: Int) {
        release()
        if (audioSessionId <= 0) return
        val eq = runCatching { Equalizer(0, audioSessionId) }.getOrNull() ?: return
        equalizer = eq
        bassBoost = runCatching { BassBoost(0, audioSessionId) }.getOrNull()?.takeIf { it.strengthSupported }

        val bandCount = eq.numberOfBands.toInt()
        val presets = curvePresets.map { it.name } + (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) }
        val savedPreset = prefs.getInt(KEY_PRESET, EqualizerState.CUSTOM).takeIf { it in presets.indices }
            ?: EqualizerState.CUSTOM
        val savedLevels = prefs.getString(KEY_LEVELS, null)?.split(',')?.mapNotNull { it.toIntOrNull() }
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val bass = prefs.getInt(KEY_BASS, 0)

        runCatching {
            if (savedPreset != EqualizerState.CUSTOM) {
                applyPreset(eq, savedPreset)
            } else if (savedLevels?.size == bandCount) {
                savedLevels.forEachIndexed { i, level -> eq.setBandLevel(i.toShort(), level.toShort()) }
            }
            bassBoost?.setStrength(bass.toShort())
            eq.enabled = enabled
            bassBoost?.enabled = enabled
        }

        val range = eq.bandLevelRange
        _state.value = EqualizerState(
            available = true,
            enabled = enabled,
            minLevel = range[0].toInt(),
            maxLevel = range[1].toInt(),
            bands = readBands(eq),
            presets = presets,
            preset = savedPreset,
            bassSupported = bassBoost != null,
            bassStrength = bass,
        )
    }

    fun setEnabled(enabled: Boolean) {
        runCatching {
            equalizer?.enabled = enabled
            bassBoost?.enabled = enabled
        }
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.update { it.copy(enabled = enabled) }
    }

    fun setBandLevel(band: Int, level: Int) {
        val eq = equalizer ?: return
        val current = _state.value
        val clamped = level.coerceIn(current.minLevel, current.maxLevel)
        runCatching { eq.setBandLevel(band.toShort(), clamped.toShort()) }
        val bands = current.bands.mapIndexed { i, b -> if (i == band) b.copy(level = clamped) else b }
        prefs.edit()
            .putInt(KEY_PRESET, EqualizerState.CUSTOM)
            .putString(KEY_LEVELS, bands.joinToString(",") { it.level.toString() })
            .apply()
        _state.update { it.copy(bands = bands, preset = EqualizerState.CUSTOM) }
    }

    fun usePreset(preset: Int) {
        val eq = equalizer ?: return
        runCatching { applyPreset(eq, preset) }
        val bands = readBands(eq)
        prefs.edit()
            .putInt(KEY_PRESET, preset)
            .putString(KEY_LEVELS, bands.joinToString(",") { it.level.toString() })
            .apply()
        _state.update { it.copy(bands = bands, preset = preset) }
    }

    fun setBassStrength(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        runCatching { bassBoost?.setStrength(clamped.toShort()) }
        prefs.edit().putInt(KEY_BASS, clamped).apply()
        _state.update { it.copy(bassStrength = clamped) }
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        equalizer = null
        bassBoost = null
        _state.update { it.copy(available = false) }
    }

    /** Indices first cover [curvePresets], then the device's own presets. */
    private fun applyPreset(eq: Equalizer, preset: Int) {
        val curve = curvePresets.getOrNull(preset)
        if (curve == null) {
            eq.usePreset((preset - curvePresets.size).toShort())
            return
        }
        val range = eq.bandLevelRange
        for (band in 0 until eq.numberOfBands) {
            val hz = eq.getCenterFreq(band.toShort()) / 1000
            val level = (curve.gainAt(hz) * 100).roundToInt().coerceIn(range[0].toInt(), range[1].toInt())
            eq.setBandLevel(band.toShort(), level.toShort())
        }
    }

    private fun readBands(eq: Equalizer): List<EqualizerBand> =
        (0 until eq.numberOfBands).map { i ->
            EqualizerBand(centerHz = eq.getCenterFreq(i.toShort()) / 1000, level = eq.getBandLevel(i.toShort()).toInt())
        }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_PRESET = "preset"
        const val KEY_LEVELS = "levels"
        const val KEY_BASS = "bass"
    }
}
