package com.axioloom.app.ui.canvas

import android.graphics.Matrix
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min

/** Shared workspace-to-screen transform used by both wet and dry ink layers. */
@Stable
class ViewportState(
    initialScale: Float = 1f,
    initialTranslation: Offset = Offset.Zero
) {
    var scale by mutableFloatStateOf(initialScale.coerceIn(MIN_SCALE, MAX_SCALE))
        private set
    var translation by mutableStateOf(initialTranslation)
        private set

    fun worldToScreen(point: Offset): Offset = Offset(
        translation.x + point.x * scale,
        translation.y + point.y * scale
    )

    fun screenToWorld(point: Offset): Offset = Offset(
        (point.x - translation.x) / scale,
        (point.y - translation.y) / scale
    )

    fun panBy(delta: Offset) {
        translation += delta
    }

    /** Zooms around the previous centroid while moving that anchor to the current centroid. */
    fun applyGesture(previousCentroid: Offset, currentCentroid: Offset, zoomFactor: Float) {
        if (!zoomFactor.isFinite() || zoomFactor <= 0f) return
        val worldAnchor = screenToWorld(previousCentroid)
        scale = (scale * zoomFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        translation = Offset(
            currentCentroid.x - worldAnchor.x * scale,
            currentCentroid.y - worldAnchor.y * scale
        )
    }

    fun reset() {
        scale = 1f
        translation = Offset.Zero
    }

    fun visibleWorldRect(screenWidthPx: Float, screenHeightPx: Float, marginWorldPx: Float = 64f): Rect {
        val topLeft = screenToWorld(Offset(0f, 0f))
        val bottomRight = screenToWorld(Offset(screenWidthPx, screenHeightPx))
        return Rect(
            min(topLeft.x, bottomRight.x) - marginWorldPx,
            min(topLeft.y, bottomRight.y) - marginWorldPx,
            max(topLeft.x, bottomRight.x) + marginWorldPx,
            max(topLeft.y, bottomRight.y) + marginWorldPx
        )
    }

    /** Matrix mapping stored workspace coordinates to the screen-space wet/dry canvas. */
    fun worldToScreenMatrix(): Matrix = Matrix().apply {
        setValues(floatArrayOf(
            scale, 0f, translation.x,
            0f, scale, translation.y,
            0f, 0f, 1f
        ))
    }

    /** Maps raw view-coordinate MotionEvents into the workspace coordinate system. */
    fun screenToWorldMatrix(): Matrix = Matrix().apply {
        val inverseScale = 1f / scale
        setValues(floatArrayOf(
            inverseScale, 0f, -translation.x * inverseScale,
            0f, inverseScale, -translation.y * inverseScale,
            0f, 0f, 1f
        ))
    }

    companion object {
        const val MIN_SCALE = 0.35f
        const val MAX_SCALE = 5f
    }
}
