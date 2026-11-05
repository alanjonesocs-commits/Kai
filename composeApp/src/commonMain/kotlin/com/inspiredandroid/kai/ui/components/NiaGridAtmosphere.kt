package com.inspiredandroid.kai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * A restrained perspective signal-grid for NIA's conversation space.
 * Drawn behind content, with no pointer input or animation work on the UI thread.
 */
@Composable
fun NiaGridAtmosphere(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val horizon = height * 0.62f
        val signal = Color(0xFF62F5E5)

        // Fine star points above the horizon keep the upper field from feeling flat.
        val stars = listOf(
            0.08f to 0.09f, 0.21f to 0.17f, 0.39f to 0.08f, 0.58f to 0.14f,
            0.76f to 0.07f, 0.91f to 0.19f, 0.14f to 0.34f, 0.48f to 0.28f,
            0.69f to 0.36f, 0.86f to 0.31f, 0.31f to 0.43f, 0.96f to 0.48f,
        )
        stars.forEachIndexed { index, point ->
            drawCircle(
                color = signal.copy(alpha = if (index % 3 == 0) 0.24f else 0.12f),
                radius = if (index % 3 == 0) 1.6f else 1.0f,
                center = Offset(width * point.first, height * point.second),
            )
        }

        // Perspective rails converge on a single vanishing point.
        val vanishingPoint = Offset(width * 0.5f, horizon)
        for (index in -8..8) {
            val bottomX = width * (0.5f + index * 0.105f)
            drawLine(
                color = signal.copy(alpha = 0.075f),
                start = vanishingPoint,
                end = Offset(bottomX, height),
                strokeWidth = 1f,
            )
        }

        // The horizontal scan lines spread out towards the viewer.
        for (index in 1..11) {
            val progress = index / 11f
            val eased = progress * progress
            val y = horizon + (height - horizon) * eased
            drawLine(
                color = signal.copy(alpha = 0.035f + progress * 0.035f),
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1f,
            )
        }
    }
}
