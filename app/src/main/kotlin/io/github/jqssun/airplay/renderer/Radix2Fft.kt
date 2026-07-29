package io.github.jqssun.airplay.renderer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Small allocation-free radix-2 FFT used by the on-device MDX pipeline.
 *
 * Keeping this local avoids pulling a second native DSP runtime into the APK.
 * A 4096-point plan is reused for every STFT frame.
 */
internal class Radix2Fft(private val size: Int) {
    private val reversed = IntArray(size)
    private val cosine = FloatArray(size / 2)
    private val sine = FloatArray(size / 2)

    init {
        require(size > 1 && size and (size - 1) == 0) {
            "FFT size must be a power of two"
        }
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) {
            reversed[i] = Integer.reverse(i) ushr (Int.SIZE_BITS - bits)
        }
        for (i in cosine.indices) {
            val angle = 2.0 * PI * i / size
            cosine[i] = cos(angle).toFloat()
            sine[i] = sin(angle).toFloat()
        }
    }

    fun forward(real: FloatArray, imaginary: FloatArray) =
        transform(real, imaginary, inverse = false)

    fun inverse(real: FloatArray, imaginary: FloatArray) =
        transform(real, imaginary, inverse = true)

    private fun transform(
        real: FloatArray,
        imaginary: FloatArray,
        inverse: Boolean,
    ) {
        require(real.size == size && imaginary.size == size)

        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                var tmp = real[i]
                real[i] = real[j]
                real[j] = tmp
                tmp = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = tmp
            }
        }

        var length = 2
        while (length <= size) {
            val half = length / 2
            val twiddleStep = size / length
            var block = 0
            while (block < size) {
                for (j in 0 until half) {
                    val twiddle = j * twiddleStep
                    val wr = cosine[twiddle]
                    val wi = if (inverse) sine[twiddle] else -sine[twiddle]
                    val odd = block + j + half
                    val even = block + j
                    val tr = wr * real[odd] - wi * imaginary[odd]
                    val ti = wr * imaginary[odd] + wi * real[odd]
                    real[odd] = real[even] - tr
                    imaginary[odd] = imaginary[even] - ti
                    real[even] += tr
                    imaginary[even] += ti
                }
                block += length
            }
            length = length shl 1
        }

        if (inverse) {
            val scale = 1f / size
            for (i in 0 until size) {
                real[i] *= scale
                imaginary[i] *= scale
            }
        }
    }
}
