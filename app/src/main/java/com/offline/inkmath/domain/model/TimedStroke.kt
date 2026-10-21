package com.offline.inkmath.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import java.util.Collections
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A point in workspace pixels; time is relative to the first input of this stroke. */
data class InkPoint(val x: Float, val y: Float, val elapsedTimeMillis: Long) {
    init {
        require(x.isFinite() && y.isFinite()) { "Ink coordinates must be finite" }
        require(elapsedTimeMillis >= 0L)
    }
    val offset: Offset get() = Offset(x, y)
}

/**
 * Immutable geometric projection of a native stroke. Also usable without JNI for geometry tests.
 * [bounds] includes the native mesh, while [centerlineBounds] excludes brush thickness.
 */
class StrokeGeometry(
    val id: UUID,
    points: List<InkPoint>,
    val bounds: Rect
) {
    val points: List<InkPoint> = Collections.unmodifiableList(ArrayList(points))
    val centerlineBounds: Rect
    val arcLength: Double

    init {
        require(this.points.isNotEmpty()) { "A completed stroke needs at least one input" }
        require(bounds.isFiniteOrdered()) { "Invalid stroke bounds" }
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        var length = 0.0
        this.points.forEachIndexed { index, point ->
            left = min(left, point.x); top = min(top, point.y)
            right = max(right, point.x); bottom = max(bottom, point.y)
            if (index > 0) {
                val previous = this.points[index - 1]
                require(point.elapsedTimeMillis >= previous.elapsedTimeMillis) { "Input times must be monotonic" }
                length += hypot(point.x.toDouble() - previous.x, point.y.toDouble() - previous.y)
            }
        }
        centerlineBounds = Rect(left, top, right, bottom)
        require(bounds.left <= left && bounds.top <= top && bounds.right >= right && bounds.bottom >= bottom) {
            "Bounds must enclose the centerline"
        }
        arcLength = length
    }
}

/**
 * A finished Jetpack Ink stroke with an immutable, cached geometric snapshot.
 *
 * Contract: inputs AND mesh are already in workspace coordinates, not viewport/screen coordinates.
 * A stroke ID identifies immutable geometry forever. Never re-use it for replacement ink.
 * [finishedAtMillis] is a monotonic workspace timestamp, not wall-clock time.
 */
class TimedStroke internal constructor(
    stroke: Stroke?,
    val finishedAtMillis: Long,
    val geometry: StrokeGeometry
) {
    val id: UUID get() = geometry.id
    val bounds: Rect get() = geometry.bounds
    val points: List<InkPoint> get() = geometry.points
    val centerlineBounds: Rect get() = geometry.centerlineBounds
    val stroke: Stroke get() = checkNotNull(_stroke) { "Native Stroke is not available in pure JVM environment" }
    private val _stroke: Stroke? = stroke

    fun translate(dx: Float, dy: Float): TimedStroke {
        if (dx == 0f && dy == 0f) return this
        val translatedPoints = points.map { InkPoint(it.x + dx, it.y + dy, it.elapsedTimeMillis) }
        val translatedBounds = Rect(bounds.left + dx, bounds.top + dy, bounds.right + dx, bounds.bottom + dy)
        val newGeom = StrokeGeometry(id, translatedPoints, translatedBounds)
        return TimedStroke(_stroke, finishedAtMillis, newGeom)
    }

    companion object {
        fun forTesting(
            geometry: StrokeGeometry,
            finishedAtMillis: Long = 0L
        ): TimedStroke = TimedStroke(null, finishedAtMillis, geometry)

        fun fromStroke(
            stroke: Stroke,
            finishedAtMillis: Long,
            id: UUID = UUID.randomUUID()
        ): TimedStroke {
            require(finishedAtMillis >= 0L)
            require(stroke.inputs.size > 0) { "Cannot commit an empty stroke" }
            val input = StrokeInput()
            val points = ArrayList<InkPoint>(stroke.inputs.size)
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            for (index in 0 until stroke.inputs.size) {
                stroke.inputs.populate(index, input)
                points += InkPoint(input.x, input.y, input.elapsedTimeMillis)
                left = min(left, input.x); top = min(top, input.y)
                right = max(right, input.x); bottom = max(bottom, input.y)
            }
            // Include the centerline even when a custom brush has an asymmetric/empty shape.
            val mesh = stroke.shape.computeBoundingBox()
            val bounds = if (mesh != null) {
                Rect(min(left, mesh.xMin), min(top, mesh.yMin), max(right, mesh.xMax), max(bottom, mesh.yMax))
            } else {
                val radius = stroke.brush.size / 2f
                Rect(left - radius, top - radius, right + radius, bottom + radius)
            }
            return TimedStroke(stroke, finishedAtMillis, StrokeGeometry(id, points, bounds))
        }
    }
}

internal fun Rect.isFiniteOrdered(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        left <= right && top <= bottom && width.isFinite() && height.isFinite()

internal fun Rect.unionBounds(other: Rect): Rect = Rect(
    min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom)
)

/** Inclusive comparisons deliberately allow point/dot/zero-height line bounds. */
internal fun Rect.touches(other: Rect): Boolean =
    left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top
