package io.github.jqssun.airplay.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

internal data class ArtworkPalette(
    val primary: Color,
    val secondary: Color,
    val dark: Color,
    val tertiary: Color,
    val highlight: Color,
) {
    companion object {
        val Default = ArtworkPalette(
            // A no-artwork track should look deliberately quiet, rather than
            // borrowing colourful tones from the previous album.
            primary = Color(0xFF6B6D73),
            secondary = Color(0xFF3D3F45),
            dark = Color(0xFF16171B),
            tertiary = Color(0xFF50535A),
            highlight = Color(0xFF8B8E95),
        )
    }
}

/** Extracts a stable five-tone ambient palette without adding a palette dependency. */
internal fun extractArtworkPalette(bitmap: Bitmap?): ArtworkPalette {
    if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
        return ArtworkPalette.Default
    }

    return runCatching {
        val sample = Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true)
        val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        sample.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        if (sample !== bitmap) sample.recycle()

        val buckets = Array(HUE_BUCKETS) { ColorBucket() }
        val neutral = ColorBucket()
        val highlightBucket = ColorBucket()
        val shadowBucket = ColorBucket()
        val hsv = FloatArray(3)

        pixels.forEach { pixel ->
            if (AndroidColor.alpha(pixel) < 180) return@forEach
            val red = AndroidColor.red(pixel)
            val green = AndroidColor.green(pixel)
            val blue = AndroidColor.blue(pixel)
            AndroidColor.RGBToHSV(red, green, blue, hsv)
            val saturation = hsv[1]
            val value = hsv[2]
            if (value < 0.08f || value > 0.97f && saturation < 0.08f) return@forEach

            val weight = (0.3f + saturation * 1.6f + value * 0.35f).toDouble()
            neutral.add(red, green, blue, weight)
            if (value >= 0.48f) {
                highlightBucket.add(
                    red,
                    green,
                    blue,
                    weight * (0.45 + value * 0.8),
                )
            }
            if (value in 0.08f..0.52f) {
                shadowBucket.add(
                    red,
                    green,
                    blue,
                    weight * (0.4 + (0.52f - value)),
                )
            }
            if (saturation >= 0.18f) {
                val bucket = ((hsv[0] / 360f) * HUE_BUCKETS).toInt()
                    .coerceIn(0, HUE_BUCKETS - 1)
                buckets[bucket].add(red, green, blue, weight)
            }
        }

        val primaryIndex = buckets.indices.maxByOrNull { buckets[it].weight }
            ?.takeIf { buckets[it].weight > 0.0 }
        val primary = primaryIndex?.let { buckets[it].toColor() }
            ?: neutral.toColorOrNull()
            ?: ArtworkPalette.Default.primary

        val secondaryIndex = primaryIndex?.let { selected ->
            buckets.indices
                .filter { it != selected && circularDistance(it, selected) >= 2 }
                .maxByOrNull { buckets[it].weight }
                ?.takeIf { buckets[it].weight > 0.0 }
        }
        val secondary = secondaryIndex?.let { buckets[it].toColor() }
            ?: rotateFallback(primary)
        val tertiaryIndex = buckets.indices
            .filter { index ->
                index != primaryIndex &&
                    index != secondaryIndex &&
                    (primaryIndex == null || circularDistance(index, primaryIndex) >= 1) &&
                    (secondaryIndex == null || circularDistance(index, secondaryIndex) >= 1)
            }
            .maxByOrNull { buckets[it].weight }
            ?.takeIf { buckets[it].weight > 0.0 }
        val tertiary = tertiaryIndex?.let { buckets[it].toColor() }
            ?: mix(primary, secondary, 0.52f)
        val highlight = highlightBucket.toColorOrNull()
            ?: neutral.toColorOrNull()
            ?: mix(primary, Color.White, 0.2f)
        val sampledShadow = shadowBucket.toColorOrNull()

        ArtworkPalette(
            primary = liftForAccent(primary),
            secondary = liftForAccent(secondary),
            dark = sampledShadow
                ?.let { mix(it, Color.Black, 0.62f) }
                ?: mix(primary, Color.Black, 0.84f),
            tertiary = liftForAccent(tertiary),
            highlight = liftHighlight(highlight),
        )
    }.getOrDefault(ArtworkPalette.Default)
}

private class ColorBucket {
    var red = 0.0
    var green = 0.0
    var blue = 0.0
    var weight = 0.0

    fun add(r: Int, g: Int, b: Int, sampleWeight: Double) {
        red += r * sampleWeight
        green += g * sampleWeight
        blue += b * sampleWeight
        weight += sampleWeight
    }

    fun toColor(): Color = Color(
        red = (red / weight / 255.0).toFloat().coerceIn(0f, 1f),
        green = (green / weight / 255.0).toFloat().coerceIn(0f, 1f),
        blue = (blue / weight / 255.0).toFloat().coerceIn(0f, 1f),
    )

    fun toColorOrNull(): Color? = if (weight > 0.0) toColor() else null
}

private fun circularDistance(first: Int, second: Int): Int {
    val direct = abs(first - second)
    return minOf(direct, HUE_BUCKETS - direct)
}

private fun liftForAccent(color: Color): Color {
    val max = maxOf(color.red, color.green, color.blue)
    if (max >= 0.52f) return color
    val lift = 0.52f / max.coerceAtLeast(0.1f)
    return Color(
        red = (color.red * lift).coerceAtMost(1f),
        green = (color.green * lift).coerceAtMost(1f),
        blue = (color.blue * lift).coerceAtMost(1f),
    )
}

private fun liftHighlight(color: Color): Color {
    val max = maxOf(color.red, color.green, color.blue)
    val lift = if (max < 0.62f) 0.62f / max.coerceAtLeast(0.1f) else 1f
    return Color(
        red = (color.red * lift).coerceAtMost(1f),
        green = (color.green * lift).coerceAtMost(1f),
        blue = (color.blue * lift).coerceAtMost(1f),
    )
}

private fun rotateFallback(color: Color): Color = Color(
    red = (color.blue * 0.82f + color.red * 0.18f).coerceIn(0f, 1f),
    green = (color.red * 0.58f + color.green * 0.42f).coerceIn(0f, 1f),
    blue = (color.green * 0.72f + color.blue * 0.28f).coerceIn(0f, 1f),
)

internal fun mix(first: Color, second: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = first.red + (second.red - first.red) * t,
        green = first.green + (second.green - first.green) * t,
        blue = first.blue + (second.blue - first.blue) * t,
        alpha = first.alpha + (second.alpha - first.alpha) * t,
    )
}

private const val SAMPLE_SIZE = 36
private const val HUE_BUCKETS = 18
