package com.axioloom.app.domain.clustering

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.axioloom.app.domain.model.EquationBlock
import com.axioloom.app.domain.model.InkPoint
import com.axioloom.app.domain.model.StrokeGeometry
import com.axioloom.app.domain.model.TimedStroke
import com.axioloom.app.domain.model.isFiniteOrdered
import com.axioloom.app.domain.model.unionBounds
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** A pure geometry result; useful for deterministic tests and for profiling clustering separately. */
data class StrokeCluster(val geometries: List<StrokeGeometry>, val bounds: Rect) {
    init { require(geometries.isNotEmpty()) }
    val strokeIds: Set<UUID> get() = geometries.mapTo(linkedSetOf()) { it.id }
}

data class ClusterIdentity(val id: UUID, val strokeIds: Set<UUID>) {
    init { require(strokeIds.isNotEmpty()) }
}

/** One-to-one old/new cluster identity assignment. Old identities cannot duplicate after a split. */
data class StableClusterMatch(val newClusterIndex: Int, val previousClusterIndex: Int, val jaccard: Double)

/**
 * 2D union-find clustering with a sweep-pruned candidate scan. All distances are in the same
 * workspace-pixel coordinate system as the stroke geometries. Connectors are symmetric by design.
 */
class SpatialBlockGrouper {
    data class GeometryResult(val medianHeightPx: Float, val clusters: List<StrokeCluster>)

    fun clusterGeometry(geometries: List<StrokeGeometry>): GeometryResult {
        require(geometries.map { it.id }.distinct().size == geometries.size) { "Stroke IDs must be unique" }
        if (geometries.isEmpty()) return GeometryResult(DEFAULT_MEDIAN_HEIGHT_PX, emptyList())
        val medianHeight = medianNonDotHeight(geometries)
        val ordered = geometries.sortedWith(compareBy<StrokeGeometry> { it.bounds.left }.thenBy { it.bounds.top }.thenBy { it.id })
        val unionFind = DisjointSet(ordered.size)
        val active = ArrayList<Int>()
        ordered.indices.forEach { currentIndex ->
            val current = ordered[currentIndex]
            val dx = DILATION_X * medianHeight
            val expired = active.iterator()
            while (expired.hasNext()) {
                val previous = ordered[expired.next()]
                if (previous.bounds.right + dx < current.bounds.left) expired.remove()
            }
            active.forEach { previousIndex ->
                if (connects(ordered[previousIndex].bounds, current.bounds, medianHeight)) {
                    unionFind.union(previousIndex, currentIndex)
                }
            }
            active += currentIndex
        }
        val components = linkedMapOf<Int, MutableList<StrokeGeometry>>()
        ordered.indices.forEach { index -> components.getOrPut(unionFind.find(index)) { mutableListOf() } += ordered[index] }
        val clusters = components.values.map { component ->
            val stableStrokes = component.sortedBy { it.id }
            val box = stableStrokes.drop(1).fold(stableStrokes.first().bounds) { acc, item -> acc.unionBounds(item.bounds) }
            StrokeCluster(stableStrokes, box)
        }.sortedWith(compareBy<StrokeCluster> { it.bounds.left }.thenBy { it.bounds.top }.thenBy { it.strokeIds.minOf { id -> id.toString() } })
        return GeometryResult(medianHeight, clusters)
    }

    /** Groups finished native strokes and preserves block IDs/payload only for unchanged clusters. */
    fun group(strokes: List<TimedStroke>, previousBlocks: List<EquationBlock> = emptyList()): List<EquationBlock> {
        val byId = strokes.associateBy { it.id }
        require(byId.size == strokes.size) { "Stroke IDs must be unique" }
        val result = clusterGeometry(strokes.map { it.geometry })
        val old = previousBlocks.map { ClusterIdentity(it.id, it.strokeIds) }
        val assignments = matchStableIds(result.clusters.map { it.strokeIds }, old).associate { it.newClusterIndex to old[it.previousClusterIndex] }
        return result.clusters.mapIndexed { index, cluster ->
            val identity = assignments[index]
            val prior = identity?.let { match -> previousBlocks.first { it.id == match.id } }
            val unchanged = prior != null && prior.strokeIds == cluster.strokeIds
            val orderedStrokes = cluster.geometries.map { byId.getValue(it.id) }
            val medianHeight = medianStrokeHeight(orderedStrokes, result.medianHeightPx)
            val anchor = findTerminalEquals(orderedStrokes.map { it.geometry }, result.medianHeightPx)
            if (unchanged) {
                prior!!.copy(strokes = orderedStrokes, bounds = cluster.bounds, medianStrokeHeight = medianHeight, resultAnchorPoint = anchor)
            } else {
                EquationBlock(
                    id = prior?.id ?: UUID.randomUUID(),
                    strokes = orderedStrokes,
                    bounds = cluster.bounds,
                    medianStrokeHeight = medianHeight,
                    resultAnchorPoint = anchor,
                    revision = (prior?.revision ?: -1L) + 1L
                )
            }
        }
    }

    private fun medianStrokeHeight(strokes: List<TimedStroke>, fallback: Float): Float =
        medianNonDotHeight(strokes.map { it.geometry }).takeIf { it > 0f } ?: fallback

    /** Public pure matcher: deterministic greedy maximum-Jaccard one-to-one assignment. */
    fun matchStableIds(newClusters: List<Set<UUID>>, previous: List<ClusterIdentity>): List<StableClusterMatch> {
        data class Candidate(val newIndex: Int, val oldIndex: Int, val score: Double, val intersection: Int)
        val candidates = buildList {
            newClusters.forEachIndexed { newIndex, newIds ->
                if (newIds.isNotEmpty()) previous.forEachIndexed { oldIndex, old ->
                    val common = newIds.count { it in old.strokeIds }
                    if (common > 0) add(Candidate(newIndex, oldIndex, common.toDouble() / (newIds.size + old.strokeIds.size - common), common))
                }
            }
        }.sortedWith(
            compareByDescending<Candidate> { it.score }
                .thenByDescending { it.intersection }
                .thenBy { previous[it.oldIndex].id.toString() }
                .thenBy { it.newIndex }
        )
        val usedNew = HashSet<Int>()
        val usedOld = HashSet<Int>()
        val matched = ArrayList<StableClusterMatch>()
        candidates.forEach { candidate ->
            if (usedNew.add(candidate.newIndex)) {
                if (usedOld.add(candidate.oldIndex)) matched += StableClusterMatch(candidate.newIndex, candidate.oldIndex, candidate.score)
                else usedNew.remove(candidate.newIndex)
            }
        }
        return matched.sortedBy { it.newClusterIndex }
    }

    private fun findTerminalEquals(strokes: List<StrokeGeometry>, medianHeight: Float): Offset? {
        if (strokes.size < 2) return null
        data class Bar(val geometry: StrokeGeometry, val bounds: Rect)
        val bars = strokes.mapNotNull { stroke ->
            val box = stroke.centerlineBounds
            val w = box.width
            val h = box.height
            if (w >= MIN_EQUALS_WIDTH_PX && (h <= MIN_EQUALS_HEIGHT_PX || w / h >= MIN_EQUALS_ASPECT_RATIO)) Bar(stroke, box) else null
        }.sortedWith(compareBy<Bar> { it.bounds.left }.thenBy { it.geometry.id })
        var best: Pair<Bar, Bar>? = null
        var bestRight = Float.NEGATIVE_INFINITY
        for (i in bars.indices) for (j in i + 1 until bars.size) {
            val first = bars[i]; val second = bars[j]
            val overlap = min(first.bounds.right, second.bounds.right) - max(first.bounds.left, second.bounds.left)
            val denominator = min(first.bounds.width, second.bounds.width)
            val overlapRatio = if (denominator > 0f) overlap / denominator else 0f
            val gap = verticalGap(first.bounds, second.bounds)
            if (overlapRatio >= MIN_EQUALS_OVERLAP && gap <= MAX_EQUALS_GAP_HEIGHTS * medianHeight) {
                val right = max(first.bounds.right, second.bounds.right)
                if (right > bestRight) { best = first to second; bestRight = right }
            }
        }
        return best?.let { (first, second) ->
            Offset(bestRight, (first.bounds.center.y + second.bounds.center.y) / 2f)
        }
    }

    private fun connects(a: Rect, b: Rect, h: Float): Boolean {
        if (!a.isFiniteOrdered() || !b.isFiniteOrdered()) return false
        if (a.left - DILATION_X * h <= b.right && a.right + DILATION_X * h >= b.left &&
            a.top - DILATION_Y * h <= b.bottom && a.bottom + DILATION_Y * h >= b.top) return true
        val overlap = min(a.right, b.right) - max(a.left, b.left)
        val minWidth = min(a.width, b.width)
        val overlapRatio = if (minWidth > 0f) overlap / minWidth else 0f
        val gap = verticalGap(a, b)
        return (overlapRatio >= STACK_OVERLAP && gap <= STACK_GAP_HEIGHTS * h) ||
            (overlapRatio >= DIACRITIC_OVERLAP && gap <= DIACRITIC_GAP_HEIGHTS * h)
    }

    private fun verticalGap(a: Rect, b: Rect): Float = when {
        a.bottom < b.top -> b.top - a.bottom
        b.bottom < a.top -> a.top - b.bottom
        else -> 0f
    }

    private fun medianNonDotHeight(geometries: List<StrokeGeometry>): Float {
        val heights = geometries.map { it.centerlineBounds.height }.filter { it >= NON_DOT_MIN_HEIGHT_PX }.sorted()
        if (heights.isEmpty()) return DEFAULT_MEDIAN_HEIGHT_PX
        val middle = heights.size / 2
        val median = if (heights.size % 2 == 0) (heights[middle - 1] + heights[middle]) / 2f else heights[middle]
        return median.coerceIn(MIN_MEDIAN_HEIGHT_PX, MAX_MEDIAN_HEIGHT_PX)
    }

    private class DisjointSet(size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = ByteArray(size)
        fun find(value: Int): Int {
            var node = value
            while (node != parent[node]) { parent[node] = parent[parent[node]]; node = parent[node] }
            return node
        }
        fun union(a: Int, b: Int) {
            var ra = find(a); var rb = find(b)
            if (ra == rb) return
            if (rank[ra] < rank[rb]) { val tmp = ra; ra = rb; rb = tmp }
            parent[rb] = ra
            if (rank[ra] == rank[rb]) rank[ra]++
        }
    }

    private companion object {
        const val DEFAULT_MEDIAN_HEIGHT_PX = 48f
        const val MIN_MEDIAN_HEIGHT_PX = 24f
        const val MAX_MEDIAN_HEIGHT_PX = 96f
        const val NON_DOT_MIN_HEIGHT_PX = 2f
        const val DILATION_X = 1.6f
        const val DILATION_Y = 0.85f
        const val STACK_OVERLAP = 0.35f
        const val STACK_GAP_HEIGHTS = 1.4f
        const val DIACRITIC_OVERLAP = 0.50f
        const val DIACRITIC_GAP_HEIGHTS = 1.2f
        const val MIN_EQUALS_OVERLAP = 0.65f
        const val MIN_EQUALS_ASPECT_RATIO = 2.5f
        const val MIN_EQUALS_WIDTH_PX = 4f
        const val MIN_EQUALS_HEIGHT_PX = 1f
        const val MAX_EQUALS_GAP_HEIGHTS = 0.8f
    }
}
