package com.inspiredandroid.kai.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Visual phase used only by NIA's presentation layer. */
enum class NiaVisualState { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * NIA's paired identity: a live neural network linked to a beating heart.
 * This is presentation-only; the state is supplied by the existing chat/voice UI.
 */
@Composable
fun HeartBrainDuo(
    modifier: Modifier = Modifier,
    state: NiaVisualState = NiaVisualState.IDLE,
    isMini: Boolean = false,
    brainSize: Dp = if (isMini) 42.dp else 164.dp,
    heartSize: Dp = if (isMini) 28.dp else 94.dp,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HolographicBrain(size = brainSize, state = state)
        Canvas(
            modifier = Modifier
                .width(if (isMini) 2.dp else 3.dp)
                .height(if (isMini) 6.dp else 16.dp),
        ) {
            drawLine(
                color = Color(0xFF68E7F4).copy(alpha = 0.9f),
                start = Offset(size.width / 2f, 0f),
                end = Offset(size.width / 2f, size.height),
                strokeWidth = if (isMini) 2f else 3f,
            )
        }
        BeatingHeart(state = state, size = heartSize)
    }
}

@Composable
private fun BeatingHeart(state: NiaVisualState, size: Dp) {
    val bpm = when (state) {
        NiaVisualState.LISTENING -> 82
        NiaVisualState.THINKING -> 92
        NiaVisualState.SPEAKING -> 76
        NiaVisualState.IDLE -> 62
    }
    val cycleMillis = 60_000 / bpm
    val transition = rememberInfiniteTransition()
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = cycleMillis
                1f at 0
                1.15f at cycleMillis / 4
                1f at cycleMillis / 2
                1.08f at (cycleMillis * 3) / 4
                1f at cycleMillis
            },
            repeatMode = RepeatMode.Restart,
        ),
    )
    Canvas(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        val widthPx = this.size.width
        val heightPx = this.size.height
        val heart = Path().apply {
            moveTo(widthPx * 0.5f, heightPx * 0.86f)
            cubicTo(widthPx * 0.12f, heightPx * 0.59f, widthPx * 0.08f, heightPx * 0.22f, widthPx * 0.31f, heightPx * 0.16f)
            cubicTo(widthPx * 0.41f, heightPx * 0.12f, widthPx * 0.47f, heightPx * 0.22f, widthPx * 0.5f, heightPx * 0.31f)
            cubicTo(widthPx * 0.53f, heightPx * 0.22f, widthPx * 0.59f, heightPx * 0.12f, widthPx * 0.69f, heightPx * 0.16f)
            cubicTo(widthPx * 0.92f, heightPx * 0.22f, widthPx * 0.88f, heightPx * 0.59f, widthPx * 0.5f, heightPx * 0.86f)
            close()
        }
        drawPath(
            path = heart,
            color = Color(0xFFEC4899).copy(alpha = 0.22f),
            style = Stroke(width = widthPx * 0.12f),
        )
        drawPath(
            path = heart,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFFEC4899), Color(0xFFEF4444)),
            ),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.78f),
            radius = widthPx * 0.025f,
            center = Offset(widthPx * 0.36f, heightPx * 0.29f),
        )
    }
}

@Composable
fun HolographicBrain(
    modifier: Modifier = Modifier,
    size: Dp = 216.dp,
    state: NiaVisualState = NiaVisualState.IDLE,
) {
    val transition = rememberInfiniteTransition()
    val cycleMillis = when (state) {
        NiaVisualState.IDLE -> 3000
        NiaVisualState.LISTENING -> 1250
        NiaVisualState.THINKING -> 620
        NiaVisualState.SPEAKING -> 920
    }
    val pulse by transition.animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(cycleMillis, easing = FastOutSlowInEasing), RepeatMode.Restart),
    )
    Canvas(modifier.size(size)) {
        val s = minOf(this.size.width, this.size.height)
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val cyan = Color(0xFF63E9FF)
        val blue = Color(0xFF5595FF)
        val violet = Color(0xFFB49AFF)
        val stateColor = when (state) {
            NiaVisualState.IDLE -> cyan
            NiaVisualState.LISTENING -> Color(0xFF54F1FF)
            NiaVisualState.THINKING -> violet
            NiaVisualState.SPEAKING -> Color(0xFF9C8BFF)
        }

        // Holographic scan rings. Active phases breathe outward at their own cadence.
        for (i in 0..2) {
            val phase = (pulse + i / 3f) % 1f
            val activeRadius = if (state == NiaVisualState.IDLE) 0f else phase * 0.10f
            val alpha = if (state == NiaVisualState.IDLE) 0.08f - i * 0.018f else (1f - phase) * 0.19f
            drawCircle(
                stateColor.copy(alpha = alpha.coerceAtLeast(0.015f)),
                s * (0.34f + i * 0.035f + activeRadius),
                c,
                style = Stroke(s * (if (state == NiaVisualState.IDLE) 0.003f else 0.0045f)),
            )
        }

        // Twin hemispheres form a stylised anatomical silhouette.
        val left = Path().apply {
            moveTo(c.x, c.y - s * 0.25f)
            cubicTo(c.x - s * 0.18f, c.y - s * 0.32f, c.x - s * 0.35f, c.y - s * 0.16f, c.x - s * 0.32f, c.y + s * 0.02f)
            cubicTo(c.x - s * 0.34f, c.y + s * 0.20f, c.x - s * 0.18f, c.y + s * 0.28f, c.x, c.y + s * 0.23f)
            close()
        }
        val right = Path().apply {
            moveTo(c.x, c.y - s * 0.25f)
            cubicTo(c.x + s * 0.18f, c.y - s * 0.32f, c.x + s * 0.35f, c.y - s * 0.16f, c.x + s * 0.32f, c.y + s * 0.02f)
            cubicTo(c.x + s * 0.34f, c.y + s * 0.20f, c.x + s * 0.18f, c.y + s * 0.28f, c.x, c.y + s * 0.23f)
            close()
        }
        drawPath(left, stateColor.copy(alpha = 0.72f), style = Stroke(s * 0.012f))
        drawPath(right, blue.copy(alpha = if (state == NiaVisualState.IDLE) 0.72f else 0.9f), style = Stroke(s * 0.012f))

        // Curved folds create the holographic brain texture.
        val folds = listOf(
            -0.18f to -0.12f, -0.25f to 0.00f, -0.18f to 0.13f,
            -0.10f to -0.21f, -0.09f to 0.20f,
            0.18f to -0.12f, 0.25f to 0.00f, 0.18f to 0.13f,
            0.10f to -0.21f, 0.09f to 0.20f,
        )
        folds.forEach { (x, y) ->
            drawLine(
                cyan.copy(alpha = 0.30f),
                Offset(c.x, c.y + y * s * 0.35f),
                Offset(c.x + x * s, c.y + y * s),
                s * 0.005f,
            )
        }

        val nodes = listOf(
            -0.24f to -0.12f, -0.13f to -0.20f, -0.27f to 0.02f,
            -0.14f to 0.10f, -0.20f to 0.18f, -0.04f to -0.08f,
            0.24f to -0.12f, 0.13f to -0.20f, 0.27f to 0.02f,
            0.14f to 0.10f, 0.20f to 0.18f, 0.04f to -0.08f,
        )
        val links = listOf(
            0 to 1, 0 to 2, 1 to 5, 2 to 3, 3 to 4, 5 to 3,
            6 to 7, 6 to 8, 7 to 11, 8 to 9, 9 to 10, 11 to 9, 5 to 11,
        )
        links.forEach { (a, b) ->
            val p = nodes[a]
            val q = nodes[b]
            drawLine(
                cyan.copy(alpha = 0.24f),
                Offset(c.x + p.first * s, c.y + p.second * s),
                Offset(c.x + q.first * s, c.y + q.second * s),
                s * 0.004f,
            )
        }
        nodes.forEachIndexed { index, node ->
            val active = (1f - abs(index / nodes.size.toFloat() - pulse) * 2.8f).coerceIn(0f, 1f)
            val point = Offset(c.x + node.first * s, c.y + node.second * s)
            drawCircle(cyan.copy(alpha = 0.12f + active * 0.25f), s * (0.018f + active * 0.025f), point)
            drawCircle((if (index % 3 == 0) violet else cyan).copy(alpha = 0.38f + active * 0.62f), s * 0.009f, point)
        }
        drawLine(violet.copy(alpha = 0.45f), Offset(c.x, c.y - s * 0.23f), Offset(c.x, c.y + s * 0.22f), s * 0.004f)
        val scanY = c.y - s * 0.24f + s * 0.48f * pulse
        drawLine(stateColor.copy(alpha = 0.24f + pulse * 0.18f), Offset(c.x - s * 0.30f, scanY), Offset(c.x + s * 0.30f, scanY), s * 0.003f)
    }
}
