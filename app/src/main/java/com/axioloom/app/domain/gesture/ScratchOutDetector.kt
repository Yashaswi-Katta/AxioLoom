package com.axioloom.app.domain.gesture

import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.Intersection.intersects
import com.axioloom.app.domain.model.StrokeGeometry
import com.axioloom.app.domain.model.TimedStroke
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Geometry-only part makes the oscillation rule testable without initializing Ink JNI. */
data class ScratchMetrics(
    val width: Float,
    val height: Float,
    val diagonal: Double,
    val arcLength: Double,
    val horizontalDirectionFlips: Int,
    val density: Double
) {
    val isOscillatoryScratch: Boolean get() = diagonal > 0.0 && density >= MIN_PATH_DENSITY && horizontalDirectionFlips >= MIN_DIRECTION_FLIPS
    companion object { const val MIN_PATH_DENSITY = 3.2; const val MIN_DIRECTION_FLIPS = 3 }
}

data class ScratchDecision(
    val metrics: ScratchMetrics,
    val geometryQualified: Boolean,
    val intersectedStrokes: List<TimedStroke>
) {
    val isScratchOut: Boolean get() = geometryQualified && intersectedStrokes.isNotEmpty()
}

/**
 * Two-stage scratch detector. It first applies a centerline path-density/hysteresis test, then
 * verifies targets using native Ink mesh intersection (not AABB-only guesses). Meshes must share
 * workspace coordinates. The returned list never includes the scratch stroke itself.
 */
class ScratchOutDetector {
    fun measure(geometry: StrokeGeometry): ScratchMetrics {
        val box = geometry.centerlineBounds
        val width = box.width
        val height = box.height
        val diagonal = hypot(width.toDouble(), height.toDouble())
        val flips = countHorizontalFlips(geometry.points, width)
        return ScratchMetrics(width, height, diagonal, geometry.arcLength, flips, if (diagonal == 0.0) 0.0 else geometry.arcLength / diagonal)
    }

    fun detect(scratch: TimedStroke, targets: Collection<TimedStroke>): ScratchDecision {
        val metrics = measure(scratch.geometry)
        val qualified = metrics.isOscillatoryScratch
        if (!qualified) return ScratchDecision(metrics, false, emptyList())
        val hits = targets.asSequence()
            .filter { it.id != scratch.id }
            .filter { it.bounds.touchesForScratch(scratch.bounds) }
            .filter { scratch.stroke.shape.intersects(it.stroke.shape, AffineTransform.IDENTITY, AffineTransform.IDENTITY) }
            .sortedBy { it.finishedAtMillis }
            .toList()
        return ScratchDecision(metrics, true, hits)
    }

    private fun countHorizontalFlips(points: List<com.axioloom.app.domain.model.InkPoint>, width: Float): Int {
        if (points.size < 3 || width <= 0f) return 0
        val threshold = MIN_REVERSAL_FRACTION * width
        var direction = 0
        var reversalTravel = 0f
        var flips = 0
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            if (dx == 0f) continue
            val sign = if (dx > 0f) 1 else -1
            if (direction == 0) { direction = sign; continue }
            if (sign == direction) {
                reversalTravel = 0f
            } else {
                reversalTravel += abs(dx)
                if (reversalTravel >= threshold) {
                    flips++
                    direction = sign
                    reversalTravel = 0f
                }
            }
        }
        return flips
    }

    private fun androidx.compose.ui.geometry.Rect.touchesForScratch(other: androidx.compose.ui.geometry.Rect): Boolean =
        left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top

    private companion object { const val MIN_REVERSAL_FRACTION = 0.25f }
}
