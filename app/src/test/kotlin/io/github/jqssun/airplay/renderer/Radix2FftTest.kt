package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class Radix2FftTest {
    @Test
    fun forwardThenInverseRestoresSignal() {
        val size = 4_096
        val fft = Radix2Fft(size)
        val original = FloatArray(size) { index ->
            (
                0.63 * sin(2.0 * PI * 37.0 * index / size) +
                    0.21 * sin(2.0 * PI * 511.0 * index / size)
                ).toFloat()
        }
        val real = original.copyOf()
        val imaginary = FloatArray(size)

        fft.forward(real, imaginary)
        fft.inverse(real, imaginary)

        real.indices.forEach { index ->
            assertEquals(original[index], real[index], 2e-5f)
            assertEquals(0f, imaginary[index], 2e-5f)
        }
    }

    @Test
    fun mdxStftAndIstftIdentityCancelsTheMixture() {
        val inputLeft = FloatArray(MdxVocalChunkProcessor.CHUNK_SIZE) { index ->
            (0.42 * sin(2.0 * PI * 440.0 * index / MdxVocalChunkProcessor.SAMPLE_RATE))
                .toFloat()
        }
        val inputRight = FloatArray(MdxVocalChunkProcessor.CHUNK_SIZE) { index ->
            (0.31 * sin(2.0 * PI * 880.0 * index / MdxVocalChunkProcessor.SAMPLE_RATE))
                .toFloat()
        }
        val identityModel = MdxSpectrogramModel { input -> input.copyOf() }

        val instrumental = MdxVocalChunkProcessor(identityModel).process(
            leftChunk = inputLeft,
            rightChunk = inputRight,
            reduction = 1f,
        )

        val maxResidual = maxOf(
            instrumental.left.maxOf { kotlin.math.abs(it) },
            instrumental.right.maxOf { kotlin.math.abs(it) },
        )
        assertTrue("identity residual was $maxResidual", maxResidual < 2e-4f)
    }

    @Test
    fun overlapWeightsAlwaysNormalizeToOne() {
        val window = buildMdxCrossfadeWindow()
        for (index in 0 until MdxVocalChunkProcessor.OVERLAP_SIZE) {
            val previous = window[MdxVocalChunkProcessor.STRIDE + index]
            val current = window[index]
            assertEquals(1f, previous + current, 1e-6f)
        }
    }
}
