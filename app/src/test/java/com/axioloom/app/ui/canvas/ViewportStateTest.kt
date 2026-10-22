package com.axioloom.app.ui.canvas

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportStateTest {
    @Test
    fun worldAndScreenCoordinatesRoundTrip() {
        val viewport = ViewportState(initialScale = 2f, initialTranslation = Offset(13f, -7f))
        val world = Offset(41f, 28f)
        val roundTrip = viewport.screenToWorld(viewport.worldToScreen(world))
        assertEquals(world.x, roundTrip.x, 0.0001f)
        assertEquals(world.y, roundTrip.y, 0.0001f)
    }

    @Test
    fun pinchKeepsTheWorldPointUnderTheMovingCentroid() {
        val viewport = ViewportState(initialScale = 1f, initialTranslation = Offset.Zero)
        viewport.applyGesture(Offset(20f, 30f), Offset(35f, 45f), zoomFactor = 2f)
        assertEquals(2f, viewport.scale, 0.0001f)
        assertEquals(35f, viewport.worldToScreen(Offset(20f, 30f)).x, 0.0001f)
        assertEquals(45f, viewport.worldToScreen(Offset(20f, 30f)).y, 0.0001f)
    }
}
