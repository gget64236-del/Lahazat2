package com.floating.stopwatch.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sin as mathSin
import kotlin.random.Random

private data class Star(
    val xRatio: Float,
    val yRatio: Float,
    val radiusDp: Float,
    val baseAlpha: Float,
    val layer: Int,
    val color: Color,
    val isTwinkling: Boolean,
    val twinklePhase: Float,
    val twinkleSpeed: Float,
    val hasHalo: Boolean,
    val haloRadiusFactor: Float
)

private class MeteorState {
    var active: Boolean = false
    var startX: Float = 0f
    var startY: Float = 0f
    var endX: Float = 0f
    var endY: Float = 0f
    var startTimeSec: Float = 0f
    var durationSec: Float = 1.4f
    var coreRadiusPx: Float = 4f
    var haloRadiusPx: Float = 14f
    var tailLengthPx: Float = 250f
    var currentX: Float = 0f
    var currentY: Float = 0f
    var progress: Float = 0f

    // Microscopic trailing particles relative offsets & sizes
    val particleOffsets = FloatArray(4) { 0f }
    val particleDrifts = FloatArray(4) { 0f }
    val particleSizes = FloatArray(4) { 1f }
}

@Composable
fun StellarBackground(
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val lifecycleOwner = LocalLifecycleOwner.current

    var isResumed by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var timeNanos by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isResumed) {
        if (isResumed) {
            var lastFrame = 0L
            while (true) {
                withFrameNanos { frameTime ->
                    if (lastFrame != 0L) {
                        val delta = frameTime - lastFrame
                        timeNanos += delta
                    }
                    lastFrame = frameTime
                }
            }
        }
    }

    // Deterministically generate star field once
    val stars = remember {
        val random = Random(20260822L) // Fixed seed for stable positions
        val count = 200
        val list = ArrayList<Star>(count)

        val softWhite = Color(0xFFF5F5F7)
        val warmWhite = Color(0xFFFFF6E5)

        for (i in 0 until count) {
            val xRatio = random.nextFloat()
            val yRatio = random.nextFloat()

            val layerRoll = random.nextFloat()
            val layer = when {
                layerRoll < 0.50f -> 0
                layerRoll < 0.85f -> 1
                else -> 2
            }

            val radiusDp = when (layer) {
                0 -> 0.5f + random.nextFloat() * 0.4f
                1 -> 0.9f + random.nextFloat() * 0.5f
                else -> 1.4f + random.nextFloat() * 0.6f
            }

            val baseAlpha = when (layer) {
                0 -> 0.15f + random.nextFloat() * 0.30f
                1 -> 0.35f + random.nextFloat() * 0.35f
                else -> 0.60f + random.nextFloat() * 0.30f
            }

            val isWarm = random.nextFloat() < 0.08f // 8% subtle warm-white highlights
            val starColor = if (isWarm) warmWhite else softWhite

            val isTwinkling = random.nextFloat() < 0.12f // 12% asynchronous twinkling stars
            val twinklePhase = random.nextFloat() * 6.283185f
            val twinkleSpeed = 0.8f + random.nextFloat() * 1.2f

            val hasHalo = (layer == 2) && (random.nextFloat() < 0.20f) // Signature stars
            val haloRadiusFactor = if (hasHalo) 2.8f + random.nextFloat() * 1.2f else 0f

            list.add(
                Star(
                    xRatio = xRatio,
                    yRatio = yRatio,
                    radiusDp = radiusDp,
                    baseAlpha = baseAlpha,
                    layer = layer,
                    color = starColor,
                    isTwinkling = isTwinkling,
                    twinklePhase = twinklePhase,
                    twinkleSpeed = twinkleSpeed,
                    hasHalo = hasHalo,
                    haloRadiusFactor = haloRadiusFactor
                )
            )
        }
        list
    }

    val meteorState = remember { MeteorState() }
    var lastMeteorTriggerTimeSec by remember { mutableFloatStateOf(-5f) }
    var nextMeteorIntervalSec by remember { mutableFloatStateOf(9f) }

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        if (width <= 0f || height <= 0f) return@Canvas

        // 1. Pure Black Background
        drawRect(color = Color(0xFF000000))

        val timeSec = timeNanos / 1_000_000_000f

        // 2. Meteor Engine State Calculation
        val diagonal = hypot(width, height)
        if (!meteorState.active) {
            val elapsedSinceLast = timeSec - lastMeteorTriggerTimeSec
            if (elapsedSinceLast >= nextMeteorIntervalSec) {
                // Trigger a new meteor event
                val random = Random((timeSec * 1000).toLong())
                meteorState.active = true
                meteorState.startTimeSec = timeSec
                meteorState.durationSec = 1.3f + random.nextFloat() * 0.5f // 1.3s .. 1.8s
                meteorState.coreRadiusPx = (1.5f + random.nextFloat() * 0.8f) * density
                meteorState.haloRadiusPx = (5.0f + random.nextFloat() * 3.0f) * density
                meteorState.tailLengthPx = diagonal * (0.22f + random.nextFloat() * 0.08f)

                // Select diagonal trajectory (60-90% of screen diagonal length)
                val trajectoryLength = diagonal * (0.65f + random.nextFloat() * 0.20f)
                val isLeftToRight = random.nextBoolean()
                val angleDeg = if (isLeftToRight) 30f + random.nextFloat() * 30f else 120f + random.nextFloat() * 30f
                val angleRad = Math.toRadians(angleDeg.toDouble()).toFloat()

                val margin = 0.1f
                val startXRatio = if (isLeftToRight) -margin + random.nextFloat() * 0.3f else 1f + margin - random.nextFloat() * 0.3f
                val startYRatio = -margin + random.nextFloat() * 0.4f

                meteorState.startX = startXRatio * width
                meteorState.startY = startYRatio * height
                meteorState.endX = meteorState.startX + cos(angleRad) * trajectoryLength
                meteorState.endY = meteorState.startY + sin(angleRad) * trajectoryLength

                for (p in 0 until 4) {
                    meteorState.particleOffsets[p] = (30f + p * 25f + random.nextFloat() * 15f) * density
                    meteorState.particleDrifts[p] = (-4f + random.nextFloat() * 8f) * density
                    meteorState.particleSizes[p] = (0.5f + random.nextFloat() * 0.5f) * density
                }

                lastMeteorTriggerTimeSec = timeSec
                nextMeteorIntervalSec = 8.5f + random.nextFloat() * 5.0f // ~8.5s - 13.5s (avg ~11s)
            }
        } else {
            val progressRaw = (timeSec - meteorState.startTimeSec) / meteorState.durationSec
            if (progressRaw >= 1.0f) {
                meteorState.active = false
            } else {
                meteorState.progress = FastOutSlowInEasing.transform(progressRaw.coerceIn(0f, 1f))
                meteorState.currentX = meteorState.startX + (meteorState.endX - meteorState.startX) * meteorState.progress
                meteorState.currentY = meteorState.startY + (meteorState.endY - meteorState.startY) * meteorState.progress
            }
        }

        val meteorActive = meteorState.active
        val mX = meteorState.currentX
        val mY = meteorState.currentY
        val excitationRadius = 140f * density

        // 3. Render Stars
        for (i in stars.indices) {
            val star = stars[i]
            val x = star.xRatio * width
            val y = star.yRatio * height
            val radiusPx = star.radiusDp * density

            // Compute alpha (base + twinkling)
            var currentAlpha = star.baseAlpha
            if (star.isTwinkling) {
                val twinkleMod = mathSin(timeSec * star.twinkleSpeed * 2.5f + star.twinklePhase) * 0.18f
                currentAlpha = (currentAlpha + twinkleMod).coerceIn(0.05f, 0.95f)
            }

            // Subtle brightening when meteor passes nearby
            if (meteorActive) {
                val dist = hypot(x - mX, y - mY)
                if (dist < excitationRadius) {
                    val excitation = (1f - dist / excitationRadius) * 0.30f * (1f - meteorState.progress)
                    currentAlpha = (currentAlpha + excitation).coerceAtMost(1.0f)
                }
            }

            // Signature Star Halo
            if (star.hasHalo && currentAlpha > 0.3f) {
                val haloRadiusPx = radiusPx * star.haloRadiusFactor
                drawCircle(
                    color = star.color.copy(alpha = currentAlpha * 0.15f),
                    radius = haloRadiusPx,
                    center = Offset(x, y)
                )
            }

            // Star Core
            drawCircle(
                color = star.color.copy(alpha = currentAlpha),
                radius = radiusPx,
                center = Offset(x, y)
            )
        }

        // 4. Render Meteor (if active)
        if (meteorActive) {
            val progress = meteorState.progress
            val startX = meteorState.startX
            val startY = meteorState.startY
            val currentX = meteorState.currentX
            val currentY = meteorState.currentY

            // Fade in at start, fade out at end
            val alphaFade = when {
                progress < 0.15f -> progress / 0.15f
                progress > 0.82f -> (1.0f - progress) / 0.18f
                else -> 1.0f
            }.coerceIn(0f, 1f)

            val dx = currentX - startX
            val dy = currentY - startY
            val tailLen = meteorState.tailLengthPx
            val distTravelled = hypot(dx, dy)

            if (distTravelled > 2f) {
                val dirX = dx / distTravelled
                val dirY = dy / distTravelled

                val actualTailLen = tailLen.coerceAtMost(distTravelled)
                val tailStartX = currentX - dirX * actualTailLen
                val tailStartY = currentY - dirY * actualTailLen

                // Multi-layer tapered gradient tail
                // Outer subtle glow trail
                drawLinearGradientLine(
                    start = Offset(tailStartX, tailStartY),
                    end = Offset(currentX, currentY),
                    strokeWidth = 3.5f * density,
                    colors = listOf(
                        Color.Transparent,
                        Color(0x33FFFFFF),
                        Color(0xBBFFFFFF)
                    ),
                    alpha = alphaFade * 0.4f
                )

                // Inner sharp core trail
                drawLinearGradientLine(
                    start = Offset(tailStartX + dirX * actualTailLen * 0.3f, tailStartY + dirY * actualTailLen * 0.3f),
                    end = Offset(currentX, currentY),
                    strokeWidth = 1.2f * density,
                    colors = listOf(
                        Color.Transparent,
                        Color(0x88FFFFFF),
                        Color(0xFFFFFFFF)
                    ),
                    alpha = alphaFade * 0.95f
                )

                // Soft circular halo around luminous core
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.35f * alphaFade),
                            Color.White.copy(alpha = 0.08f * alphaFade),
                            Color.Transparent
                        ),
                        center = Offset(currentX, currentY),
                        radius = meteorState.haloRadiusPx
                    ),
                    center = Offset(currentX, currentY),
                    radius = meteorState.haloRadiusPx
                )

                // Luminous Core
                drawCircle(
                    color = Color.White.copy(alpha = 0.98f * alphaFade),
                    radius = meteorState.coreRadiusPx,
                    center = Offset(currentX, currentY)
                )

                // Microscopic trailing dust particles
                val perpX = -dirY
                val perpY = dirX
                for (p in 0 until 4) {
                    val pDist = meteorState.particleOffsets[p]
                    if (distTravelled > pDist) {
                        val px = currentX - dirX * pDist + perpX * meteorState.particleDrifts[p]
                        val py = currentY - dirY * pDist + perpY * meteorState.particleDrifts[p]
                        val pAlpha = ((1f - (pDist / (tailLen * 1.2f))) * alphaFade * 0.6f).coerceIn(0f, 1f)
                        drawCircle(
                            color = Color.White.copy(alpha = pAlpha),
                            radius = meteorState.particleSizes[p],
                            center = Offset(px, py)
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLinearGradientLine(
    start: Offset,
    end: Offset,
    strokeWidth: Float,
    colors: List<Color>,
    alpha: Float
) {
    drawLine(
        brush = Brush.linearGradient(
            colors = colors.map { it.copy(alpha = it.alpha * alpha) },
            start = start,
            end = end
        ),
        start = start,
        end = end,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
}
