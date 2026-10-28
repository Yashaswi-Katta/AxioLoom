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
            .filter { target ->
                if (scratch.hasNativeStroke && target.hasNativeStroke) {
                    runCatching {
                        scratch.stroke.shape.intersects(target.stroke.shape, AffineTransform.IDENTITY, AffineTransform.IDENTITY)
                    }.getOrDefault(false) || polylinesIntersect(scratch.points, target.points)
                } else {
                    polylinesIntersect(scratch.points, target.points)
                }
            }
            .sortedBy { it.finishedAtMillis }
            .toList()
        return ScratchDecision(metrics, true, hits)
    }

    private fun polylinesIntersect(pts1: List<com.axioloom.app.domain.model.InkPoint>, pts2: List<com.axioloom.app.domain.model.InkPoint>): Boolean {
        if (pts1.size < 2 || pts2.size < 2) return false
        for (i in 0 until pts1.size - 1) {
            val p1 = pts1[i]
            val p2 = pts1[i + 1]
            for (j in 0 until pts2.size - 1) {
                val p3 = pts2[j]
                val p4 = pts2[j + 1]
                if (segmentsIntersect(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y, p4.x, p4.y)) return true
            }
        }
        return false
    }

    private fun segmentsIntersect(
        x1: Float, y1: Float, x2: Float, y2: Float,
        x3: Float, y3: Float, x4: Float, y4: Float
    ): Boolean {
        fun ccw(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float {
            return (cy - ay) * (bx - ax) - (by - ay) * (cx - ax)
        }
        fun onSegment(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float): Boolean {
            return qx <= maxOf(px, rx) && qx >= minOf(px, rx) &&
                   qy <= maxOf(py, ry) && qy >= minOf(py, ry)
        }
        val ccw1 = ccw(x1, y1, x2, y2, x3, y3)
        val ccw2 = ccw(x1, y1, x2, y2, x4, y4)
        val ccw3 = ccw(x3, y3, x4, y4, x1, y1)
        val ccw4 = ccw(x3, y3, x4, y4, x2, y2)
        if (((ccw1 > 0f && ccw2 < 0f) || (ccw1 < 0f && ccw2 > 0f)) &&
            ((ccw3 > 0f && ccw4 < 0f) || (ccw3 < 0f && ccw4 > 0f))) return true

        if (kotlin.math.abs(ccw1) < 1e-5f && onSegment(x1, y1, x3, y3, x2, y2)) return true
        if (kotlin.math.abs(ccw2) < 1e-5f && onSegment(x1, y1, x4, y4, x2, y2)) return true
        if (kotlin.math.abs(ccw3) < 1e-5f && onSegment(x3, y3, x1, y1, x4, y4)) return true
        if (kotlin.math.abs(ccw4) < 1e-5f && onSegment(x3, y3, x2, y2, x4, y4)) return true
        return false
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
