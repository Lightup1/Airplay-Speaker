@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.jqssun.airplay.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.RadialGradient as AndroidRadialGradient
import android.graphics.Shader as AndroidShader
import android.view.TextureView
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.lyrics.LyricAlignment
import io.github.jqssun.airplay.lyrics.LyricLine
import io.github.jqssun.airplay.lyrics.LyricsState
import io.github.jqssun.airplay.lyrics.LyricsStatus
import io.github.jqssun.airplay.lyrics.lyricWordProgress
import io.github.jqssun.airplay.lyrics.lyricWordsMatchLine
import io.github.jqssun.airplay.lyrics.progressiveTextSlice
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Dedicated ten-foot Now Playing surface, proportioned around a 1920 x 1080 canvas. */
@Composable
internal fun TvNowPlayingScreen(
    viewModel: MainViewModel,
    onSettings: () -> Unit,
) {
    val track by viewModel.trackInfo.collectAsState()
    val lyrics by viewModel.lyrics.collectAsState()
    val playing by viewModel.audioPlaying.collectAsState()
    val motionArtworkEnabled by viewModel.motionArtworkEnabled.collectAsState()
    val animatedArtworkUrl by viewModel.animatedArtworkUrl.collectAsState()
    val animatedArtworkCover by viewModel.animatedArtworkCover.collectAsState()
    val displayArtwork = if (motionArtworkEnabled) animatedArtworkCover ?: track.coverArt else track.coverArt
    val extracted = remember(displayArtwork) { extractArtworkPalette(displayArtwork) }
    val primary by animateColorAsState(extracted.primary, tween(1_400), label = "artPrimary")
    val secondary by animateColorAsState(extracted.secondary, tween(1_400), label = "artSecondary")
    val dark by animateColorAsState(extracted.dark, tween(1_400), label = "artDark")
    val accent = mix(primary, Color.White, 0.42f)
    val paper = Color(0xFFF9F8FB)
    val muted = paper.copy(alpha = 0.56f)
    val rootFocus = remember { FocusRequester() }
    var lyricBrowseSignal by remember { mutableIntStateOf(0) }
    var lyricBrowseDirection by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        runCatching { rootFocus.requestFocus() }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_MENU -> {
                        onSettings()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        lyricBrowseDirection = -1
                        lyricBrowseSignal++
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        lyricBrowseDirection = 1
                        lyricBrowseSignal++
                        true
                    }
                    else -> false
                }
            },
    ) {
        val baseScale = minOf(maxWidth.value / 960f, maxHeight.value / 540f)
        val scale = (baseScale * 0.88f).coerceIn(0.74f, 1.76f)

        BlendedArtworkBackdrop(
            primary = extracted.primary,
            secondary = extracted.secondary,
            dark = extracted.dark,
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = (54f * scale).dp,
                    end = (54f * scale).dp,
                    top = (30f * scale).dp,
                    bottom = (30f * scale).dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MinimalArtworkColumn(
                artwork = displayArtwork,
                animatedArtworkUrl = animatedArtworkUrl.takeIf { motionArtworkEnabled },
                title = track.title.ifBlank { stringResource(R.string.unknown_track) },
                artist = track.artist.ifBlank { "AirPlay Audio" },
                album = track.album,
                primary = primary,
                secondary = secondary,
                paper = paper,
                muted = muted,
                scale = scale,
                playing = playing,
                artworkMotionEnabled = motionArtworkEnabled,
            )

            Spacer(Modifier.width((68f * scale).dp))

            DisplaySyncedLyricsStage(
                lyrics = lyrics,
                viewModel = viewModel,
                accent = accent,
                paper = paper,
                muted = muted,
                scale = scale,
                durationMs = track.durationMs,
                writerCredit = track.composer,
                playing = playing,
                browseSignal = lyricBrowseSignal,
                browseDirection = lyricBrowseDirection,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun BlendedArtworkBackdrop(
    primary: Color,
    secondary: Color,
    dark: Color,
) {
    val ambientBitmap = remember(primary, secondary, dark) {
        createAmbientBackdrop(primary, secondary, dark)
    }
    val transition = rememberInfiniteTransition(label = "ambientBackdrop")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(24_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ambientPhase",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF08090D)),
    ) {
        Image(
            bitmap = ambientBitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val angle = phase.value * (2f * PI.toFloat())
                    scaleX = 1.12f
                    scaleY = 1.12f
                    translationX = 18f * cos(angle)
                    translationY = 13f * sin(angle * 0.82f)
                },
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.06f),
                        0.58f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.48f),
                    )
                )
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.16f),
                        0.52f to Color.Black.copy(alpha = 0.01f),
                        1f to Color.Black.copy(alpha = 0.18f),
                    )
                )
        )
    }
}

private fun createAmbientBackdrop(primary: Color, secondary: Color, dark: Color): Bitmap {
    val width = 384
    val height = 216
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(mix(dark, Color.Black, 0.34f).toArgb())
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)

    fun blob(color: Color, x: Float, y: Float, radius: Float, strength: Float) {
        paint.shader = AndroidRadialGradient(
            x,
            y,
            radius,
            intArrayOf(
                mix(color, Color.White, 0.08f).copy(alpha = strength).toArgb(),
                color.copy(alpha = strength * 0.46f).toArgb(),
                Color.Transparent.toArgb(),
            ),
            floatArrayOf(0f, 0.48f, 1f),
            AndroidShader.TileMode.CLAMP,
        )
        canvas.drawCircle(x, y, radius, paint)
    }

    blob(primary, width * 0.18f, height * 0.32f, width * 0.58f, 0.72f)
    blob(secondary, width * 0.86f, height * 0.28f, width * 0.62f, 0.64f)
    blob(mix(primary, secondary, 0.5f), width * 0.54f, height * 0.94f, width * 0.52f, 0.52f)
    paint.shader = null
    return bitmap
}

@Composable
private fun MinimalArtworkColumn(
    artwork: Bitmap?,
    animatedArtworkUrl: String?,
    title: String,
    artist: String,
    album: String,
    primary: Color,
    secondary: Color,
    paper: Color,
    muted: Color,
    scale: Float,
    playing: Boolean,
    artworkMotionEnabled: Boolean,
) {
    val artworkSize = (282f * scale).dp
    val motion = rememberInfiniteTransition(label = "artworkMotion")
    val floatY by motion.animateFloat(
        initialValue = -3.5f,
        targetValue = 3.5f,
        animationSpec = infiniteRepeatable(
            tween(3_800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "artworkFloat",
    )
    val tilt by motion.animateFloat(
        initialValue = -0.55f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            tween(5_600, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "artworkTilt",
    )
    val breathe by motion.animateFloat(
        initialValue = 1f,
        targetValue = 1.018f,
        animationSpec = infiniteRepeatable(
            tween(4_600, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "artworkBreathe",
    )
    val glow by motion.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.32f,
        animationSpec = infiniteRepeatable(
            tween(3_400, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "artworkGlow",
    )
    val hasMotionVideo = !animatedArtworkUrl.isNullOrBlank()
    val animateStaticArtwork = artworkMotionEnabled && !hasMotionVideo
    Column(
        modifier = Modifier.width((304f * scale).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(artworkSize),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = if (artworkMotionEnabled) 1.07f + (breathe - 1f) else 1.07f
                        scaleY = if (artworkMotionEnabled) 1.07f + (breathe - 1f) else 1.07f
                        alpha = if (artworkMotionEnabled) glow else 0.24f
                    }
                    .blur((25f * scale).dp)
                    .background(
                        Brush.radialGradient(
                            listOf(primary, secondary.copy(alpha = 0.55f), Color.Transparent)
                        ),
                        RoundedCornerShape((25f * scale).dp),
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = ((if (animateStaticArtwork) floatY else 0f) * scale).dp.toPx()
                        rotationZ = if (animateStaticArtwork) tilt else 0f
                        rotationY = if (animateStaticArtwork) tilt * 0.7f else 0f
                        scaleX = if (animateStaticArtwork) breathe else 1f
                        scaleY = if (animateStaticArtwork) breathe else 1f
                        cameraDistance = 18f * density
                    }
                    .shadow((28f * scale).dp, RoundedCornerShape((18f * scale).dp))
                    .clip(RoundedCornerShape((18f * scale).dp))
                    .background(Brush.linearGradient(listOf(primary, secondary)))
                    .border(
                        (0.7f * scale).dp,
                        Color.White.copy(alpha = 0.18f),
                        RoundedCornerShape((18f * scale).dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                MotionAlbumArtwork(
                    artwork = artwork,
                    motionUrl = animatedArtworkUrl,
                    paper = paper,
                    scale = scale,
                    playing = playing,
                )
            }
        }

        Spacer(Modifier.height((17f * scale).dp))
        TitleWithEqualizer(
            title = title,
            color = paper,
            equalizerColor = mix(primary, Color.White, 0.58f),
            scale = scale,
            playing = playing,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height((3f * scale).dp))
        BouncingFadingText(
            text = artist,
            color = paper.copy(alpha = 0.72f),
            style = TextStyle(
                fontSize = (14f * scale).sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (album.isNotBlank()) {
            Spacer(Modifier.height((2f * scale).dp))
            BouncingFadingText(
                text = album,
                color = muted.copy(alpha = 0.72f),
                style = TextStyle(
                    fontSize = (11f * scale).sp,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TitleWithEqualizer(
    title: String,
    color: Color,
    equalizerColor: Color,
    scale: Float,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val titleStyle = TextStyle(
        fontSize = (20f * scale).sp,
        lineHeight = (24f * scale).sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    var availableWidthPx by remember(title) { mutableIntStateOf(0) }
    var titleWidthPx by remember(title) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val equalizerAndGapPx = with(density) { (19f * scale).dp.roundToPx() }
    val titleOverflows = titleWidthPx + equalizerAndGapPx > availableWidthPx

    Box(
        modifier = modifier.onSizeChanged { availableWidthPx = it.width },
        contentAlignment = Alignment.Center,
    ) {
        // Measure without a width constraint. Short names can be centred with
        // their indicator; long ones retain a fixed indicator and marquee.
        Text(
            text = title,
            color = Color.Transparent,
            style = titleStyle,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            modifier = Modifier
                .wrapContentWidth(unbounded = true)
                .onSizeChanged { titleWidthPx = it.width },
        )

        if (titleOverflows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniNowPlayingEqualizer(color = equalizerColor, scale = scale, playing = playing)
                Spacer(Modifier.width((4f * scale).dp))
                BouncingFadingText(
                    text = title,
                    color = color,
                    style = titleStyle,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniNowPlayingEqualizer(color = equalizerColor, scale = scale, playing = playing)
                Spacer(Modifier.width((4f * scale).dp))
                Text(
                    text = title,
                    color = color,
                    style = titleStyle,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun BouncingFadingText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    var containerWidthPx by remember(text) { mutableIntStateOf(0) }
    var contentWidthPx by remember(text) { mutableIntStateOf(0) }
    val overflowPx = (contentWidthPx - containerWidthPx).coerceAtLeast(0)
    val travel = remember { Animatable(0f) }
    val pixelsPerSecond = with(LocalDensity.current) { 36.dp.toPx() }

    LaunchedEffect(text, overflowPx) {
        travel.snapTo(0f)
        if (overflowPx <= 1) return@LaunchedEffect
        val durationMs = ((overflowPx / pixelsPerSecond) * 1_000f)
            .roundToInt()
            .coerceIn(1_700, 14_000)
        delay(1_150)
        while (isActive) {
            travel.animateTo(
                targetValue = overflowPx.toFloat(),
                animationSpec = tween(durationMs, easing = LinearEasing),
            )
            delay(850)
            travel.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMs, easing = LinearEasing),
            )
            delay(1_150)
        }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerWidthPx = it.width }
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                if (overflowPx > 1) {
                    val leftAlpha = if (travel.value > 1f) 0f else 1f
                    val rightAlpha = if (travel.value < overflowPx - 1f) 0f else 1f
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.White.copy(alpha = leftAlpha),
                            0.08f to Color.White,
                            0.92f to Color.White,
                            1f to Color.White.copy(alpha = rightAlpha),
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
        contentAlignment = if (overflowPx > 1) Alignment.CenterStart else Alignment.Center,
    ) {
        val textModifier = Modifier
            .wrapContentWidth(unbounded = true)
            .graphicsLayer { translationX = -travel.value }
        if (leading == null) {
            Text(
                text = text,
                color = color,
                style = style,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                modifier = textModifier.onSizeChanged { contentWidthPx = it.width },
            )
        } else {
            Row(
                modifier = textModifier.onSizeChanged { contentWidthPx = it.width },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading()
                Text(
                    text = text,
                    color = color,
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}

@Composable
private fun MiniNowPlayingEqualizer(
    color: Color,
    scale: Float,
    playing: Boolean,
) {
    val motion = rememberInfiniteTransition(label = "titleEqualizer")
    val first = motion.animateFloat(
        initialValue = 0.26f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(430, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "titleEqualizerFirst",
    )
    val second = motion.animateFloat(
        initialValue = 0.88f,
        targetValue = 0.34f,
        animationSpec = infiniteRepeatable(
            tween(570, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "titleEqualizerSecond",
    )
    val third = motion.animateFloat(
        initialValue = 0.42f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            tween(360, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "titleEqualizerThird",
    )

    Row(
        modifier = Modifier
            .width((15f * scale).dp)
            .height((14f * scale).dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        listOf(first.value, second.value, third.value).mapIndexed { index, animatedLevel ->
            if (playing) animatedLevel else listOf(0.46f, 0.72f, 0.56f)[index]
        }.forEach { level ->
            Box(
                Modifier
                    .width((3f * scale).dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        scaleY = level
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .background(
                        color = color,
                        shape = RoundedCornerShape((2f * scale).dp),
                    )
            )
        }
    }
}

@Composable
private fun MotionAlbumArtwork(
    artwork: Bitmap?,
    motionUrl: String?,
    paper: Color,
    scale: Float,
    playing: Boolean,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (artwork != null) {
            Image(
                bitmap = artwork.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_cover_art),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = paper.copy(alpha = 0.82f),
                modifier = Modifier.size((82f * scale).dp),
            )
        }

        if (!motionUrl.isNullOrBlank()) {
            LoopingMotionArtwork(
                url = motionUrl,
                playing = playing,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LoopingMotionArtwork(
    url: String,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    var firstFrameRendered by remember(url) { mutableStateOf(false) }
    var playbackFailed by remember(url) { mutableStateOf(false) }
    val videoAlpha by animateFloatAsState(
        targetValue = if (firstFrameRendered && !playbackFailed) 1f else 0f,
        animationSpec = tween(420),
        label = "motionArtworkReveal",
    )
    val player = remember(url) {
        // Motion art is decorative. Its HLS buffer should never compete with
        // audio/lyrics or consume a large share of a 2 GB TV's memory.
        val compactLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 1_500,
                /* maxBufferMs = */ 8_000,
                /* bufferForPlaybackMs = */ 400,
                /* bufferForPlaybackAfterRebufferMs = */ 900,
            )
            .setTargetBufferBytes(4 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(compactLoadControl)
            .build()
            .apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        }
    }

    DisposableEffect(player, url) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackFailed = true
            }
        }
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = playing
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Free the decoder from rendering/buffering work as soon as music pauses.
    LaunchedEffect(player, playing) {
        player.playWhenReady = playing
    }

    if (!playbackFailed) {
        androidx.compose.runtime.key(url) {
            AndroidView(
                factory = { viewContext ->
                    TextureView(viewContext).also(player::setVideoTextureView)
                },
                update = player::setVideoTextureView,
                modifier = modifier.alpha(videoAlpha),
            )
        }
    }
}

@Composable
private fun DisplaySyncedLyricsStage(
    lyrics: LyricsState,
    viewModel: MainViewModel,
    accent: Color,
    paper: Color,
    muted: Color,
    scale: Float,
    durationMs: Long,
    writerCredit: String,
    playing: Boolean,
    browseSignal: Int,
    browseDirection: Int,
    modifier: Modifier = Modifier,
) {
    // Keep the display-rate clock inside the lyric subtree. Reading it from
    // the root forced album art and the animated background to recompose for
    // every karaoke frame on entry-level TV hardware.
    val sourcePositionMs by viewModel.audioPositionMs.collectAsState()
    val displayPositionMs = rememberDisplaySyncedPosition(
        sourcePositionMs = sourcePositionMs,
        viewModel = viewModel,
        enabled = lyrics.status == LyricsStatus.SYNCED && playing,
    )
    AppleStyleLyricsStage(
        lyrics = lyrics,
        positionMs = displayPositionMs,
        accent = accent,
        paper = paper,
        muted = muted,
        scale = scale,
        durationMs = durationMs,
        writerCredit = writerCredit,
        playing = playing,
        browseSignal = browseSignal,
        browseDirection = browseDirection,
        modifier = modifier,
    )
}

@Composable
private fun AppleStyleLyricsStage(
    lyrics: LyricsState,
    positionMs: Long,
    accent: Color,
    paper: Color,
    muted: Color,
    scale: Float,
    durationMs: Long,
    writerCredit: String,
    playing: Boolean,
    browseSignal: Int,
    browseDirection: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        when (lyrics.status) {
            LyricsStatus.SYNCED -> {
                ScrollableSyncedLyrics(
                    lines = lyrics.lines,
                    positionMs = positionMs,
                    accent = accent,
                    paper = paper,
                    scale = scale,
                    durationMs = durationMs,
                    writerCredit = writerCredit,
                    playing = playing,
                    browseSignal = browseSignal,
                    browseDirection = browseDirection,
                )
            }

            LyricsStatus.PLAIN -> {
                PlainLyrics(
                    lyrics = lyrics.plainLyrics,
                    paper = paper,
                    scale = scale,
                    writerCredit = writerCredit,
                    browseSignal = browseSignal,
                    browseDirection = browseDirection,
                )
            }

            LyricsStatus.LOADING -> IOSLyricsLoadingIndicator(paper, scale)
            LyricsStatus.INSTRUMENTAL -> LargeLyricsMessage(
                stringResource(R.string.lyrics_instrumental), paper, scale
            )
            LyricsStatus.NOT_FOUND -> LargeLyricsMessage(
                stringResource(R.string.lyrics_not_found), muted, scale
            )
            LyricsStatus.ERROR -> LargeLyricsMessage(
                stringResource(R.string.lyrics_error), muted, scale
            )
            LyricsStatus.IDLE -> LargeLyricsMessage(
                stringResource(R.string.lyrics_waiting), muted, scale
            )
        }
    }
}

@Composable
private fun ScrollableSyncedLyrics(
    lines: List<LyricLine>,
    positionMs: Long,
    accent: Color,
    paper: Color,
    scale: Float,
    durationMs: Long,
    writerCredit: String,
    playing: Boolean,
    browseSignal: Int,
    browseDirection: Int,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val timeline = remember(lines, durationMs, writerCredit) {
        buildLyricsTimeline(lines, durationMs, writerCredit)
    }
    val activeTimelineIndex = timeline.indexOfLast { it.startMs <= positionMs }
    var browsing by remember(lines) { mutableStateOf(false) }
    // Keep all rows mounted so the next lyric can travel into the live
    // position. Rows outside the current/next window only fade visually.
    val visibleTimeline = timeline
    val activeIndex = activeTimelineIndex
    val upcomingLineIndex = visibleTimeline.indices.firstOrNull { index ->
        index > activeIndex && visibleTimeline[index].line != null
    }

    LaunchedEffect(browseSignal) {
        if (browseSignal == 0 || browseDirection == 0) return@LaunchedEffect
        val startsBrowsing = !browsing
        browsing = true
        if (startsBrowsing) {
            // Wait for LazyColumn to receive the complete timeline before
            // positioning at the live row; Up can then reveal past lyrics.
            withFrameNanos { }
            listState.animateLineToCenter(activeTimelineIndex.coerceAtLeast(0))
        }
        listState.animateScrollBy(
            value = with(density) { (browseDirection * 124f * scale).dp.toPx() },
            animationSpec = tween(170, easing = FastOutSlowInEasing),
        )
    }
    LaunchedEffect(browseSignal) {
        if (browseSignal == 0) return@LaunchedEffect
        delay(3_200)
        browsing = false
    }
    LaunchedEffect(activeTimelineIndex, browsing, visibleTimeline, playing) {
        if (playing && !browsing && activeIndex >= 0) {
            listState.animateLineToCenter(activeIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
        contentPadding = PaddingValues(
            top = (250f * scale).dp,
            bottom = (154f * scale).dp,
        ),
        verticalArrangement = Arrangement.spacedBy((19f * scale).dp),
    ) {
        itemsIndexed(
            items = visibleTimeline,
            key = { _, entry -> entry.key },
        ) { index, entry ->
            val edgeBlur = lyricEdgeBlurFactor(
                listState = listState,
                itemIndex = index,
                scale = scale,
                density = density,
            )
            val isOutsideLiveWindow = !browsing && when {
                entry.line == null -> index != activeIndex
                index < activeIndex -> true
                upcomingLineIndex != null && index > upcomingLineIndex -> true
                else -> false
            }
            when {
                entry.line != null -> ScrollableLyricLine(
                    line = entry.line,
                    lineIndex = index,
                    activeIndex = activeIndex,
                    // Only the active row receives the frame clock. Stable
                    // timestamps let Compose skip every inactive lyric row.
                    positionMs = if (index == activeIndex) positionMs else entry.startMs - 1L,
                    accent = accent,
                    paper = paper,
                    scale = scale,
                    browsing = browsing,
                    isUpcoming = !browsing && index == upcomingLineIndex,
                    hasGapBefore = index > activeIndex + 1,
                    edgeBlur = edgeBlur,
                    isOutsideLiveWindow = isOutsideLiveWindow,
                )

                entry.writerCredit != null -> LyricsWriterCredit(
                    writerCredit = entry.writerCredit,
                    paper = paper,
                    scale = scale,
                    edgeBlur = edgeBlur,
                    isOutsideLiveWindow = isOutsideLiveWindow,
                )

                else -> LyricsGapIndicator(
                    lineIndex = index,
                    activeIndex = activeIndex,
                    positionMs = if (index == activeIndex) positionMs else entry.startMs,
                    startMs = entry.startMs,
                    endMs = entry.endMs,
                    paper = paper,
                    scale = scale,
                    browsing = browsing,
                    playing = playing,
                    edgeBlur = edgeBlur,
                    isOutsideLiveWindow = isOutsideLiveWindow,
                )
            }
        }
    }
}

private fun compactLyricsTimeline(
    timeline: List<LyricsTimelineEntry>,
    activeIndex: Int,
): List<LyricsTimelineEntry> {
    if (timeline.isEmpty()) return emptyList()
    if (activeIndex < 0) return timeline.take(2)
    val nextLyricIndex = timeline.indices.firstOrNull { index ->
        index > activeIndex && timeline[index].line != null
    }
    val endExclusive = (nextLyricIndex ?: activeIndex) + 1
    return timeline.subList(activeIndex, endExclusive)
}

private fun lyricEdgeBlurFactor(
    listState: LazyListState,
    itemIndex: Int,
    scale: Float,
    density: androidx.compose.ui.unit.Density,
): Float {
    val layout = listState.layoutInfo
    val item = layout.visibleItemsInfo.firstOrNull { it.index == itemIndex } ?: return 0f
    val itemCenter = item.offset + item.size / 2f
    val distanceToEdge = minOf(
        itemCenter - layout.viewportStartOffset,
        layout.viewportEndOffset - itemCenter,
    )
    val edgeZonePx = with(density) { (96f * scale).dp.toPx() }
    return ((edgeZonePx - distanceToEdge) / edgeZonePx).coerceIn(0f, 1f)
}

/**
 * Dot cues are reserved for long instrumental passages: the introduction and
 * breaks between lyric lines. Short breaths and the quiet outro stay clean.
 */
private const val INSTRUMENTAL_GAP_MINIMUM_MS = 7_000L
private const val LYRIC_GAP_VISUAL_DELAY_MS = 520L

private data class LyricsTimelineEntry(
    val key: String,
    val startMs: Long,
    val endMs: Long,
    val line: LyricLine? = null,
    val writerCredit: String? = null,
)

private fun buildLyricsTimeline(
    lines: List<LyricLine>,
    durationMs: Long,
    writerCredit: String,
): List<LyricsTimelineEntry> {
    if (lines.isEmpty()) return emptyList()
    val timeline = mutableListOf<LyricsTimelineEntry>()

    fun addGap(startMs: Long, endMs: Long, afterLyric: Boolean = false) {
        val start = (startMs + if (afterLyric) LYRIC_GAP_VISUAL_DELAY_MS else 0L)
            .coerceAtLeast(0L)
        if (endMs <= start) return
        if (endMs - start < INSTRUMENTAL_GAP_MINIMUM_MS) return
        val previous = timeline.lastOrNull()
        if (previous != null && previous.line == null && start <= previous.endMs + 80L) {
            timeline[timeline.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, endMs))
        } else {
            timeline += LyricsTimelineEntry(
                key = "gap-$start",
                startMs = start,
                endMs = endMs,
            )
        }
    }

    val voicedLines = mergeStandaloneParentheticalLines(
        lines.filter { it.text.isNotBlank() }
    )
    // A long instrumental introduction gets the same progressive cue as an
    // interlude, while the end of a song remains intentionally quiet.
    voicedLines.firstOrNull()?.let { firstLine ->
        addGap(startMs = 0L, endMs = firstLine.timeMs)
    }
    voicedLines.forEachIndexed { index, line ->
        val nextLine = voicedLines.getOrNull(index + 1)

        val lyricEndMs = lyricAudibleEndMs(line)
        timeline += LyricsTimelineEntry(
            key = "line-${line.timeMs}-$index",
            startMs = line.timeMs,
            endMs = lyricEndMs,
            line = line,
        )
        // Keep long instrumental breaks between lyric lines, but not an outro.
        nextLine?.let { addGap(startMs = lyricEndMs, endMs = it.timeMs, afterLyric = true) }
    }
    val lastLyric = timeline.lastOrNull { it.line != null }
    if (lastLyric != null && writerCredit.isNotBlank()) {
        // Keep the credit as the final rail item, so it glides into the live
        // slot after the last sung line instead of appearing as an overlay.
        timeline += LyricsTimelineEntry(
            key = "writer-credit-${lastLyric.endMs}",
            startMs = lastLyric.endMs + 720L,
            endMs = durationMs.coerceAtLeast(lastLyric.endMs + 720L),
            writerCredit = writerCredit.trim(),
        )
    }
    return timeline
}

private fun lyricAudibleEndMs(line: LyricLine): Long {
    if (line.exactWordTiming) {
        val lastWordEnd = line.words.maxOfOrNull { it.endMs } ?: line.timeMs
        // Some providers give a line duration that extends beyond their last
        // word marker. Respect that tail so an instrumental cue cannot cover
        // a visibly unfinished lyric.
        return maxOf(lastWordEnd, line.endMs ?: line.timeMs)
    }
    line.endMs?.takeIf { it > line.timeMs }?.let { return it }
    // Untimed/LRC text should err on the side of retaining the lyric longer;
    // an early gap cue is far more distracting than a slightly later one.
    val estimatedDurationMs = (line.words.size.coerceAtLeast(1) * 620L)
        .coerceIn(1_900L, 9_000L)
    return line.timeMs + estimatedDurationMs
}

private enum class LyricAsidePlacement { ABOVE, BELOW }

private data class LyricDisplayParts(
    val mainText: String,
    val mainWords: List<io.github.jqssun.airplay.lyrics.LyricWord>,
    val asideText: String? = null,
    val asideWords: List<io.github.jqssun.airplay.lyrics.LyricWord> = emptyList(),
    val asidePlacement: LyricAsidePlacement = LyricAsidePlacement.ABOVE,
)

private const val STANDALONE_ASIDE_ATTACH_WINDOW_MS = 1_800L
private val parentheticalLyricPattern = Regex("[（(]([^（）()]*)[）)]")
private val standaloneParentheticalPattern = Regex(
    "^\\s*[（(].+[）)]\\s*(?:[x×]\\s*\\d+)?\\s*$",
    RegexOption.IGNORE_CASE,
)
private val parentheticalMarkPattern = Regex("[（）()]")

/**
 * Providers such as NetEase commonly send backing vocals as their own YRC
 * row, for example `（And watches everything）`. Attach a row like that to
 * the lyric immediately before it when their timing touches, or to the next
 * lyric as an above-line aside when it arrives independently.
 */
internal fun mergeStandaloneParentheticalLines(lines: List<LyricLine>): List<LyricLine> {
    if (lines.isEmpty()) return emptyList()
    val merged = mutableListOf<LyricLine>()
    var pendingAbove: LyricLine? = null

    lines.forEach { line ->
        if (standaloneParentheticalPattern.matches(line.text)) {
            val previous = merged.lastOrNull()
            val attachesBelow = previous != null &&
                line.timeMs >= previous.timeMs &&
                line.timeMs <= lyricAudibleEndMs(previous) + STANDALONE_ASIDE_ATTACH_WINDOW_MS
            if (attachesBelow) {
                merged[merged.lastIndex] = mergeLyricDisplayLines(previous, line)
            } else {
                pendingAbove?.let(merged::add)
                pendingAbove = line
            }
        } else {
            val above = pendingAbove
            if (above != null) {
                merged += mergeLyricDisplayLines(above, line)
                pendingAbove = null
            } else {
                merged += line
            }
        }
    }
    pendingAbove?.let(merged::add)
    return merged
}

private fun mergeLyricDisplayLines(first: LyricLine, second: LyricLine): LyricLine {
    val words = first.words + second.words
    val firstIsAside = standaloneParentheticalPattern.matches(first.text)
    return LyricLine(
        timeMs = minOf(first.timeMs, second.timeMs),
        text = "${first.text.trimEnd()} ${second.text.trimStart()}",
        words = words,
        exactWordTiming = first.exactWordTiming && second.exactWordTiming && words.isNotEmpty(),
        endMs = maxOf(lyricAudibleEndMs(first), lyricAudibleEndMs(second)),
        alignment = if (firstIsAside) second.alignment else first.alignment,
    )
}

private fun splitLyricDisplayParts(line: LyricLine): LyricDisplayParts {
    val matches = parentheticalLyricPattern.findAll(line.text).toList()
    if (matches.isEmpty()) return LyricDisplayParts(line.text, line.words)

    val asideText = matches.joinToString(" ") { it.groupValues[1].trim() }
        .replace(Regex("\\s+"), " ")
        .trim()
    val mainText = parentheticalLyricPattern.replace(line.text, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    // A line made entirely of a parenthetical is still a normal lyric line;
    // only use the compact treatment when it accompanies main lyrics.
    if (asideText.isBlank() || mainText.isBlank()) return LyricDisplayParts(line.text, line.words)

    var searchStart = 0
    val asideRanges = matches.map { it.range }
    val mainWords = mutableListOf<io.github.jqssun.airplay.lyrics.LyricWord>()
    val asideWords = mutableListOf<io.github.jqssun.airplay.lyrics.LyricWord>()
    line.words.forEach { word ->
        val matchStart = line.text.indexOf(word.text, startIndex = searchStart, ignoreCase = true)
        val wordStart = if (matchStart >= 0) matchStart else searchStart
        val wordEnd = (wordStart + word.text.length).coerceAtMost(line.text.length)
        searchStart = wordEnd
        val isAside = asideRanges.any { range ->
            wordStart < range.last + 1 && wordEnd > range.first
        }
        if (isAside) {
            val cleaned = word.text.replace(parentheticalMarkPattern, "")
            if (cleaned.isNotBlank()) asideWords += word.copy(text = cleaned)
        } else {
            mainWords += word
        }
    }

    return LyricDisplayParts(
        mainText = mainText,
        mainWords = mainWords,
        asideText = asideText,
        asideWords = asideWords,
        asidePlacement = if (matches.first().range.first == 0) {
            LyricAsidePlacement.ABOVE
        } else {
            LyricAsidePlacement.BELOW
        },
    )
}

@Composable
private fun ScrollableLyricLine(
    line: LyricLine,
    lineIndex: Int,
    activeIndex: Int,
    positionMs: Long,
    accent: Color,
    paper: Color,
    scale: Float,
    browsing: Boolean,
    isUpcoming: Boolean,
    hasGapBefore: Boolean,
    edgeBlur: Float,
    isOutsideLiveWindow: Boolean,
) {
    val displayParts = remember(line) { splitLyricDisplayParts(line) }
    val isActive = lineIndex == activeIndex
    val isTrailing = line.alignment == LyricAlignment.TRAILING
    val textAlign = if (isTrailing) TextAlign.End else TextAlign.Start
    val distance = abs(lineIndex - activeIndex)
    val emphasis by animateFloatAsState(
        targetValue = when {
            isActive -> 1f
            isUpcoming -> 0.68f
            browsing -> 0.82f
            distance == 1 -> 0.64f
            distance == 2 -> 0.44f
            else -> 0.25f
        },
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "lyricLineEmphasis",
    )
    val sizeScale by animateFloatAsState(
        targetValue = when {
            isActive -> 1f
            isUpcoming -> 0.9f
            browsing -> 0.92f
            else -> 0.82f
        },
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "lyricLineScale",
    )
    val logicalBlur = when {
        browsing || isActive -> 0f
        isUpcoming -> 2.2f
        distance == 1 -> 1.1f
        distance == 2 -> 2.8f
        distance == 3 -> 4.8f
        else -> 6.5f
    }
    val blurRadius by animateFloatAsState(
        targetValue = maxOf(logicalBlur, edgeBlur * 3.4f),
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "lyricLineBlur",
    )
    val edgeFade = 1f - edgeBlur * 0.34f
    val liveWindowAlpha by animateFloatAsState(
        targetValue = if (isOutsideLiveWindow) 0f else 1f,
        animationSpec = tween(340, easing = FastOutSlowInEasing),
        label = "lyricLiveWindowAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = if (isUpcoming) {
                    (if (hasGapBefore) 20f else 48f) * scale
                } else {
                    0f
                }.dp
            )
            .graphicsLayer {
                alpha = emphasis * edgeFade * liveWindowAlpha
                scaleX = sizeScale
                scaleY = sizeScale
                transformOrigin = TransformOrigin(if (isTrailing) 1f else 0f, 0.5f)
            }
            .then(
                if (blurRadius > 0.01f) Modifier.blur((blurRadius * scale).dp) else Modifier
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isTrailing) Alignment.End else Alignment.Start,
        ) {
            if (displayParts.asidePlacement == LyricAsidePlacement.ABOVE) {
                ParentheticalLyric(
                    parts = displayParts,
                    active = isActive,
                    positionMs = positionMs,
                    accent = accent,
                    paper = paper,
                    scale = scale,
                    alignment = line.alignment,
                )
            }

            if (isActive) {
                ProgressiveWordLine(
                    text = displayParts.mainText,
                    words = displayParts.mainWords,
                    positionMs = positionMs,
                    accent = accent,
                    paper = paper,
                    scale = scale,
                    textAlign = textAlign,
                )
            } else {
                Text(
                    displayParts.mainText.ifBlank { "♪" },
                    color = paper,
                    fontSize = (46f * scale).sp,
                    lineHeight = (56f * scale).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (displayParts.asidePlacement == LyricAsidePlacement.BELOW) {
                ParentheticalLyric(
                    parts = displayParts,
                    active = isActive,
                    positionMs = positionMs,
                    accent = accent,
                    paper = paper,
                    scale = scale,
                    alignment = line.alignment,
                )
            }
        }
    }
}

@Composable
private fun ParentheticalLyric(
    parts: LyricDisplayParts,
    active: Boolean,
    positionMs: Long,
    accent: Color,
    paper: Color,
    scale: Float,
    alignment: LyricAlignment,
) {
    val aside = parts.asideText ?: return
    val textAlign = if (alignment == LyricAlignment.TRAILING) TextAlign.End else TextAlign.Start
    if (parts.asidePlacement == LyricAsidePlacement.BELOW) {
        Spacer(Modifier.height((9f * scale).dp))
    }
    if (active) {
        ProgressiveWordLine(
            text = aside,
            words = parts.asideWords,
            positionMs = positionMs,
            accent = accent,
            paper = paper,
            scale = scale,
            fontSize = 27f,
            lineHeight = 34f,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            textAlign = textAlign,
        )
    } else {
        Text(
            text = aside,
            color = paper,
            fontSize = (26f * scale).sp,
            lineHeight = (33f * scale).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (parts.asidePlacement == LyricAsidePlacement.ABOVE) {
        Spacer(Modifier.height((7f * scale).dp))
    }
}

@Composable
private fun LyricsWriterCredit(
    writerCredit: String,
    paper: Color,
    scale: Float,
    edgeBlur: Float,
    isOutsideLiveWindow: Boolean,
) {
    val visibility by animateFloatAsState(
        targetValue = if (isOutsideLiveWindow) 0f else 1f,
        animationSpec = tween(340, easing = FastOutSlowInEasing),
        label = "lyricsWriterCreditVisibility",
    )
    val blurRadius by animateFloatAsState(
        targetValue = edgeBlur * 2.4f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "lyricsWriterCreditBlur",
    )
    val styledCredit = remember(writerCredit) {
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Black)) {
                append("Written By:")
            }
            append(" ")
            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                append(writerCredit)
            }
        }
    }

    Text(
        text = styledCredit,
        color = paper,
        fontSize = (28f * scale).sp,
        lineHeight = (37f * scale).sp,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = visibility * (1f - edgeBlur * 0.26f) }
            .then(
                if (blurRadius > 0.01f) Modifier.blur((blurRadius * scale).dp) else Modifier
            ),
    )
}

@Composable
private fun LyricsGapIndicator(
    lineIndex: Int,
    activeIndex: Int,
    positionMs: Long,
    startMs: Long,
    endMs: Long,
    paper: Color,
    scale: Float,
    browsing: Boolean,
    playing: Boolean,
    edgeBlur: Float,
    isOutsideLiveWindow: Boolean,
) {
    val isActive = lineIndex == activeIndex
    val distance = abs(lineIndex - activeIndex)
    val emphasis by animateFloatAsState(
        targetValue = when {
            isActive -> 1f
            browsing -> 0.82f
            distance == 1 -> 0.58f
            else -> 0.3f
        },
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "lyricsGapEmphasis",
    )
    val logicalBlur = if (browsing || isActive) 0f else if (distance == 1) 1.2f else 3.8f
    val blurRadius by animateFloatAsState(
        targetValue = maxOf(logicalBlur, edgeBlur * 3.2f),
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "lyricsGapBlur",
    )
    val reachesNextLyric = isActive && endMs > positionMs && endMs - positionMs <= 300L
    val arrivalFade by animateFloatAsState(
        targetValue = if (reachesNextLyric) 0f else 1f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "lyricsGapArrivalFade",
    )
    val liveWindowAlpha by animateFloatAsState(
        targetValue = if (isOutsideLiveWindow) 0f else 1f,
        animationSpec = tween(340, easing = FastOutSlowInEasing),
        label = "lyricsGapLiveWindowAlpha",
    )
    val dotBreath = if (playing && isActive) {
        val transition = rememberInfiniteTransition(label = "lyricsGapDots")
        val breath by transition.animateFloat(
            initialValue = 0.97f,
            targetValue = 1.035f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_450, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "lyricsGapDotBreath",
        )
        breath
    } else {
        1f
    }
    val elapsedMs = (positionMs - startMs).coerceAtLeast(0L)
    val gapDurationMs = (endMs - startMs).coerceAtLeast(INSTRUMENTAL_GAP_MINIMUM_MS)
    val entrance = FastOutSlowInEasing.transform((elapsedMs / 420f).coerceIn(0f, 1f))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((64f * scale).dp)
            .graphicsLayer {
                alpha = emphasis * arrivalFade * liveWindowAlpha * (1f - edgeBlur * 0.34f)
            }
            .then(
                if (blurRadius > 0.01f) Modifier.blur((blurRadius * scale).dp) else Modifier
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(
            Modifier
                .size((96f * scale).dp, (34f * scale).dp)
        ) {
            repeat(3) { index ->
                // The cue illuminates left-to-right at the beginning of a
                // long break, then rests with a very small shared breath.
                val activation = stagedDotProgress(elapsedMs, gapDurationMs, index)
                val breath = if (playing && isActive && activation > 0.96f) dotBreath else 1f
                val alpha = (0.2f + activation * 0.8f) * breath * entrance
                val radius = size.height * (0.23f + activation * 0.16f) * breath
                drawCircle(
                    color = paper.copy(alpha = alpha),
                    radius = radius,
                    center = androidx.compose.ui.geometry.Offset(
                        x = size.width * (0.14f + index * 0.36f),
                        y = size.height / 2f,
                    ),
                )
            }
        }
    }
}

private fun stagedDotProgress(elapsedMs: Long, gapDurationMs: Long, index: Int): Float {
    // Let all three dots arrive progressively over the first ~62% of a long
    // instrumental, leaving a calm fully-lit beat before the lyric returns.
    val stagingWindowMs = (gapDurationMs * 0.62f).toLong().coerceIn(3_500L, 7_000L)
    val startMs = stagingWindowMs * index / 3L
    val fadeMs = (stagingWindowMs / 5L).coerceIn(500L, 900L)
    val progress = ((elapsedMs - startMs).toFloat() / fadeMs).coerceIn(0f, 1f)
    return FastOutSlowInEasing.transform(progress)
}

/*
private fun stalePlaceholder(
    targetValue: Float,
) = targetValue
*/

/*
    val blurRadius by animateFloatAsState(
        targetValue = if (browsing || isActive) {
            0f
        } else {
            when (distance) {
                1 -> 1.4f
                2 -> 3.6f
                3 -> 6.4f
                else -> 9f
            }
        },
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "lyricLineBlur",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = if (isUpcoming) {
                    (if (hasGapBefore) 20f else 48f) * scale
                } else {
                    0f
                }.dp
            )
            .graphicsLayer {
                alpha = emphasis
                scaleX = sizeScale
                scaleY = sizeScale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .then(
                if (blurRadius > 0.01f) Modifier.blur((blurRadius * scale).dp) else Modifier
            ),
    ) {
        if (isActive) {
            ProgressiveWordLine(
                line = line,
                positionMs = positionMs,
                accent = accent,
                paper = paper,
                scale = scale,
            )
        } else {
            Text(
                line.text.ifBlank { "♪" },
                color = paper,
                fontSize = (46f * scale).sp,
                lineHeight = (56f * scale).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LyricsGapIndicator(
    lineIndex: Int,
    activeIndex: Int,
    positionMs: Long,
    endMs: Long,
    paper: Color,
    scale: Float,
    browsing: Boolean,
) {
    val isActive = lineIndex == activeIndex
    val distance = abs(lineIndex - activeIndex)
    val emphasis by animateFloatAsState(
        targetValue = when {
            isActive -> 1f
            browsing -> 0.82f
            distance == 1 -> 0.58f
            else -> 0.3f
        },
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "lyricsGapEmphasis",
    )
    val blurRadius by animateFloatAsState(
        targetValue = if (browsing || isActive) 0f else if (distance == 1) 1.2f else 3.8f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "lyricsGapBlur",
    )
    val transition = rememberInfiniteTransition(label = "lyricsGapDots")
    val middleDotBreath by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "lyricsGapMiddleDotBreath",
    )
    val reachesNextLyric = isActive && endMs > positionMs && endMs - positionMs <= 300L
    val arrivalFade by animateFloatAsState(
        targetValue = if (reachesNextLyric) 0f else 1f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "lyricsGapArrivalFade",
    )
    // The reference waiting state keeps the first two dots illuminated. The
    // trailing dot fills only as the next lyric approaches, then all dots fade.
    val remainingMs = (endMs - positionMs).coerceAtLeast(0L)
    val trailingDotProgress = FastOutSlowInEasing.transform(
        ((2_400L - remainingMs).toFloat() / 2_400f).coerceIn(0f, 1f)
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((46f * scale).dp)
            .graphicsLayer { alpha = emphasis * arrivalFade }
            .then(
                if (blurRadius > 0.01f) Modifier.blur((blurRadius * scale).dp) else Modifier
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.size((57f * scale).dp, (18f * scale).dp)) {
            repeat(3) { index ->
                val alpha = when (index) {
                    0 -> 1f
                    1 -> 0.9f * middleDotBreath
                    else -> 0.24f + trailingDotProgress * 0.76f
                }
                drawCircle(
                    color = paper.copy(alpha = alpha),
                    radius = size.height * 0.36f,
                    center = androidx.compose.ui.geometry.Offset(
                        x = size.width * (0.16f + index * 0.34f),
                        y = size.height / 2f,
                    ),
                )
            }
        }
    }
}

*/

@Composable
private fun PlainLyrics(
    lyrics: String,
    paper: Color,
    scale: Float,
    writerCredit: String,
    browseSignal: Int,
    browseDirection: Int,
) {
    val lines = remember(lyrics) { lyrics.lines() }
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    LaunchedEffect(browseSignal) {
        if (browseSignal == 0 || browseDirection == 0) return@LaunchedEffect
        listState.animateScrollBy(
            value = with(density) { (browseDirection * 124f * scale).dp.toPx() },
            animationSpec = tween(170, easing = FastOutSlowInEasing),
        )
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
        contentPadding = PaddingValues(vertical = (38f * scale).dp),
        verticalArrangement = Arrangement.spacedBy((9f * scale).dp),
    ) {
        itemsIndexed(lines) { _, line ->
            if (line.isBlank()) {
                Spacer(Modifier.height((20f * scale).dp))
            } else {
                Text(
                    text = line,
                    color = paper.copy(alpha = 0.9f),
                    fontSize = (35f * scale).sp,
                    lineHeight = (45f * scale).sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (writerCredit.isNotBlank()) {
            item(key = "writer-credit") {
                Spacer(Modifier.height((32f * scale).dp))
                LyricsWriterCredit(
                    writerCredit = writerCredit,
                    paper = paper,
                    scale = scale,
                    edgeBlur = 0f,
                    isOutsideLiveWindow = false,
                )
            }
        }
    }
}

private suspend fun LazyListState.animateLineToCenter(index: Int) {
    // Adjacent lyric rows are already visible. Aligning them to the top first
    // caused two consecutive animations and made the rail appear late.
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        scrollToItem(index)
        withFrameNanos { }
    }
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    val itemCenter = item.offset + (item.size / 2)
    val delta = itemCenter - viewportCenter
    if (abs(delta) > 1) {
        animateScrollBy(
            value = delta.toFloat(),
            animationSpec = spring(
                dampingRatio = 0.9f,
                stiffness = Spring.StiffnessMedium,
            ),
        )
    }
}

@Composable
private fun ProgressiveWordLine(
    text: String,
    words: List<io.github.jqssun.airplay.lyrics.LyricWord>,
    positionMs: Long,
    accent: Color,
    paper: Color,
    scale: Float,
    fontSize: Float = 50f,
    lineHeight: Float = 60f,
    fontWeight: FontWeight = FontWeight.Black,
    maxLines: Int = 3,
    textAlign: TextAlign = TextAlign.Start,
) {
    // A partial word timeline must never hide text supplied by the lyric
    // provider. It is better to show a complete, non-karaoke line than a
    // beautifully timed line with missing words.
    val renderWords = remember(text, words) {
        words.takeIf { lyricWordsMatchLine(text, it) }.orEmpty()
    }
    val highlighted = remember(text, renderWords, positionMs, accent, paper, scale) {
        buildAnnotatedString {
            if (renderWords.isEmpty()) {
                append(text.ifBlank { "♪" })
            } else {
                val future = paper.copy(alpha = 0.28f)
                val aura = mix(accent, Color.White, 0.34f)
                renderWords.forEach { word ->
                    val progress = lyricWordProgress(word, positionMs)
                    val wordFade = FastOutSlowInEasing.transform(progress)
                    val slice = progressiveTextSlice(word.text, progress)
                    val activeFade = FastOutSlowInEasing.transform(slice.blendFraction)
                    val isCurrentWord = progress > 0f && progress < 1f
                    val auraStrength = if (isCurrentWord) {
                        0.58f + 0.42f * sin(progress * PI.toFloat())
                    } else {
                        0f
                    }

                    fun appendKaraokeSpan(
                        value: String,
                        color: Color,
                        glow: Float = 0f,
                    ) {
                        if (value.isEmpty()) return
                        withStyle(
                            SpanStyle(
                                color = color,
                                shadow = if (glow > 0f) {
                                    Shadow(
                                        aura.copy(alpha = 0.52f * glow),
                                        blurRadius = 13f * scale,
                                    )
                                } else {
                                    null
                                },
                            )
                        ) { append(value) }
                    }

                    // Very short words do not have enough time for a readable
                    // character sweep; fading the word as one unit prevents a
                    // rapid flicker. Longer words retain the grapheme-safe
                    // karaoke fill below.
                    if (word.endMs - word.startMs < 360L) {
                        appendKaraokeSpan(
                            value = word.text,
                            color = mix(future, paper, wordFade),
                            glow = auraStrength,
                        )
                        return@forEach
                    }

                    // Fill every grapheme cluster instead of flipping a whole
                    // word at once. `progressiveTextSlice` keeps Thai marks
                    // and emoji intact while the fill follows the vocal. A
                    // subtle pre-light on following characters prevents hard
                    // visual steps between each cluster.
                    appendKaraokeSpan(
                        value = word.text.substring(0, slice.solidEnd),
                        color = paper,
                        glow = auraStrength * 0.72f,
                    )
                    appendKaraokeSpan(
                        value = word.text.substring(slice.solidEnd, slice.blendEnd),
                        color = mix(future, paper, activeFade),
                        glow = maxOf(activeFade, auraStrength),
                    )
                    appendKaraokeSpan(
                        value = word.text.substring(slice.blendEnd),
                        color = mix(future, paper, wordFade * 0.12f),
                    )
                }
            }
        }
    }
    Text(
        text = highlighted,
        color = paper,
        fontSize = (fontSize * scale).sp,
        lineHeight = (lineHeight * scale).sp,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun IOSLyricsLoadingIndicator(
    color: Color,
    scale: Float,
) {
    val transition = rememberInfiniteTransition(label = "iosLyricsSpinner")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "iosLyricsSpinnerPhase",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size((34f * scale).dp)) {
            val pivot = center
            repeat(12) { index ->
                val distance = (phase - index + 12f) % 12f
                val alpha = 0.15f + (1f - distance / 12f) * 0.85f
                rotate(index * 30f, pivot = pivot) {
                    drawLine(
                        color = color.copy(alpha = alpha),
                        start = androidx.compose.ui.geometry.Offset(pivot.x, size.height * 0.12f),
                        end = androidx.compose.ui.geometry.Offset(pivot.x, size.height * 0.31f),
                        strokeWidth = size.width * 0.09f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberDisplaySyncedPosition(
    sourcePositionMs: Long,
    viewModel: MainViewModel,
    enabled: Boolean,
): Long {
    val latestFallback by rememberUpdatedState(sourcePositionMs)
    var renderedPosition by remember { mutableLongStateOf(sourcePositionMs) }

    LaunchedEffect(viewModel, enabled) {
        if (!enabled) {
            renderedPosition = latestFallback
            return@LaunchedEffect
        }
        while (isActive) {
            withFrameNanos {
                val sample = viewModel.sampleAudioPositionMs().takeIf { it >= 0L }
                    ?: latestFallback
                if (renderedPosition != sample) {
                    renderedPosition = sample
                }
            }
        }
    }
    return renderedPosition
}

@Composable
private fun LargeLyricsMessage(
    text: String,
    color: Color,
    scale: Float,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = color,
            fontSize = (31f * scale).sp,
            lineHeight = (38f * scale).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
