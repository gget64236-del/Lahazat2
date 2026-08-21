package com.floating.stopwatch.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private data class Star(
    val relX: Float,
    val relY: Float,
    val baseRadius: Float,
    val baseAlpha: Float,
    val twinklePhase: Float,
    val twinkleSpeed: Float,
    val isWarm: Boolean,
    val isSignature: Boolean
)

@Composable
fun AnimatedMeshGradient(
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    // 1. Stable, cached star field (180 stars)
    val stars = remember {
        val random = Random(42) // Fixed seed for absolute position stability across recompositions
        List(180) {
            val isSig = it < 8 // 8 signature brighter stars
            Star(
                relX = random.nextFloat(),
                relY = random.nextFloat(),
                baseRadius = if (isSig) random.nextFloat() * 0.8f + 1.2f else random.nextFloat() * 0.6f + 0.6f,
                baseAlpha = if (isSig) random.nextFloat() * 0.3f + 0.6f else random.nextFloat() * 0.4f + 0.2f,
                twinklePhase = random.nextFloat() * 2f * Math.PI.toFloat(),
                twinkleSpeed = random.nextFloat() * 0.8f + 0.4f,
                isWarm = random.nextFloat() > 0.65f,
                isSignature = isSig
            )
        }
    }

    // 2. Infinite time progress animation for continuous star twinkling & 10s meteor cycles
    val infiniteTransition = rememberInfiniteTransition(label = "StellarAnimation")
    val animTimeSec by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "AnimTimeSec"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Pure #000000 deep space background
        drawRect(color = Color(0xFF000000))

        // 3. Meteor cycle timing calculations (10s cycle: 1.2s meteor traversal, 8.8s pause)
        val cycleSec = 10.0f
        val currentCycleProgress = (animTimeSec % cycleSec)
        val meteorDuration = 1.2f
        val isMeteorActive = currentCycleProgress < meteorDuration
        val meteorProgress = if (isMeteorActive) (currentCycleProgress / meteorDuration).coerceIn(0f, 1f) else -1f

        // Meteor Start and End Offsets (Smooth diagonal across 70-85% of screen)
        val startX = width * 0.15f
        val startY = height * 0.08f
        val endX = width * 0.88f
        val endY = height * 0.72f

        val currentMeteorX = startX + (endX - startX) * meteorProgress
        val currentMeteorY = startY + (endY - startY) * meteorProgress
        val currentMeteorHead = Offset(currentMeteorX, currentMeteorY)

        val proximityPx = 90.dp.toPx()

        // Draw stars
        stars.forEach { star ->
            val sx = star.relX * width
            val sy = star.relY * height

            // Asynchronous twinkle calculation
            val twinkle = sin(animTimeSec * star.twinkleSpeed + star.twinklePhase) * 0.25f + 0.75f
            var starAlpha = (star.baseAlpha * twinkle).coerceIn(0.1f, 1.0f)
            var starRadius = star.baseRadius

            // Nearby star brightening when meteor passes close (< 90dp)
            if (isMeteorActive) {
                val dx = sx - currentMeteorX
                val dy = sy - currentMeteorY
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < proximityPx) {
                    val boost = (1.0f - (dist / proximityPx)).coerceIn(0f, 1f) * 0.45f
                    starAlpha = (starAlpha + boost).coerceAtMost(1.0f)
                    starRadius = starRadius + boost * 0.8f
                }
            }

            val starColor = if (star.isWarm) Color(0xFFFFF8E7) else Color(0xFFFFFFFF)

            drawCircle(
                color = starColor.copy(alpha = starAlpha),
                radius = starRadius,
                center = Offset(sx, sy)
            )

            // Signature star subtle glow halo
            if (star.isSignature) {
                drawCircle(
                    color = starColor.copy(alpha = starAlpha * 0.2f),
                    radius = starRadius * 2.2f,
                    center = Offset(sx, sy)
                )
            }
        }

        // 4. Elegant Full-Screen Luxury Minimal Meteor
        if (isMeteorActive) {
            val tailLength = 220.dp.toPx()
            val dx = endX - startX
            val dy = endY - startY
            val angle = Math.atan2(dy.toDouble(), dx.toDouble()).toFloat()

            val tailX = currentMeteorX - tailLength * cos(angle.toDouble()).toFloat()
            val tailY = currentMeteorY - tailLength * sin(angle.toDouble()).toFloat()
            val meteorTailEnd = Offset(tailX, tailY)

            // Multi-layer tapered fading tail gradient
            drawLineWidth(
                start = meteorTailEnd,
                end = currentMeteorHead,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFFFFF8E7).copy(alpha = 0.25f),
                        Color(0xFFFFFFFF).copy(alpha = 0.85f)
                    ),
                    start = meteorTailEnd,
                    end = currentMeteorHead
                ),
                strokeWidth = 2.2f
            )

            // Soft outer halo around meteor head
            drawCircle(
                color = Color(0xFFFFF8E7).copy(alpha = 0.35f),
                radius = 7.dp.toPx(),
                center = currentMeteorHead
            )

            // Luminous meteor head core
            drawCircle(
                color = Color(0xFFFFFFFF).copy(alpha = 0.95f),
                radius = 2.2f.dp.toPx(),
                center = currentMeteorHead
            )

            // Subtle stardust trail particles behind head
            for (p in 1..4) {
                val pDist = p * 28.dp.toPx()
                val px = currentMeteorX - pDist * cos(angle.toDouble()).toFloat()
                val py = currentMeteorY - pDist * sin(angle.toDouble()).toFloat()
                val pAlpha = (0.5f - p * 0.1f).coerceAtLeast(0.05f)

                drawCircle(
                    color = Color(0xFFFFF8E7).copy(alpha = pAlpha),
                    radius = 0.8f.dp.toPx(),
                    center = Offset(px, py)
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLineWidth(
    start: Offset,
    end: Offset,
    brush: Brush,
    strokeWidth: Float
) {
    drawLine(
        brush = brush,
        start = start,
        end = end,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
}
