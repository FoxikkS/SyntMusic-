package com.syntmusic.playback

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.media.audiofx.DynamicsProcessing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** [level] is in millibels; [centerHz] in Hz. */
data class EqualizerBand(val centerHz: Int, val level: Int)

data class EqualizerState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val minLevel: Int = -MAX_LEVEL_MB,
    val maxLevel: Int = MAX_LEVEL_MB,
    val bands: List<EqualizerBand> = emptyList(),
    val presets: List<String> = emptyList(),
    /** Index into [presets], or [CUSTOM]. */
    val preset: Int = CUSTOM,
    val bassSupported: Boolean = true,
    /** 0..1000 */
    val bassStrength: Int = 0,
    /** Adds low end and a little treble as the volume goes down, where the ear hears them less. */
    val loudness: Boolean = false,
) {
    companion object {
        const val CUSTOM = -1
    }
}

private const val MAX_LEVEL_MB = 1200

private val BAND_CENTERS = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

/** Upper edge of each band: geometric midpoint to the next center. */
private val BAND_EDGES = BAND_CENTERS.mapIndexed { i, hz ->
    BAND_CENTERS.getOrNull(i + 1)?.let { sqrt(hz.toFloat() * it) } ?: 20_000f
}

/** Extra low-end gain per band at full bass boost, in dB. */
private val BASS_BOOST_DB = listOf(8f, 7f, 4f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)

/** Loudness compensation at very low volume, in dB; scales down to zero at full volume. */
private val LOUDNESS_DB = listOf(6f, 5f, 3f, 1f, 0f, 0f, 0f, 0.5f, 1.5f, 2.5f)

/** Share of the loudest boost taken off the input; the limiter handles what's left. */
private const val HEADROOM_SHARE = 0.6f

private class Preset(val name: String, val levels: List<Int>)

private fun preset(name: String, vararg db: Float) = Preset(name, db.map { (it * 100).roundToInt() })

/** Interpolates a target curve (Hz to dB) onto the bands, in log frequency. */
private fun curve(name: String, vararg points: Pair<Int, Float>): Preset {
    fun gainAt(hz: Int): Float {
        if (hz <= points.first().first) return points.first().second
        if (hz >= points.last().first) return points.last().second
        val i = points.indexOfFirst { it.first >= hz }
        val (f0, g0) = points[i - 1]
        val (f1, g1) = points[i]
        val t = (log10(hz.toFloat()) - log10(f0.toFloat())) / (log10(f1.toFloat()) - log10(f0.toFloat()))
        return g0 + (g1 - g0) * t
    }
    return Preset(name, BAND_CENTERS.map { (gainAt(it) * 100).roundToInt() })
}

private val presets = listOf(
    preset("Flat", 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    // Approximate: no public measurements exist for these. Assumes the usual consumer ANC
    // over-ear signature (mid-bass bloom, recessed presence region) and nudges it towards
    // a Harman-like balance. Fine-tune by ear; any edit becomes "Custom".
    curve(
        "Honor Choice Pro",
        30 to 1.5f, 60 to 1f, 120 to -1.5f, 230 to -2f, 500 to -0.5f, 910 to 0f,
        2_000 to 1f, 3_600 to 2.5f, 6_000 to 1f, 10_000 to 0.5f, 14_000 to 2f,
    ),
    preset("Bass", 6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
    preset("Hip-Hop", 5f, 5f, 3f, 0f, -1f, 0f, 1f, 1.5f, 1f, 2f),
    preset("Electronic", 5f, 4f, 1f, 0f, -1f, 1f, 0f, 2f, 4f, 4f),
    preset("Rock", 4f, 3f, 1f, -1f, -1.5f, 0f, 2f, 3f, 3f, 3f),
    preset("Pop", -1f, 0f, 2f, 3f, 2f, 0f, -0.5f, 0f, 1f, 1.5f),
    preset("Vocal", -2f, -2f, -1f, 1f, 2.5f, 3f, 2.5f, 1.5f, 0f, -1f),
    preset("Acoustic", 2f, 2f, 1f, 0f, 1f, 1f, 2f, 2.5f, 2f, 1f),
    preset("Loudness", 5f, 4f, 2f, 0f, -1f, -1f, 0f, 2f, 4f, 5f),
    preset("Treble", 0f, 0f, 0f, 0f, 0f, 0f, 1f, 3f, 4.5f, 5f),
)

/**
 * 10-band EQ on the player's audio session using DynamicsProcessing: pre-gain keeps headroom
 * for boosts and a limiter catches peaks, so raised bands get louder without clipping.
 * Settings persist and are re-applied whenever the session changes.
 */
class EqualizerController(context: Context) {

    private val prefs = context.getSharedPreferences("equalizer2", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(savedState())
    val state: StateFlow<EqualizerState> = _state.asStateFlow()

    private var dynamics: DynamicsProcessing? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (_state.value.loudness) apply()
        }
    }

    init {
        // Volume changes are written to system settings; that's the only public signal for them.
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
    }

    fun attach(audioSessionId: Int) {
        release()
        if (audioSessionId <= 0) return
        dynamics = runCatching { DynamicsProcessing(0, audioSessionId, config()) }.getOrNull()
        _state.update { it.copy(available = dynamics != null) }
        apply()
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.update { it.copy(enabled = enabled) }
        apply()
    }

    fun setBandLevel(band: Int, level: Int) {
        val clamped = level.coerceIn(-MAX_LEVEL_MB, MAX_LEVEL_MB)
        val bands = _state.value.bands.mapIndexed { i, b -> if (i == band) b.copy(level = clamped) else b }
        saveLevels(bands, EqualizerState.CUSTOM)
        _state.update { it.copy(bands = bands, preset = EqualizerState.CUSTOM) }
        apply()
    }

    fun usePreset(index: Int) {
        val levels = presets.getOrNull(index)?.levels ?: return
        val bands = BAND_CENTERS.mapIndexed { i, hz -> EqualizerBand(hz, levels[i]) }
        saveLevels(bands, index)
        _state.update { it.copy(bands = bands, preset = index) }
        apply()
    }

    fun setBassStrength(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        prefs.edit().putInt(KEY_BASS, clamped).apply()
        _state.update { it.copy(bassStrength = clamped) }
        apply()
    }

    fun setLoudness(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOUDNESS, enabled).apply()
        _state.update { it.copy(loudness = enabled) }
        apply()
    }

    fun release() {
        runCatching { dynamics?.release() }
        dynamics = null
        _state.update { it.copy(available = false) }
    }

    private fun apply() {
        val dp = dynamics ?: return
        val s = _state.value
        runCatching {
            if (!s.enabled && !s.loudness) {
                dp.enabled = false
                return
            }
            val bass = if (s.enabled) s.bassStrength / 1000f else 0f
            val compensation = if (s.loudness) loudnessAmount() else 0f
            val gains = s.bands.mapIndexed { i, band ->
                (if (s.enabled) band.level / 100f else 0f) + BASS_BOOST_DB[i] * bass + LOUDNESS_DB[i] * compensation
            }
            gains.forEachIndexed { i, gain ->
                dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, BAND_EDGES[i], gain))
            }
            val loudestBoost = gains.max().coerceAtLeast(0f)
            dp.setInputGainAllChannelsTo(-loudestBoost * HEADROOM_SHARE)
            // Only limit when something is boosted; otherwise it would just flatten the track's own peaks.
            dp.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(
                    /* inUse = */ true,
                    /* enabled = */ loudestBoost > 0f,
                    /* linkGroup = */ 0,
                    /* attackTime = */ 1f,
                    /* releaseTime = */ 80f,
                    /* ratio = */ 10f,
                    /* threshold = */ -1f,
                    /* postGain = */ 0f,
                ),
            )
            dp.enabled = true
        }
    }

    /** 0 at full volume, 1 at 20% volume and below. */
    private fun loudnessAmount(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        return ((1f - volume) / 0.8f).coerceIn(0f, 1f)
    }

    private fun config(): DynamicsProcessing.Config =
        DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            /* channelCount = */ 2,
            /* preEqInUse = */ true,
            /* preEqBandCount = */ BAND_CENTERS.size,
            /* mbcInUse = */ false,
            /* mbcBandCount = */ 0,
            /* postEqInUse = */ false,
            /* postEqBandCount = */ 0,
            /* limiterInUse = */ true,
        ).build()

    private fun savedState(): EqualizerState {
        val preset = prefs.getInt(KEY_PRESET, 0).takeIf { it in presets.indices || it == EqualizerState.CUSTOM } ?: 0
        val saved = prefs.getString(KEY_LEVELS, null)
            ?.split(',')?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == BAND_CENTERS.size }
        val levels = when {
            preset != EqualizerState.CUSTOM -> presets[preset].levels
            saved != null -> saved
            else -> presets[0].levels
        }
        return EqualizerState(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            bands = BAND_CENTERS.mapIndexed { i, hz -> EqualizerBand(hz, levels[i]) },
            presets = presets.map { it.name },
            preset = preset,
            bassStrength = prefs.getInt(KEY_BASS, 0),
            loudness = prefs.getBoolean(KEY_LOUDNESS, false),
        )
    }

    private fun saveLevels(bands: List<EqualizerBand>, preset: Int) {
        prefs.edit()
            .putInt(KEY_PRESET, preset)
            .putString(KEY_LEVELS, bands.joinToString(",") { it.level.toString() })
            .apply()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_PRESET = "preset"
        const val KEY_LEVELS = "levels"
        const val KEY_BASS = "bass"
        const val KEY_LOUDNESS = "loudness"
    }
}
