package com.inspiredandroid.kai.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
fun HolographicBrain(modifier: Modifier = Modifier, size: Dp = 216.dp) {
    val transition = rememberInfiniteTransition()
    val pulse by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Restart),
    )
    Canvas(modifier.size(size)) {
        val s = minOf(this.size.width, this.size.height)
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val cyan = Color(0xFF63E9FF)
        val blue = Color(0xFF5595FF)
        val violet = Color(0xFFB49AFF)

        // Holographic scan rings.
        for (i in 0..2) drawCircle(
            cyan.copy(alpha = 0.09f - i * 0.02f),
            s * (0.34f + i * 0.055f), c, style = Stroke(s * 0.003f),
        )

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
        drawPath(left, cyan.copy(alpha = 0.72f), style = Stroke(s * 0.012f))
        drawPath(right, blue.copy(alpha = 0.72f), style = Stroke(s * 0.012f))

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
        val links = listOf(0 to 1, 0 to 2, 1 to 5, 2 to 3, 3 to 4, 5 to 3,
            6 to 7, 6 to 8, 7 to 11, 8 to 9, 9 to 10, 11 to 9, 5 to 11)
        links.forEach { (a, b) ->
            val p = nodes[a]; val q = nodes[b]
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
        drawLine(cyan.copy(alpha = 0.24f), Offset(c.x - s * 0.30f, scanY), Offset(c.x + s * 0.30f, scanY), s * 0.003f)
    }
}
