package io.github.jqssun.airplay.renderer

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Three lightweight, display-ready PCM bands used by the Now Playing meter.
 *
 * The values are immutable so the decoder thread can publish a complete
 * snapshot while Compose reads the previous or next one without locking.
 */
data class AudioMeterLevels(
    val low: Float = 0f,
    val mid: Float = 0f,
    val high: Float = 0f,
) {
    companion object {
        val Silent = AudioMeterLevels()
    }
}

/**
 * A deliberately small real-time analyser for low-memory Android TVs.
 *
 * It reads only a reduced set of PCM frames and uses two one-pole filters
 * instead of an FFT. The resulting bars still follow real bass, vocal/body,
 * and high-frequency energy while avoiding allocations on the audio thread.
 */
internal class PcmAudioLevelAnalyzer {
    @Volatile
    private var publishedLevels = AudioMeterLevels.Silent

    @Volatile
    private var lastPcmAtMs = Long.MIN_VALUE

    private var configuredSampleRate = 0
    private var configuredChannels = 0
    private var lowPassState = 0f
    private var bodyPassState = 0f
    private var displayedLow = 0f
    private var displayedMid = 0f
    private var displayedHigh = 0f

    fun analyzePcm16(
        pcm: ByteArray,
        channels: Int,
        sampleRate: Int,
        gain: Float,
        capturedAtMs: Long,
    ) {
        if (channels !in 1..2 || sampleRate <= 0 || pcm.size < channels * Short.SIZE_BYTES) {
            return
        }
        if (gain <= 0f) {
            publishSilence(capturedAtMs)
            return
        }
        if (sampleRate != configuredSampleRate || channels != configuredChannels) {
            resetFilterState(sampleRate, channels)
        }

        val frameBytes = channels * Short.SIZE_BYTES
        val frameStride = (sampleRate / TARGET_ANALYSIS_RATE_HZ).coerceAtLeast(1)
        val byteStride = frameBytes * frameStride
        val effectiveSampleRate = sampleRate.toDouble() / frameStride
        val lowCoefficient = onePoleCoefficient(LOW_CUTOFF_HZ, effectiveSampleRate)
        val bodyCoefficient = onePoleCoefficient(BODY_CUTOFF_HZ, effectiveSampleRate)
        val outputGain = gain.coerceIn(0f, 1f)

        var lowEnergy = 0.0
        var midEnergy = 0.0
        var highEnergy = 0.0
        var samples = 0
        var offset = 0
        val end = pcm.size - frameBytes
        while (offset <= end) {
            var mixed = readLittleEndianShort(pcm, offset).toFloat()
            if (channels == 2) {
                mixed = (mixed + readLittleEndianShort(pcm, offset + Short.SIZE_BYTES)) * 0.5f
            }
            val normalized = mixed / Short.MAX_VALUE * outputGain

            lowPassState += lowCoefficient * (normalized - lowPassState)
            bodyPassState += bodyCoefficient * (normalized - bodyPassState)
            val lowBand = lowPassState
            val midBand = bodyPassState - lowPassState
            val highBand = normalized - bodyPassState

            lowEnergy += lowBand * lowBand
            midEnergy += midBand * midBand
            highEnergy += highBand * highBand
            samples++
            offset += byteStride
        }
        if (samples == 0) return

        val lowTarget = meterLevel(sqrt(lowEnergy / samples) * LOW_BAND_GAIN)
        val midTarget = meterLevel(sqrt(midEnergy / samples) * MID_BAND_GAIN)
        val highTarget = meterLevel(sqrt(highEnergy / samples) * HIGH_BAND_GAIN)
        displayedLow = smoothBand(displayedLow, lowTarget)
        displayedMid = smoothBand(displayedMid, midTarget)
        displayedHigh = smoothBand(displayedHigh, highTarget)
        publishedLevels = AudioMeterLevels(
            low = displayedLow,
            mid = displayedMid,
            high = displayedHigh,
        )
        lastPcmAtMs = capturedAtMs
    }

    fun snapshot(nowMs: Long): AudioMeterLevels =
        if (
            lastPcmAtMs == Long.MIN_VALUE ||
            nowMs - lastPcmAtMs > STALE_PCM_TIMEOUT_MS
        ) {
            AudioMeterLevels.Silent
        } else {
            publishedLevels
        }

    fun reset() {
        configuredSampleRate = 0
        configuredChannels = 0
        lowPassState = 0f
        bodyPassState = 0f
        displayedLow = 0f
        displayedMid = 0f
        displayedHigh = 0f
        publishedLevels = AudioMeterLevels.Silent
        lastPcmAtMs = Long.MIN_VALUE
    }

    private fun publishSilence(capturedAtMs: Long) {
        displayedLow = 0f
        displayedMid = 0f
        displayedHigh = 0f
        publishedLevels = AudioMeterLevels.Silent
        lastPcmAtMs = capturedAtMs
    }

    private fun resetFilterState(sampleRate: Int, channels: Int) {
        configuredSampleRate = sampleRate
        configuredChannels = channels
        lowPassState = 0f
        bodyPassState = 0f
        displayedLow = 0f
        displayedMid = 0f
        displayedHigh = 0f
    }

    private fun smoothBand(previous: Float, target: Float): Float {
        val coefficient = if (target >= previous) ATTACK else RELEASE
        val smoothed = previous + (target - previous) * coefficient
        return if (smoothed < MIN_VISIBLE_SIGNAL) 0f else smoothed.coerceIn(0f, 1f)
    }

    private fun meterLevel(rms: Double): Float {
        if (rms <= NOISE_FLOOR) return 0f
        val decibels = 20.0 * ln(rms.coerceAtLeast(MIN_RMS)) / LN_10
        return ((decibels - MIN_DISPLAY_DB) / -MIN_DISPLAY_DB)
            .coerceIn(0.0, 1.0)
            .toFloat()
    }

    private fun onePoleCoefficient(cutoffHz: Double, effectiveSampleRate: Double): Float =
        (1.0 - exp(-2.0 * PI * cutoffHz / effectiveSampleRate)).toFloat()

    private fun readLittleEndianShort(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or (data[offset + 1].toInt() shl 8)

    private companion object {
        const val TARGET_ANALYSIS_RATE_HZ = 14_700
        const val LOW_CUTOFF_HZ = 220.0
        const val BODY_CUTOFF_HZ = 2_400.0
        const val LOW_BAND_GAIN = 1.18
        const val MID_BAND_GAIN = 1.62
        const val HIGH_BAND_GAIN = 2.18
        const val ATTACK = 0.68f
        const val RELEASE = 0.22f
        const val MIN_VISIBLE_SIGNAL = 0.006f
        const val NOISE_FLOOR = 0.00035
        const val MIN_RMS = 0.000001
        const val MIN_DISPLAY_DB = -52.0
        const val LN_10 = 2.302585092994046
        const val STALE_PCM_TIMEOUT_MS = 320L
    }
}
