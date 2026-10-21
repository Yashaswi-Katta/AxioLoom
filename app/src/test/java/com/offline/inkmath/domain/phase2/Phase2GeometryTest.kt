package com.offline.inkmath.domain.phase2

import androidx.compose.ui.geometry.Rect
import com.offline.inkmath.domain.clustering.ClusterIdentity
import com.offline.inkmath.domain.clustering.SpatialBlockGrouper
import com.offline.inkmath.domain.gesture.ScratchOutDetector
import com.offline.inkmath.domain.model.InkPoint
import com.offline.inkmath.domain.model.StrokeGeometry
import com.offline.inkmath.ml.hmer.StrokeBitmapRasterizer
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2GeometryTest {
    private fun geometry(id: UUID, left: Float, top: Float, right: Float, bottom: Float, vararg points: Pair<Float, Float>) =
        StrokeGeometry(id, points.mapIndexed { index, (x, y) -> InkPoint(x, y, index.toLong()) }, Rect(left, top, right, bottom))

    @Test fun unionFindGroupsNearbyInkAndKeepsSeparatedExpressionsApart() {
        val first = geometry(UUID.randomUUID(), 0f, 0f, 12f, 24f, 0f to 24f, 12f to 0f)
        val second = geometry(UUID.randomUUID(), 20f, 0f, 32f, 24f, 20f to 0f, 32f to 24f)
        val distant = geometry(UUID.randomUUID(), 300f, 0f, 312f, 24f, 300f to 24f, 312f to 0f)
        val result = SpatialBlockGrouper().clusterGeometry(listOf(distant, second, first))
        assertEquals(2, result.clusters.size)
        assertEquals(setOf(first.id, second.id), result.clusters.first { first.id in it.strokeIds }.strokeIds)
        assertEquals(setOf(distant.id), result.clusters.last().strokeIds)
    }

    @Test fun verticalFractionLikeStackClustersWhenHorizontallyOverlapping() {
        val numerator = geometry(UUID.randomUUID(), 10f, 0f, 45f, 20f, 10f to 20f, 45f to 0f)
        val denominator = geometry(UUID.randomUUID(), 10f, 50f, 45f, 70f, 10f to 70f, 45f to 50f)
        val result = SpatialBlockGrouper().clusterGeometry(listOf(numerator, denominator))
        assertEquals(1, result.clusters.size)
    }

    @Test fun stableIdentityIsOneToOneOnSplitAndUsesBestJaccardOverlap() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val c = UUID.randomUUID()
        val oldId = UUID.randomUUID()
        val groups = listOf(setOf(a), setOf(b, c))
        val matched = SpatialBlockGrouper().matchStableIds(groups, listOf(ClusterIdentity(oldId, setOf(a, b, c))))
        assertEquals(1, matched.size)
        assertEquals(1, matched.single().newClusterIndex)
        assertEquals(oldId, listOf(ClusterIdentity(oldId, setOf(a, b, c)))[matched.single().previousClusterIndex].id)
    }

    @Test fun scratchHeuristicRequiresRepeatedMeaningfulHorizontalReversals() {
        val points = buildList {
            var time = 0L
            for (row in 0..8) {
                val y = row * 6f
                if (row % 2 == 0) { add(InkPoint(0f, y, time++)); add(InkPoint(100f, y, time++)) }
                else { add(InkPoint(100f, y, time++)); add(InkPoint(0f, y, time++)) }
            }
        }
        val geometry = StrokeGeometry(UUID.randomUUID(), points, Rect(0f, 0f, 100f, 48f))
        val metrics = ScratchOutDetector().measure(geometry)
        assertTrue(metrics.horizontalDirectionFlips >= 3)
        assertTrue(metrics.density >= 3.2)
        assertTrue(metrics.isOscillatoryScratch)
    }

    @Test fun lowDensityAndSubThresholdJitterAreNotScratch() {
        val points = listOf(InkPoint(0f, 0f, 0), InkPoint(100f, 0f, 1), InkPoint(98f, 1f, 2), InkPoint(100f, 2f, 3))
        val geometry = StrokeGeometry(UUID.randomUUID(), points, Rect(0f, 0f, 100f, 2f))
        assertFalse(ScratchOutDetector().measure(geometry).isOscillatoryScratch)
    }

    @Test fun rasterPlannerUsesHeightNormalizationAndWidthBound() {
        val rasterizer = StrokeBitmapRasterizer()
        val normal = rasterizer.planDimensions(Rect(0f, 0f, 200f, 40f))
        assertEquals(96, normal.contentHeight)
        assertEquals(32, normal.tensorHeight - normal.contentHeight)
        val wide = rasterizer.planDimensions(Rect(0f, 0f, 1000f, 100f))
        assertEquals(512, wide.contentWidth)
        assertEquals(0, wide.contentHeight % 16)
        assertEquals(wide.contentHeight + 32, wide.tensorHeight)
        assertTrue(wide.scale < normal.scale)
    }
}
