package io.github.jqssun.airplay.renderer

import kotlin.math.PI
import kotlin.math.cos

internal fun interface MdxSpectrogramModel {
    fun run(input: FloatArray): FloatArray
}

internal data class StereoFloatBlock(
    val left: FloatArray,
    val right: FloatArray,
)

/**
 * Host-side STFT/iSTFT for the UVR MDX-Net 9482 LiteRT model.
 *
 * The neural graph receives a [1, 4, 2048, 256] CaC tensor in
 * [L.real, L.imag, R.real, R.imag] order. The graph predicts the vocal
 * spectrogram; karaoke audio is the original mixture minus that prediction.
 */
internal class MdxVocalChunkProcessor(
    private val model: MdxSpectrogramModel,
) {
    private val fft = Radix2Fft(N_FFT)
    private val hann = FloatArray(N_FFT) { index ->
        (0.5 * (1.0 - cos(2.0 * PI * index / N_FFT))).toFloat()
    }
    private val envelope = FloatArray(OLA_SIZE).also { values ->
        repeat(DIM_T) { frame ->
            val start = frame * HOP
            for (i in 0 until N_FFT) {
                values[start + i] += hann[i] * hann[i]
            }
        }
    }
    private val modelInput = FloatArray(MODEL_FLOATS)
    private val fftReal = FloatArray(N_FFT)
    private val fftImaginary = FloatArray(N_FFT)
    private val olaLeft = FloatArray(OLA_SIZE)
    private val olaRight = FloatArray(OLA_SIZE)

    fun process(
        leftChunk: FloatArray,
        rightChunk: FloatArray,
        reduction: Float,
    ): StereoFloatBlock {
        require(leftChunk.size == CHUNK_SIZE && rightChunk.size == CHUNK_SIZE)
        transformChannel(leftChunk, realPlane = 0, imaginaryPlane = 1)
        transformChannel(rightChunk, realPlane = 2, imaginaryPlane = 3)
        val vocalSpectrum = model.run(modelInput)
        require(vocalSpectrum.size == MODEL_FLOATS) {
            "Unexpected MDX output: ${vocalSpectrum.size} floats"
        }

        olaLeft.fill(0f)
        olaRight.fill(0f)
        inverseChannel(vocalSpectrum, realPlane = 0, imaginaryPlane = 1, olaLeft)
        inverseChannel(vocalSpectrum, realPlane = 2, imaginaryPlane = 3, olaRight)

        val amount = reduction.coerceIn(0f, 1f)
        val instrumentalLeft = FloatArray(GENERATED_SIZE)
        val instrumentalRight = FloatArray(GENERATED_SIZE)
        for (i in 0 until GENERATED_SIZE) {
            val chunkIndex = TRIM + i
            val envelopeIndex = TRIM + chunkIndex
            val divisor = envelope[envelopeIndex] + ENVELOPE_EPSILON
            val vocalLeft = olaLeft[envelopeIndex] / divisor
            val vocalRight = olaRight[envelopeIndex] / divisor
            instrumentalLeft[i] = leftChunk[chunkIndex] - vocalLeft * amount
            instrumentalRight[i] = rightChunk[chunkIndex] - vocalRight * amount
        }
        return StereoFloatBlock(instrumentalLeft, instrumentalRight)
    }

    private fun transformChannel(
        samples: FloatArray,
        realPlane: Int,
        imaginaryPlane: Int,
    ) {
        val realBase = realPlane * DIM_F * DIM_T
        val imaginaryBase = imaginaryPlane * DIM_F * DIM_T
        repeat(DIM_T) { frame ->
            val paddedStart = frame * HOP
            for (i in 0 until N_FFT) {
                fftReal[i] = reflectedSample(samples, paddedStart + i) * hann[i]
                fftImaginary[i] = 0f
            }
            fft.forward(fftReal, fftImaginary)
            for (bin in 0 until DIM_F) {
                val packed = bin * DIM_T + frame
                modelInput[realBase + packed] = fftReal[bin]
                modelInput[imaginaryBase + packed] = fftImaginary[bin]
            }
        }
    }

    private fun inverseChannel(
        spectrum: FloatArray,
        realPlane: Int,
        imaginaryPlane: Int,
        overlapAdd: FloatArray,
    ) {
        val realBase = realPlane * DIM_F * DIM_T
        val imaginaryBase = imaginaryPlane * DIM_F * DIM_T
        repeat(DIM_T) { frame ->
            for (bin in 0 until DIM_F) {
                val packed = bin * DIM_T + frame
                fftReal[bin] = spectrum[realBase + packed]
                fftImaginary[bin] = if (bin == 0) {
                    0f
                } else {
                    spectrum[imaginaryBase + packed]
                }
            }
            fftReal[DIM_F] = 0f
            fftImaginary[DIM_F] = 0f
            for (bin in 1 until DIM_F) {
                val mirror = N_FFT - bin
                fftReal[mirror] = fftReal[bin]
                fftImaginary[mirror] = -fftImaginary[bin]
            }
            fft.inverse(fftReal, fftImaginary)
            val start = frame * HOP
            for (i in 0 until N_FFT) {
                overlapAdd[start + i] += fftReal[i] * hann[i]
            }
        }
    }

    private fun reflectedSample(samples: FloatArray, paddedIndex: Int): Float {
        val source = paddedIndex - TRIM
        val reflected = when {
            source < 0 -> -source
            source >= CHUNK_SIZE -> 2 * CHUNK_SIZE - 2 - source
            else -> source
        }
        return samples[reflected.coerceIn(0, CHUNK_SIZE - 1)]
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val N_FFT = 4_096
        const val DIM_F = 2_048
        const val DIM_T = 256
        const val HOP = 1_024
        const val TRIM = N_FFT / 2
        const val CHUNK_SIZE = HOP * (DIM_T - 1)
        const val GENERATED_SIZE = CHUNK_SIZE - 2 * TRIM
        const val STRIDE = 231_322
        const val OVERLAP_SIZE = GENERATED_SIZE - STRIDE
        const val MODEL_FLOATS = 4 * DIM_F * DIM_T
        private const val OLA_SIZE = (DIM_T - 1) * HOP + N_FFT
        private const val ENVELOPE_EPSILON = 1e-8f
    }
}

internal fun buildMdxCrossfadeWindow(): FloatArray {
    val result = FloatArray(MdxVocalChunkProcessor.GENERATED_SIZE) { 1f }
    val overlap = MdxVocalChunkProcessor.OVERLAP_SIZE
    val denominator = (overlap + 1).toFloat()
    for (i in result.indices) {
        val up = (i + 1f) / denominator
        val down = (result.size - i).toFloat() / denominator
        result[i] = minOf(1f, up, down)
    }
    return result
}
