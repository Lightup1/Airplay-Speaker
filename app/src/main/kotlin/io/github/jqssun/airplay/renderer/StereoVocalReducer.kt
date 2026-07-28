package io.github.jqssun.airplay.renderer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Lightweight karaoke-style vocal reduction for stereo PCM.
 *
 * Lead vocals are commonly mixed into the stereo centre, but blindly removing
 * the complete centre also removes kick, bass, snare and solo instruments.
 * This processor splits the centre into musically useful bands and combines
 * centre dominance, stereo-bed energy and a vocal-range detector before
 * applying attenuation. Bass, high-frequency air, stereo ambience and sharp
 * transients are deliberately protected.
 *
 * It is not a neural stem separator: AirPlay supplies a live PCM stream and a
 * full music-demixing model is too expensive for the app's 2 GB TV target.
 * The adaptive path is allocation-free and has no look-ahead latency, so it is
 * safe to run on the realtime audio thread.
 */
internal class StereoVocalReducer {
    private var midBassLowPass = 0f
    private var midBodyLowPass = 0f
    private var midPresenceLowPass = 0f
    private var midVocalLowPass = 0f
    private var sideBassLowPass = 0f
    private var sideBodyLowPass = 0f
    private var sidePresenceLowPass = 0f
    private var sideVocalLowPass = 0f

    private var centreVocalEnvelope = 0f
    private var sideVocalEnvelope = 0f
    private var bassEnvelope = 0f
    private var airEnvelope = 0f
    private var fastVocalEnvelope = 0f
    private var slowVocalEnvelope = 0f
    private var smoothedVocalConfidence = 0f
    private var smoothedReduction = 0f

    private var configuredSampleRate = 0
    private var bassCoefficient = 0f
    private var bodyCoefficient = 0f
    private var presenceCoefficient = 0f
    private var vocalCoefficient = 0f
    private var envelopeCoefficient = 0f
    private var fastEnvelopeCoefficient = 0f
    private var slowEnvelopeCoefficient = 0f
    private var confidenceCoefficient = 0f
    private var reductionAttackCoefficient = 0f
    private var reductionReleaseCoefficient = 0f

    fun processInPlace(
        pcm: ByteArray,
        channels: Int,
        sampleRate: Int,
        reduction: Float,
    ) {
        if (channels != 2 || sampleRate <= 0 || pcm.size < STEREO_FRAME_BYTES) return

        val targetReduction = reduction.coerceIn(0f, 1f) * MAX_CENTRE_REDUCTION
        if (targetReduction == 0f && smoothedReduction < SILENCE_EPSILON) {
            smoothedReduction = 0f
            return
        }

        configureForSampleRate(sampleRate)
        val end = pcm.size - (pcm.size % STEREO_FRAME_BYTES)
        var offset = 0
        while (offset < end) {
            val left = readLittleEndianShort(pcm, offset)
            val right = readLittleEndianShort(pcm, offset + Short.SIZE_BYTES)
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f

            midBassLowPass += bassCoefficient * (mid - midBassLowPass)
            midBodyLowPass += bodyCoefficient * (mid - midBodyLowPass)
            midPresenceLowPass += presenceCoefficient * (mid - midPresenceLowPass)
            midVocalLowPass += vocalCoefficient * (mid - midVocalLowPass)
            sideBassLowPass += bassCoefficient * (side - sideBassLowPass)
            sideBodyLowPass += bodyCoefficient * (side - sideBodyLowPass)
            sidePresenceLowPass += presenceCoefficient * (side - sidePresenceLowPass)
            sideVocalLowPass += vocalCoefficient * (side - sideVocalLowPass)

            val bass = midBassLowPass
            val body = midBodyLowPass - midBassLowPass
            val presence = midPresenceLowPass - midBodyLowPass
            val brilliance = midVocalLowPass - midPresenceLowPass
            val air = mid - midVocalLowPass
            val sideBody = sideBodyLowPass - sideBassLowPass
            val sidePresence = sidePresenceLowPass - sideBodyLowPass
            val sideBrilliance = sideVocalLowPass - sidePresenceLowPass

            // The 150 Hz–7.2 kHz probe favours sung fundamentals and
            // intelligibility, while contributing less sibilance than the
            // actual processing bands. Side energy represents accompaniment
            // that can remain after the centre vocal is attenuated.
            val centreProbe =
                abs(body) * 0.72f + abs(presence) + abs(brilliance) * 0.56f
            val sideProbe =
                abs(sideBody) * 0.72f + abs(sidePresence) +
                    abs(sideBrilliance) * 0.56f
            centreVocalEnvelope +=
                envelopeCoefficient * (centreProbe - centreVocalEnvelope)
            sideVocalEnvelope +=
                envelopeCoefficient * (sideProbe - sideVocalEnvelope)
            bassEnvelope += envelopeCoefficient * (abs(bass) - bassEnvelope)
            airEnvelope += envelopeCoefficient * (abs(air) - airEnvelope)
            fastVocalEnvelope +=
                fastEnvelopeCoefficient * (centreProbe - fastVocalEnvelope)
            slowVocalEnvelope +=
                slowEnvelopeCoefficient * (centreProbe - slowVocalEnvelope)

            val reductionCoefficient = if (targetReduction > smoothedReduction) {
                reductionAttackCoefficient
            } else {
                reductionReleaseCoefficient
            }
            smoothedReduction +=
                reductionCoefficient * (targetReduction - smoothedReduction)

            // A real vocal is usually both centre-dominant and concentrated in
            // the vocal bands. This gate does not promise perfect isolation,
            // but it prevents the old blanket centre cancellation from
            // needlessly thinning instrumental and very bright passages.
            val vocalSpectralShare = centreVocalEnvelope / (
                centreVocalEnvelope +
                    bassEnvelope * BASS_DETECTOR_WEIGHT +
                    airEnvelope * AIR_DETECTOR_WEIGHT +
                    1f
            )
            val spectralConfidence = smoothStep(
                (vocalSpectralShare - MIN_VOCAL_SPECTRAL_SHARE) /
                    VOCAL_SPECTRAL_SHARE_RANGE
            )
            val centreShare = centreVocalEnvelope / (
                centreVocalEnvelope + sideVocalEnvelope + 1f
            )
            val centreConfidence = smoothStep(
                (centreShare - MIN_CENTRE_SHARE) / CENTRE_SHARE_RANGE
            )

            // Fast envelope spikes are more likely to be drums or a plucked
            // instrument. Keep their attack, then smoothly return to the vocal
            // reduction amount instead of creating a click or pumping sound.
            val transientRise = (
                (fastVocalEnvelope - slowVocalEnvelope) /
                    (slowVocalEnvelope + 1f)
            ).coerceAtLeast(0f)
            val transientProtection = smoothStep(
                (transientRise - TRANSIENT_START) / TRANSIENT_RANGE
            ) * MAX_TRANSIENT_PROTECTION
            val requestedConfidence = (
                MIN_VOCAL_CONFIDENCE +
                    (1f - MIN_VOCAL_CONFIDENCE) *
                    spectralConfidence *
                    (MIN_CENTRE_CONFIDENCE +
                        (1f - MIN_CENTRE_CONFIDENCE) * centreConfidence)
                ) * (1f - transientProtection)
            smoothedVocalConfidence += confidenceCoefficient * (
                requestedConfidence - smoothedVocalConfidence
            )

            // A truly mono source has no stereo accompaniment to recover.
            // Back off automatically instead of making the whole mix hollow.
            // The curve reaches full strength with a modest stereo bed so a
            // centred singer can still be reduced strongly.
            val stereoRatio = sideVocalEnvelope / (centreVocalEnvelope + 1f)
            val stereoSupport = smoothStep(
                (stereoRatio - MIN_STEREO_RATIO) / STEREO_RATIO_RANGE
            )
            val channelConfidence = MONO_REDUCTION_FLOOR +
                (1f - MONO_REDUCTION_FLOOR) * stereoSupport
            val adaptiveReduction = (
                smoothedReduction *
                    smoothedVocalConfidence *
                    channelConfidence
                ).coerceIn(0f, MAX_CENTRE_REDUCTION)

            // Preserve mono bass completely and only touch a small amount of
            // upper air. The body/presence bands receive the strongest vocal
            // reduction; stereo side information is never altered.
            val reducedMid = bass +
                body * (1f - adaptiveReduction * BODY_REDUCTION_WEIGHT) +
                presence * (1f - adaptiveReduction) +
                brilliance * (1f - adaptiveReduction * BRILLIANCE_REDUCTION_WEIGHT) +
                air * (1f - adaptiveReduction * AIR_REDUCTION_WEIGHT)
            writeLittleEndianShort(pcm, offset, (reducedMid + side).roundToInt())
            writeLittleEndianShort(
                pcm,
                offset + Short.SIZE_BYTES,
                (reducedMid - side).roundToInt(),
            )
            offset += STEREO_FRAME_BYTES
        }
    }

    fun reset() {
        midBassLowPass = 0f
        midBodyLowPass = 0f
        midPresenceLowPass = 0f
        midVocalLowPass = 0f
        sideBassLowPass = 0f
        sideBodyLowPass = 0f
        sidePresenceLowPass = 0f
        sideVocalLowPass = 0f
        centreVocalEnvelope = 0f
        sideVocalEnvelope = 0f
        bassEnvelope = 0f
        airEnvelope = 0f
        fastVocalEnvelope = 0f
        slowVocalEnvelope = 0f
        smoothedVocalConfidence = 0f
        smoothedReduction = 0f
    }

    private fun configureForSampleRate(sampleRate: Int) {
        if (sampleRate == configuredSampleRate) return
        configuredSampleRate = sampleRate
        bassCoefficient = lowPassCoefficient(BASS_PRESERVE_HZ, sampleRate)
        bodyCoefficient = lowPassCoefficient(BODY_HIGH_HZ, sampleRate)
        presenceCoefficient = lowPassCoefficient(PRESENCE_HIGH_HZ, sampleRate)
        vocalCoefficient = lowPassCoefficient(VOCAL_HIGH_HZ, sampleRate)
        envelopeCoefficient = timeCoefficient(ENVELOPE_TIME_MS, sampleRate)
        fastEnvelopeCoefficient = timeCoefficient(FAST_ENVELOPE_TIME_MS, sampleRate)
        slowEnvelopeCoefficient = timeCoefficient(SLOW_ENVELOPE_TIME_MS, sampleRate)
        confidenceCoefficient = timeCoefficient(CONFIDENCE_TIME_MS, sampleRate)
        reductionAttackCoefficient = timeCoefficient(REDUCTION_ATTACK_MS, sampleRate)
        reductionReleaseCoefficient = timeCoefficient(REDUCTION_RELEASE_MS, sampleRate)
    }

    private fun lowPassCoefficient(frequencyHz: Double, sampleRate: Int): Float =
        (1.0 - exp(-2.0 * PI * frequencyHz / sampleRate)).toFloat()

    private fun timeCoefficient(milliseconds: Double, sampleRate: Int): Float =
        (1.0 - exp(-1.0 / (sampleRate * milliseconds / 1_000.0))).toFloat()

    private fun smoothStep(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun readLittleEndianShort(data: ByteArray, offset: Int): Int =
        (((data[offset + 1].toInt() and 0xff) shl 8) or
            (data[offset].toInt() and 0xff)).toShort().toInt()

    private fun writeLittleEndianShort(data: ByteArray, offset: Int, value: Int) {
        val sample = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        data[offset] = (sample and 0xff).toByte()
        data[offset + 1] = ((sample ushr 8) and 0xff).toByte()
    }

    private companion object {
        const val STEREO_FRAME_BYTES = 2 * Short.SIZE_BYTES
        const val BASS_PRESERVE_HZ = 145.0
        const val BODY_HIGH_HZ = 900.0
        const val PRESENCE_HIGH_HZ = 3_800.0
        const val VOCAL_HIGH_HZ = 7_200.0
        const val MAX_CENTRE_REDUCTION = 0.96f
        const val BODY_REDUCTION_WEIGHT = 0.82f
        const val BRILLIANCE_REDUCTION_WEIGHT = 0.72f
        const val AIR_REDUCTION_WEIGHT = 0.14f

        const val BASS_DETECTOR_WEIGHT = 0.72f
        const val AIR_DETECTOR_WEIGHT = 0.88f
        const val MIN_VOCAL_SPECTRAL_SHARE = 0.34f
        const val VOCAL_SPECTRAL_SHARE_RANGE = 0.42f
        const val MIN_CENTRE_SHARE = 0.48f
        const val CENTRE_SHARE_RANGE = 0.40f
        const val MIN_VOCAL_CONFIDENCE = 0.34f
        const val MIN_CENTRE_CONFIDENCE = 0.58f

        const val MIN_STEREO_RATIO = 0.025f
        const val STEREO_RATIO_RANGE = 0.20f
        const val MONO_REDUCTION_FLOOR = 0.46f
        const val TRANSIENT_START = 0.44f
        const val TRANSIENT_RANGE = 1.10f
        const val MAX_TRANSIENT_PROTECTION = 0.52f

        const val ENVELOPE_TIME_MS = 18.0
        const val FAST_ENVELOPE_TIME_MS = 4.0
        const val SLOW_ENVELOPE_TIME_MS = 95.0
        const val CONFIDENCE_TIME_MS = 34.0
        const val REDUCTION_ATTACK_MS = 28.0
        const val REDUCTION_RELEASE_MS = 12.0
        const val SILENCE_EPSILON = 0.0001f
    }
}
