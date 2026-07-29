package io.github.jqssun.airplay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

private val GlassInk = Color(0xFF071019)
private val GlassBlue = Color(0xFF78C9FF)
private val GlassViolet = Color(0xFFBDA8FF)
private val GlassMint = Color(0xFF8DF4D2)

/**
 * Lightweight TV-safe interpretation of translucent glass. It deliberately
 * avoids a live backdrop blur, which is expensive on low-memory Android TVs,
 * and builds the depth from transparent colour, a specular edge and shadow.
 */
internal fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(24.dp),
    focused: Boolean = false,
    selected: Boolean = false,
    tint: Color = GlassBlue,
    strength: Float = 1f,
): Modifier {
    val safeStrength = strength.coerceIn(0.45f, 1.4f)
    val lift = focused || selected
    val fill = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = (if (lift) 0.20f else 0.115f) * safeStrength),
            tint.copy(alpha = (if (lift) 0.15f else 0.07f) * safeStrength),
            GlassInk.copy(alpha = (if (lift) 0.44f else 0.34f) * safeStrength),
        ),
    )
    val edge = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = if (focused) 0.82f else if (selected) 0.52f else 0.28f),
            tint.copy(alpha = if (focused) 0.62f else if (selected) 0.35f else 0.15f),
            Color.White.copy(alpha = if (focused) 0.27f else 0.08f),
        ),
    )

    return this
        .shadow(
            elevation = if (focused) 18.dp else if (selected) 10.dp else 5.dp,
            shape = shape,
            clip = false,
            ambientColor = tint.copy(alpha = if (focused) 0.44f else 0.18f),
            spotColor = Color.Black.copy(alpha = 0.72f),
        )
        .clip(shape)
        .background(fill)
        .drawBehind {
            val longestEdge = maxOf(size.width, size.height)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (lift) 0.15f else 0.085f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.12f, 0f),
                    radius = longestEdge * 0.82f,
                ),
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (lift) 0.075f else 0.035f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.08f),
                    ),
                ),
            )
        }
        .border(
            width = if (focused) 2.dp else 1.dp,
            brush = edge,
            shape = shape,
        )
}

/**
 * Soft colour field placed behind the translucent surfaces on non-player
 * screens. It uses a single Canvas and no blur layer or per-frame animation.
 */
@Composable
internal fun LiquidGlassAmbientBackdrop(
    modifier: Modifier = Modifier,
    accent: Color = GlassBlue,
) {
    Canvas(modifier = modifier) {
        val longestEdge = maxOf(size.width, size.height)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF07111A),
                    Color(0xFF0B111C),
                    Color(0xFF06080E),
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.34f), Color.Transparent),
            ),
            radius = longestEdge * 0.50f,
            center = Offset(size.width * 0.13f, size.height * 0.18f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(GlassViolet.copy(alpha = 0.22f), Color.Transparent),
            ),
            radius = longestEdge * 0.43f,
            center = Offset(size.width * 0.83f, size.height * 0.12f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(GlassMint.copy(alpha = 0.15f), Color.Transparent),
            ),
            radius = longestEdge * 0.48f,
            center = Offset(size.width * 0.64f, size.height * 0.96f),
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.08f),
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.38f),
                ),
            ),
        )
    }
}
