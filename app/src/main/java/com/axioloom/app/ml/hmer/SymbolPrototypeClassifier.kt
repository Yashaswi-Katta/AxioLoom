package com.axioloom.app.ml.hmer

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.axioloom.app.domain.model.TimedStroke
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.*

/**
 * 2D point normalized to the unit square [0, 1]^2.
 */
data class NormalizedPoint(val x: Float, val y: Float)

/**
 * A gesture prototype representing a canonical handwritten mathematical symbol.
 */
data class SymbolPrototype(
    val label: String,
    val latex: String,
    val strokeCount: Int,
    val points: List<NormalizedPoint>,
    val isCustom: Boolean = false
)

data class ClassificationMatch(
    val label: String,
    val latex: String,
    val score: Float
)

data class ExpressionRecognitionResult(
    val latex: String,
    val alternatives: List<String> = emptyList()
)

/**
 * High-precision mathematical symbol classifier combining:
 * 1. Deterministic geometric rule classification for unambiguous operators (+, -, =, *, /, 1, 0, .)
 * 2. Elastic DTW + Euclidean Distance matching across all stroke permutations
 * 3. Topological multi-stroke clustering and spatial superscript/number assembly.
 */
class SymbolPrototypeClassifier(private val context: Context? = null) {

    private val prototypes = mutableListOf<SymbolPrototype>()
    private val lock = Any()

    init {
        loadBuiltInPrototypes()
        context?.let { loadCustomPrototypes(it) }
    }

    // ------------------------------------------------------------------------
    // Resampling & Normalization (N = 32 points)
    // ------------------------------------------------------------------------

    companion object {
        const val TARGET_POINTS = 32
        const val PROTOTYPES_FILE_NAME = "user_symbol_prototypes.json"

        fun resamplePolyline(points: List<Offset>, targetCount: Int): List<Offset> {
            if (points.isEmpty()) return List(targetCount) { Offset.Zero }
            if (points.size == 1 || targetCount <= 1) return List(targetCount) { points.first() }

            val distances = FloatArray(points.size)
            var totalLength = 0f
            for (i in 1 until points.size) {
                val seg = hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
                totalLength += seg
                distances[i] = totalLength
            }

            if (totalLength < 1e-4f) {
                return List(targetCount) { points.first() }
            }

            val step = totalLength / (targetCount - 1).toFloat()
            val result = ArrayList<Offset>(targetCount)
            result.add(points.first())

            var segIdx = 0
            for (i in 1 until targetCount - 1) {
                val targetDist = i * step
                while (segIdx < distances.size - 2 && distances[segIdx + 1] < targetDist) {
                    segIdx++
                }
                val segStartDist = distances[segIdx]
                val segEndDist = distances[segIdx + 1]
                val segSpan = segEndDist - segStartDist
                val t = if (segSpan > 1e-5f) (targetDist - segStartDist) / segSpan else 0f

                val p0 = points[segIdx]
                val p1 = points[segIdx + 1]
                val interpolated = Offset(
                    p0.x + t * (p1.x - p0.x),
                    p0.y + t * (p1.y - p0.y)
                )
                result.add(interpolated)
            }
            result.add(points.last())
            return result
        }

        fun processStrokes(strokePolylines: List<List<Offset>>): List<NormalizedPoint> {
            val validStrokes = strokePolylines.filter { it.isNotEmpty() }
            if (validStrokes.isEmpty()) {
                return List(TARGET_POINTS) { NormalizedPoint(0.5f, 0.5f) }
            }

            val strokeCount = validStrokes.size
            val resampledPoints = mutableListOf<Offset>()

            if (strokeCount == 1) {
                resampledPoints.addAll(resamplePolyline(validStrokes[0], TARGET_POINTS))
            } else {
                val lengths = validStrokes.map { stroke ->
                    var len = 0f
                    for (i in 1 until stroke.size) {
                        len += hypot(stroke[i].x - stroke[i - 1].x, stroke[i].y - stroke[i - 1].y)
                    }
                    max(len, 1e-4f)
                }
                val totalLen = lengths.sum()

                var remaining = TARGET_POINTS
                val pointCounts = IntArray(strokeCount)
                for (i in 0 until strokeCount) {
                    val count = if (i == strokeCount - 1) {
                        remaining
                    } else {
                        val allocated = max(2, round(TARGET_POINTS * (lengths[i] / totalLen)).toInt())
                        min(allocated, remaining - (strokeCount - 1 - i) * 2)
                    }
                    pointCounts[i] = count
                    remaining -= count
                }

                for (i in 0 until strokeCount) {
                    resampledPoints.addAll(resamplePolyline(validStrokes[i], pointCounts[i]))
                }
            }

            var minX = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY

            for (p in resampledPoints) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }

            val width = max(maxX - minX, 1e-4f)
            val height = max(maxY - minY, 1e-4f)
            val scale = max(width, height)

            val offsetX = (1f - (width / scale)) / 2f
            val offsetY = (1f - (height / scale)) / 2f

            return resampledPoints.map { p ->
                NormalizedPoint(
                    x = ((p.x - minX) / scale) + offsetX,
                    y = ((p.y - minY) / scale) + offsetY
                )
            }
        }

        private fun boundsOf(pts: List<Offset>): Rect {
            if (pts.isEmpty()) return Rect.Zero
            var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
            var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
            for (p in pts) {
                l = min(l, p.x); t = min(t, p.y)
                r = max(r, p.x); b = max(b, p.y)
            }
            return Rect(l, t, r, b)
        }

        private fun arcLengthOf(pts: List<Offset>): Float {
            var len = 0f
            for (i in 1 until pts.size) {
                len += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
            }
            return len
        }

        private fun segmentsIntersect(p1: Offset, p2: Offset, q1: Offset, q2: Offset): Boolean {
            fun ccw(a: Offset, b: Offset, c: Offset): Float =
                (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

            val d1 = ccw(p1, p2, q1)
            val d2 = ccw(p1, p2, q2)
            val d3 = ccw(q1, q2, p1)
            val d4 = ccw(q1, q2, p2)

            return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
                    ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
        }

        private fun anySegmentsIntersect(s1: List<Offset>, s2: List<Offset>): Boolean {
            val step1 = max(1, s1.size / 10)
            val step2 = max(1, s2.size / 10)
            for (i in 0 until s1.size - 1 step step1) {
                val p1 = s1[i]
                val p2 = s1[min(i + step1, s1.size - 1)]
                for (j in 0 until s2.size - 1 step step2) {
                    val q1 = s2[j]
                    val q2 = s2[min(j + step2, s2.size - 1)]
                    if (segmentsIntersect(p1, p2, q1, q2)) return true
                }
            }
            return false
        }
    }

    // ------------------------------------------------------------------------
    // Deterministic Geometric Rule Classifier
    // ------------------------------------------------------------------------

    private fun classifyByGeometry(strokes: List<List<Offset>>): ClassificationMatch? {
        val valid = strokes.filter { it.isNotEmpty() }
        if (valid.isEmpty()) return null
        val count = valid.size

        if (count == 2) {
            val s1 = valid[0]
            val s2 = valid[1]
            val b1 = boundsOf(s1)
            val b2 = boundsOf(s2)

            val w1 = b1.width; val h1 = b1.height
            val w2 = b2.width; val h2 = b2.height

            // 1. Plus Sign (+): one vertical stroke, one horizontal stroke crossing near centers
            val s1Vert = h1 > w1 * 1.25f
            val s1Horiz = w1 > h1 * 1.25f
            val s2Vert = h2 > w2 * 1.25f
            val s2Horiz = w2 > h2 * 1.25f

            val bVert = if (s1Vert) b1 else b2
            val bHoriz = if (s1Vert) b2 else b1
            if ((s1Vert && s2Horiz) || (s1Horiz && s2Vert)) {
                val centerDistX = abs(b1.center.x - b2.center.x)
                val centerDistY = abs(b1.center.y - b2.center.y)
                val maxDim = max(max(w1, h1), max(w2, h2))
                if (centerDistX <= maxDim * 0.45f && centerDistY <= maxDim * 0.45f || anySegmentsIntersect(s1, s2)) {
                    if (bHoriz.center.y < bVert.top + bVert.height * 0.42f) {
                        return ClassificationMatch("t", "t", 0.005f)
                    }
                    return ClassificationMatch("+", "+", 0.001f)
                }
            }

            // 2. Equals Sign (=): two horizontal bars, stacked vertically, horizontal overlap >= 45%
            if (w1 > h1 * 1.15f && w2 > h2 * 1.15f) {
                val overlapX = min(b1.right, b2.right) - max(b1.left, b2.left)
                val minW = min(w1, w2)
                if (minW > 4f && overlapX / minW >= 0.45f) {
                    val gapY = abs(b1.center.y - b2.center.y)
                    if (gapY > 2f && gapY <= max(w1, w2) * 1.2f && !anySegmentsIntersect(s1, s2)) {
                        return ClassificationMatch("=", "=", 0.001f)
                    }
                }
            }

            // 3. Multiplication Cross (\times) vs Variable 'x': two crossing diagonal strokes
            if (anySegmentsIntersect(s1, s2)) {
                val dx1 = s1.last().x - s1.first().x; val dy1 = s1.last().y - s1.first().y
                val dx2 = s2.last().x - s2.first().x; val dy2 = s2.last().y - s2.first().y
                val slope1 = dy1 / (abs(dx1) + 1e-4f)
                val slope2 = dy2 / (abs(dx2) + 1e-4f)
                if ((slope1 > 0.15f && slope2 < -0.15f) || (slope1 < -0.15f && slope2 > 0.15f)) {
                    val b1 = boundsOf(s1); val b2 = boundsOf(s2)
                    val totalW = max(b1.right, b2.right) - min(b1.left, b2.left)
                    val totalH = max(b1.bottom, b2.bottom) - min(b1.top, b2.top)
                    val aspect = totalH / (totalW + 1e-4f)
                    return if (aspect >= 0.9f) {
                        ClassificationMatch("x", "x", 0.01f)
                    } else {
                        ClassificationMatch("\\times", "\\times", 0.015f)
                    }
                }
            }
        } else if (count == 1) {
            val s = valid[0]
            val b = boundsOf(s)
            val w = b.width; val h = b.height
            val dx = abs(s.last().x - s.first().x)
            val dy = abs(s.last().y - s.first().y)
            val startEndDist = hypot(s.last().x - s.first().x, s.last().y - s.first().y)
            val arcLen = arcLengthOf(s)

            // Dot (.)
            if (max(w, h) < 14f || arcLen < 16f) {
                return ClassificationMatch(".", ".", 0.005f)
            }

            // Minus (-)
            if (w > h * 2.0f && dx > dy * 1.8f && w >= 12f) {
                return ClassificationMatch("-", "-", 0.001f)
            }

            // One (1)
            if (h > w * 2.3f && dy > dx * 2.0f && h >= 16f) {
                return ClassificationMatch("1", "1", 0.001f)
            }

            // Zero (0): closed loop with balanced aspect ratio
            if (startEndDist < max(w, h) * 0.35f && arcLen >= max(w, h) * 2.1f && min(w, h) >= 12f) {
                if (w / max(h, 1e-4f) in 0.40f..2.4f) {
                    return ClassificationMatch("0", "0", 0.01f)
                }
            }

            // Slash (/)
            if (h in (w * 0.45f)..(w * 3.5f) && min(w, h) >= 16f) {
                val sx = s.first().x; val sy = s.first().y
                val ex = s.last().x; val ey = s.last().y
                val isSlash = (ex > sx + 8f && sy > ey + 8f) || (sx > ex + 8f && ey > sy + 8f)
                if (isSlash && arcLen < hypot(ex - sx, ey - sy) * 1.4f) {
                    return ClassificationMatch("/", "/", 0.02f)
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------------
    // Elastic DTW Matching
    // ------------------------------------------------------------------------

    private fun dtwDistance(
        a: List<NormalizedPoint>,
        b: List<NormalizedPoint>,
        window: Int = 8
    ): Float {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { FloatArray(m + 1) { Float.POSITIVE_INFINITY } }
        dp[0][0] = 0f

        for (i in 1..n) {
            val minJ = max(1, i - window)
            val maxJ = min(m, i + window)
            for (j in minJ..maxJ) {
                val p1 = a[i - 1]
                val p2 = b[j - 1]
                val cost = hypot(p1.x - p2.x, p1.y - p2.y)
                val prev = min(dp[i - 1][j], min(dp[i][j - 1], dp[i - 1][j - 1]))
                if (prev.isFinite()) {
                    dp[i][j] = cost + prev
                }
            }
        }

        val dtwScore = if (dp[n][m].isFinite()) dp[n][m] / n.toFloat() else 8f

        var eucSum = 0f
        val len = min(n, m)
        for (i in 0 until len) {
            eucSum += hypot(a[i].x - b[i].x, a[i].y - b[i].y)
        }
        val eucScore = eucSum / len.toFloat()

        return (0.6f * dtwScore) + (0.4f * eucScore)
    }

    /**
     * Classifies a list of stroke polylines against known prototypes.
     * Evaluates geometric signatures first, then all 8 permutations for multi-stroke inputs.
     */
    fun classifyStrokes(strokePolylines: List<List<Offset>>): List<ClassificationMatch> = synchronized(lock) {
        val validStrokes = strokePolylines.filter { it.isNotEmpty() }
        if (validStrokes.isEmpty()) return emptyList()

        // Prioritize custom prototypes if DTW distance < 0.12f
        val customProtos = prototypes.filter { it.isCustom && it.strokeCount == validStrokes.size }
        if (customProtos.isNotEmpty()) {
            val strokeCount = validStrokes.size
            val variations = mutableListOf<List<List<Offset>>>()
            variations.add(validStrokes)
            if (strokeCount == 2) {
                val s0 = validStrokes[0]; val s1 = validStrokes[1]
                variations.add(listOf(s1, s0))
                variations.add(listOf(s0.reversed(), s1))
                variations.add(listOf(s0, s1.reversed()))
                variations.add(listOf(s0.reversed(), s1.reversed()))
            } else if (strokeCount == 1) {
                variations.add(listOf(validStrokes[0].reversed()))
            }
            val processedVariations = variations.map { processStrokes(it) }
            val customMatches = mutableListOf<ClassificationMatch>()
            for (proto in customProtos) {
                var bestScore = Float.POSITIVE_INFINITY
                for (cand in processedVariations) {
                    val dist = dtwDistance(cand, proto.points)
                    if (dist < bestScore) bestScore = dist
                }
                customMatches.add(ClassificationMatch(proto.label, proto.latex, bestScore))
            }
            val bestCustom = customMatches.minByOrNull { it.score }
            if (bestCustom != null && bestCustom.score < 0.12f) {
                return customMatches.sortedBy { it.score }
            }
        }

        val ruleMatch = classifyByGeometry(validStrokes)
        if (ruleMatch != null) {
            if (ruleMatch.label == "x") {
                return listOf(ruleMatch, ClassificationMatch("\\times", "\\times", 0.015f))
            } else if (ruleMatch.label == "\\times") {
                return listOf(ruleMatch, ClassificationMatch("x", "x", 0.02f))
            } else if (ruleMatch.label == "t") {
                return listOf(ruleMatch, ClassificationMatch("+", "+", 0.02f))
            } else if (ruleMatch.label == "+") {
                return listOf(ruleMatch, ClassificationMatch("t", "t", 0.02f))
            }
            return listOf(ruleMatch)
        }

        val strokeCount = validStrokes.size

        val variations = mutableListOf<List<List<Offset>>>()
        variations.add(validStrokes)
        if (strokeCount == 2) {
            val s0 = validStrokes[0]; val s1 = validStrokes[1]
            val s0r = s0.reversed(); val s1r = s1.reversed()
            variations.add(listOf(s1, s0))
            variations.add(listOf(s0r, s1))
            variations.add(listOf(s0, s1r))
            variations.add(listOf(s0r, s1r))
            variations.add(listOf(s1r, s0))
            variations.add(listOf(s1, s0r))
            variations.add(listOf(s1r, s0r))
        } else if (strokeCount == 1) {
            variations.add(listOf(validStrokes[0].reversed()))
        }

        val processedVariations = variations.map { processStrokes(it) }
        val matches = mutableListOf<ClassificationMatch>()

        for (proto in prototypes) {
            var bestScore = Float.POSITIVE_INFINITY

            for (cand in processedVariations) {
                val dist = dtwDistance(cand, proto.points)
                val strokeCountDiff = abs(strokeCount - proto.strokeCount)
                val strokePenalty = strokeCountDiff * 0.25f
                val totalScore = dist + strokePenalty - (if (proto.isCustom) 0.05f else 0f)

                if (totalScore < bestScore) {
                    bestScore = totalScore
                }
            }

            matches.add(ClassificationMatch(proto.label, proto.latex, bestScore))
        }

        return matches.sortedBy { it.score }
    }

    fun classify(timedStrokes: List<TimedStroke>): ClassificationMatch? {
        val polylines = timedStrokes.map { stroke -> stroke.points.map { it.offset } }
        return classifyStrokes(polylines).firstOrNull()
    }

    /**
     * High-level expression recognizer: clusters strokes topologically,
     * classifies each symbol cluster, and formats into combined mathematical LaTeX.
     */
    fun recognizeExpression(timedStrokes: List<TimedStroke>): ExpressionRecognitionResult? {
        if (timedStrokes.isEmpty()) return null

        // Detect horizontal fraction bars
        if (timedStrokes.size >= 3) {
            val potentialBar = timedStrokes.firstOrNull { stroke ->
                val b = stroke.bounds
                val pts = stroke.points.map { it.offset }
                val dx = abs(pts.last().x - pts.first().x)
                val dy = abs(pts.last().y - pts.first().y)
                b.width >= 24f && b.width / max(b.height, 1f) >= 2.5f && dy < dx * 0.35f
            }
            if (potentialBar != null) {
                val barBounds = potentialBar.bounds
                val barCenterY = barBounds.center.y
                val otherStrokes = timedStrokes.filter { it != potentialBar }

                val numStrokes = otherStrokes.filter {
                    it.bounds.bottom <= barCenterY + 4f &&
                    it.bounds.right >= barBounds.left - 10f &&
                    it.bounds.left <= barBounds.right + 10f
                }
                val denStrokes = otherStrokes.filter {
                    it.bounds.top >= barCenterY - 4f &&
                    it.bounds.right >= barBounds.left - 10f &&
                    it.bounds.left <= barBounds.right + 10f
                }

                if (numStrokes.isNotEmpty() && denStrokes.isNotEmpty() &&
                    (numStrokes.size + denStrokes.size + 1 == timedStrokes.size)) {
                    val numRec = recognizeExpression(numStrokes)
                    val denRec = recognizeExpression(denStrokes)
                    if (numRec != null && denRec != null) {
                        val fracLatex = "\\frac{${numRec.latex}}{${denRec.latex}}"
                        val alts = mutableListOf<String>()
                        alts.addAll(numRec.alternatives)
                        alts.addAll(denRec.alternatives)
                        return ExpressionRecognitionResult(fracLatex, alts.distinct().take(4))
                    }
                }
            }
        }

        val clusters = clusterStrokesHorizontally(timedStrokes)
        if (clusters.isEmpty()) return null

        data class RecognizedToken(
            val latex: String,
            val bounds: Rect,
            val alternatives: List<String>
        )

        val tokens = mutableListOf<RecognizedToken>()
        val allAlternatives = mutableListOf<String>()

        for (cluster in clusters) {
            val polylines = cluster.map { stroke -> stroke.points.map { it.offset } }
            val cLeft = cluster.minOf { it.bounds.left }
            val cRight = cluster.maxOf { it.bounds.right }
            val cTop = cluster.minOf { it.bounds.top }
            val cBottom = cluster.maxOf { it.bounds.bottom }
            val clusterBounds = Rect(cLeft, cTop, cRight, cBottom)

            val matches = classifyStrokes(polylines)
            if (matches.isNotEmpty()) {
                val top = matches.first()
                tokens.add(RecognizedToken(top.latex, clusterBounds, matches.drop(1).take(3).map { it.latex }))
                matches.take(3).forEach { m ->
                    if (m.latex != top.latex && m.latex !in allAlternatives) {
                        allAlternatives.add(m.latex)
                    }
                }
            }
        }

        if (tokens.isEmpty()) return null

        fun formatTokens(tokenList: List<RecognizedToken>): String {
            val sb = StringBuilder()
            var idx = 0
            while (idx < tokenList.size) {
                val cur = tokenList[idx]
                val isDigit = cur.latex.all { it in '0'..'9' }

                if (cur.latex == "\\sqrt") {
                    if (idx > 0) sb.append(" ")
                    if (idx + 1 < tokenList.size) {
                        val next = tokenList[idx + 1]
                        sb.append("\\sqrt{").append(next.latex).append("}")
                        idx += 2
                        continue
                    } else {
                        sb.append("\\sqrt{}")
                        idx++
                        continue
                    }
                }

                if (idx > 0) {
                    val prev = tokenList[idx - 1]
                    val prevIsDigit = prev.latex.all { it in '0'..'9' }

                    // Check for superscript (elevated and smaller)
                    val isElevated = cur.bounds.center.y < prev.bounds.top + prev.bounds.height * 0.40f &&
                            cur.bounds.left >= prev.bounds.right - prev.bounds.width * 0.25f

                    if (isElevated) {
                        sb.append("^{").append(cur.latex).append("}")
                        idx++
                        continue
                    }

                    // If both are digits and tightly spaced, concatenate without space
                    val tightGap = cur.bounds.left - prev.bounds.right
                    if (isDigit && prevIsDigit && tightGap <= prev.bounds.height * 0.65f) {
                        sb.append(cur.latex)
                        idx++
                        continue
                    }

                    sb.append(" ")
                }

                sb.append(cur.latex)
                idx++
            }
            return sb.toString().trim()
        }

        val latex = formatTokens(tokens)
        val fullAlternatives = mutableListOf<String>()

        for (tokenIdx in tokens.indices) {
            val token = tokens[tokenIdx]
            for (alt in token.alternatives) {
                val altTokenList = tokens.mapIndexed { j, tok ->
                    if (j == tokenIdx) tok.copy(latex = alt) else tok
                }
                val candidateStr = formatTokens(altTokenList)
                if (candidateStr != latex && candidateStr !in fullAlternatives) {
                    fullAlternatives.add(candidateStr)
                }
            }
        }

        return ExpressionRecognitionResult(latex, fullAlternatives.take(4))
    }

    private fun clusterStrokesHorizontally(strokes: List<TimedStroke>): List<List<TimedStroke>> {
        if (strokes.size <= 1) return listOf(strokes)

        val sorted = strokes.sortedBy { it.bounds.left }
        val clusters = mutableListOf<MutableList<TimedStroke>>()

        for (stroke in sorted) {
            val sBounds = stroke.bounds
            var placed = false

            for (cluster in clusters) {
                val cLeft = cluster.minOf { it.bounds.left }
                val cRight = cluster.maxOf { it.bounds.right }
                val cTop = cluster.minOf { it.bounds.top }
                val cBottom = cluster.maxOf { it.bounds.bottom }
                val cWidth = max(cRight - cLeft, 8f)
                val cHeight = max(cBottom - cTop, 8f)
                val cBounds = Rect(cLeft, cTop, cRight, cBottom)

                // Condition 1: Intersection or contact distance <= 14px
                val touchesAny = cluster.any { other ->
                    strokesIntersectOrTouch(stroke, other, 14f)
                }

                // Condition 2: Vertical Stacking (like '=' sign or division bar)
                val overlapX = min(sBounds.right, cRight) - max(sBounds.left, cLeft)
                val minW = min(sBounds.width, cWidth)
                val isStacked = (minW > 4f && overlapX / minW >= 0.45f) &&
                        (abs(sBounds.center.x - cBounds.center.x) <= max(sBounds.width, cWidth) * 0.55f) &&
                        (verticalDistance(sBounds, cBounds) <= max(cHeight, sBounds.height) * 1.25f)

                if (touchesAny || isStacked) {
                    cluster.add(stroke)
                    placed = true
                    break
                }
            }

            if (!placed) {
                clusters.add(mutableListOf(stroke))
            }
        }

        return clusters.sortedBy { c -> c.minOf { it.bounds.left } }
    }

    private fun verticalDistance(a: Rect, b: Rect): Float = when {
        a.bottom < b.top -> b.top - a.bottom
        b.bottom < a.top -> a.top - b.bottom
        else -> 0f
    }

    private fun strokesIntersectOrTouch(a: TimedStroke, b: TimedStroke, maxDist: Float): Boolean {
        if (a.bounds.right < b.bounds.left - maxDist || b.bounds.right < a.bounds.left - maxDist ||
            a.bounds.bottom < b.bounds.top - maxDist || b.bounds.bottom < a.bounds.top - maxDist) {
            return false
        }

        val ptsA = a.points.map { it.offset }
        val ptsB = b.points.map { it.offset }
        if (anySegmentsIntersect(ptsA, ptsB)) return true

        val maxDistSq = maxDist * maxDist
        val stepA = max(1, ptsA.size / 8)
        val stepB = max(1, ptsB.size / 8)
        for (i in ptsA.indices step stepA) {
            val pa = ptsA[i]
            for (j in ptsB.indices step stepB) {
                val pb = ptsB[j]
                val dx = pa.x - pb.x
                val dy = pa.y - pb.y
                if (dx * dx + dy * dy <= maxDistSq) return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------------
    // Custom Prototype Recording & Persistence
    // ------------------------------------------------------------------------

    fun addCustomPrototype(
        label: String,
        latex: String,
        strokePolylines: List<List<Offset>>,
        saveToFile: Boolean = true
    ) = synchronized(lock) {
        val points = processStrokes(strokePolylines)
        val proto = SymbolPrototype(
            label = label,
            latex = latex,
            strokeCount = strokePolylines.size,
            points = points,
            isCustom = true
        )
        prototypes.add(0, proto)
        if (saveToFile && context != null) {
            saveCustomPrototypes(context)
        }
    }

    fun getPrototypes(): List<SymbolPrototype> = synchronized(lock) {
        prototypes.toList()
    }

    fun removeCustomPrototype(proto: SymbolPrototype) = synchronized(lock) {
        prototypes.remove(proto)
        context?.let { saveCustomPrototypes(it) }
    }

    private fun loadCustomPrototypes(ctx: Context) = synchronized(lock) {
        try {
            val file = File(ctx.filesDir, PROTOTYPES_FILE_NAME)
            if (!file.exists()) return
            val text = file.readText()
            val array = JSONArray(text)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val label = obj.getString("label")
                val latex = obj.getString("latex")
                val strokeCount = obj.getInt("strokeCount")
                val ptsArray = obj.getJSONArray("points")
                val points = mutableListOf<NormalizedPoint>()
                for (j in 0 until ptsArray.length()) {
                    val ptObj = ptsArray.getJSONObject(j)
                    points.add(NormalizedPoint(ptObj.getDouble("x").toFloat(), ptObj.getDouble("y").toFloat()))
                }
                prototypes.add(
                    0,
                    SymbolPrototype(
                        label = label,
                        latex = latex,
                        strokeCount = strokeCount,
                        points = points,
                        isCustom = true
                    )
                )
            }
        } catch (_: Exception) {
            // Ignore load failures in restricted test environments
        }
    }

    private fun saveCustomPrototypes(ctx: Context) = synchronized(lock) {
        try {
            val customOnly = prototypes.filter { it.isCustom }
            val array = JSONArray()
            for (proto in customOnly) {
                val obj = JSONObject()
                obj.put("label", proto.label)
                obj.put("latex", proto.latex)
                obj.put("strokeCount", proto.strokeCount)
                val ptsArray = JSONArray()
                for (pt in proto.points) {
                    val ptObj = JSONObject()
                    ptObj.put("x", pt.x.toDouble())
                    ptObj.put("y", pt.y.toDouble())
                    ptsArray.put(ptObj)
                }
                obj.put("points", ptsArray)
                array.put(obj)
            }
            val file = File(ctx.filesDir, PROTOTYPES_FILE_NAME)
            file.writeText(array.toString(2))
        } catch (_: Exception) {
            // Ignore write failures in restricted environments
        }
    }

    // ------------------------------------------------------------------------
    // Built-in Prototype Library
    // ------------------------------------------------------------------------

    private fun loadBuiltInPrototypes() {
        // Digits
        addBuiltIn("0", "0", listOf(makeEllipse(0.5f, 0.5f, 0.35f, 0.45f)))
        addBuiltIn("1", "1", listOf(listOf(Offset(0.5f, 0.1f), Offset(0.5f, 0.9f))))
        addBuiltIn("1_hook", "1", listOf(listOf(Offset(0.35f, 0.25f), Offset(0.5f, 0.1f), Offset(0.5f, 0.9f))))

        // Digit 2 Variations
        addBuiltIn("2", "2", listOf(listOf(
            Offset(0.2f, 0.3f), Offset(0.5f, 0.1f), Offset(0.8f, 0.3f),
            Offset(0.2f, 0.9f), Offset(0.85f, 0.9f)
        )))
        addBuiltIn("2_loop", "2", listOf(listOf(
            Offset(0.25f, 0.3f), Offset(0.5f, 0.12f), Offset(0.78f, 0.28f),
            Offset(0.25f, 0.82f), Offset(0.18f, 0.92f), Offset(0.32f, 0.88f), Offset(0.85f, 0.9f)
        )))
        addBuiltIn("2_two_stroke", "2", listOf(
            listOf(Offset(0.2f, 0.3f), Offset(0.5f, 0.12f), Offset(0.8f, 0.3f), Offset(0.2f, 0.9f)),
            listOf(Offset(0.15f, 0.9f), Offset(0.85f, 0.9f))
        ))

        // Digit 3 Variations
        addBuiltIn("3", "3", listOf(listOf(
            Offset(0.2f, 0.15f), Offset(0.8f, 0.15f), Offset(0.45f, 0.5f),
            Offset(0.8f, 0.75f), Offset(0.2f, 0.9f)
        )))
        addBuiltIn("3_curved", "3", listOf(listOf(
            Offset(0.25f, 0.25f), Offset(0.5f, 0.12f), Offset(0.75f, 0.28f), Offset(0.5f, 0.5f),
            Offset(0.78f, 0.72f), Offset(0.5f, 0.88f), Offset(0.22f, 0.78f)
        )))

        // Digit 4 Variations
        addBuiltIn("4", "4", listOf(
            listOf(Offset(0.3f, 0.1f), Offset(0.15f, 0.6f), Offset(0.85f, 0.6f)),
            listOf(Offset(0.7f, 0.1f), Offset(0.7f, 0.9f))
        ))
        addBuiltIn("4_single", "4", listOf(listOf(
            Offset(0.7f, 0.9f), Offset(0.7f, 0.1f), Offset(0.2f, 0.65f), Offset(0.85f, 0.65f)
        )))

        // Digit 5 Variations
        addBuiltIn("5", "5", listOf(listOf(
            Offset(0.8f, 0.15f), Offset(0.25f, 0.15f), Offset(0.2f, 0.5f),
            Offset(0.8f, 0.6f), Offset(0.25f, 0.9f)
        )))
        addBuiltIn("5_two_stroke", "5", listOf(
            listOf(Offset(0.25f, 0.15f), Offset(0.2f, 0.5f), Offset(0.8f, 0.6f), Offset(0.25f, 0.9f)),
            listOf(Offset(0.25f, 0.15f), Offset(0.8f, 0.15f))
        ))

        // Digit 6
        addBuiltIn("6", "6", listOf(listOf(
            Offset(0.8f, 0.15f), Offset(0.25f, 0.5f), Offset(0.25f, 0.85f),
            Offset(0.8f, 0.85f), Offset(0.8f, 0.55f), Offset(0.25f, 0.55f)
        )))

        // Digit 7 Variations
        addBuiltIn("7", "7", listOf(listOf(
            Offset(0.15f, 0.15f), Offset(0.85f, 0.15f), Offset(0.4f, 0.9f)
        )))
        addBuiltIn("7_cross", "7", listOf(
            listOf(Offset(0.15f, 0.15f), Offset(0.85f, 0.15f), Offset(0.4f, 0.9f)),
            listOf(Offset(0.35f, 0.5f), Offset(0.65f, 0.5f))
        ))

        // Digit 8
        addBuiltIn("8", "8", listOf(listOf(
            Offset(0.5f, 0.5f), Offset(0.25f, 0.25f), Offset(0.5f, 0.1f),
            Offset(0.75f, 0.25f), Offset(0.5f, 0.5f), Offset(0.2f, 0.75f),
            Offset(0.5f, 0.9f), Offset(0.8f, 0.75f), Offset(0.5f, 0.5f)
        )))

        // Digit 9
        addBuiltIn("9", "9", listOf(listOf(
            Offset(0.8f, 0.5f), Offset(0.25f, 0.5f), Offset(0.25f, 0.15f),
            Offset(0.8f, 0.15f), Offset(0.8f, 0.9f), Offset(0.3f, 0.9f)
        )))

        // Operators
        addBuiltIn("+", "+", listOf(
            listOf(Offset(0.5f, 0.1f), Offset(0.5f, 0.9f)),
            listOf(Offset(0.1f, 0.5f), Offset(0.9f, 0.5f))
        ))
        addBuiltIn("-", "-", listOf(
            listOf(Offset(0.1f, 0.5f), Offset(0.9f, 0.5f))
        ))
        addBuiltIn("=", "=", listOf(
            listOf(Offset(0.1f, 0.35f), Offset(0.9f, 0.35f)),
            listOf(Offset(0.1f, 0.65f), Offset(0.9f, 0.65f))
        ))
        addBuiltIn("\\times", "\\times", listOf(
            listOf(Offset(0.2f, 0.2f), Offset(0.8f, 0.8f)),
            listOf(Offset(0.8f, 0.2f), Offset(0.2f, 0.8f))
        ))
        addBuiltIn("\\div", "\\div", listOf(
            listOf(Offset(0.1f, 0.5f), Offset(0.9f, 0.5f)),
            listOf(Offset(0.5f, 0.25f), Offset(0.5f, 0.27f)),
            listOf(Offset(0.5f, 0.73f), Offset(0.5f, 0.75f))
        ))
        addBuiltIn("\\sqrt", "\\sqrt", listOf(listOf(
            Offset(0.1f, 0.5f), Offset(0.2f, 0.6f), Offset(0.4f, 0.95f),
            Offset(0.6f, 0.05f), Offset(0.95f, 0.05f)
        )))
        addBuiltIn("\\int", "\\int", listOf(listOf(
            Offset(0.75f, 0.1f), Offset(0.5f, 0.05f), Offset(0.45f, 0.5f),
            Offset(0.5f, 0.95f), Offset(0.25f, 0.9f)
        )))

        // Parentheses
        addBuiltIn("(", "(", listOf(listOf(
            Offset(0.7f, 0.1f), Offset(0.3f, 0.5f), Offset(0.7f, 0.9f)
        )))
        addBuiltIn(")", ")", listOf(listOf(
            Offset(0.3f, 0.1f), Offset(0.7f, 0.5f), Offset(0.3f, 0.9f)
        )))

        // Variables: x, y, z, a, b, c, t, s
        addBuiltIn("x", "x", listOf(
            listOf(Offset(0.2f, 0.2f), Offset(0.8f, 0.8f)),
            listOf(Offset(0.8f, 0.2f), Offset(0.2f, 0.8f))
        ))
        addBuiltIn("y", "y", listOf(
            listOf(Offset(0.2f, 0.2f), Offset(0.5f, 0.6f)),
            listOf(Offset(0.8f, 0.2f), Offset(0.2f, 0.95f))
        ))
        addBuiltIn("z", "z", listOf(listOf(
            Offset(0.2f, 0.2f), Offset(0.8f, 0.2f), Offset(0.2f, 0.8f), Offset(0.8f, 0.8f)
        )))
        addBuiltIn("t", "t", listOf(
            listOf(Offset(0.5f, 0.1f), Offset(0.5f, 0.9f)),
            listOf(Offset(0.2f, 0.35f), Offset(0.8f, 0.35f))
        ))
        addBuiltIn("s", "s", listOf(listOf(
            Offset(0.8f, 0.25f), Offset(0.5f, 0.15f), Offset(0.2f, 0.35f),
            Offset(0.8f, 0.65f), Offset(0.5f, 0.85f), Offset(0.2f, 0.75f)
        )))
        addBuiltIn("a", "a", listOf(listOf(
            Offset(0.75f, 0.4f), Offset(0.45f, 0.3f), Offset(0.25f, 0.6f),
            Offset(0.55f, 0.85f), Offset(0.75f, 0.5f), Offset(0.75f, 0.9f)
        )))
        addBuiltIn("b", "b", listOf(listOf(
            Offset(0.25f, 0.1f), Offset(0.25f, 0.9f), Offset(0.75f, 0.75f),
            Offset(0.75f, 0.45f), Offset(0.25f, 0.5f)
        )))
        addBuiltIn("c", "c", listOf(listOf(
            Offset(0.8f, 0.25f), Offset(0.35f, 0.2f), Offset(0.2f, 0.55f),
            Offset(0.35f, 0.85f), Offset(0.8f, 0.8f)
        )))

        // Constants: \pi, e
        addBuiltIn("\\pi", "\\pi", listOf(
            listOf(Offset(0.15f, 0.2f), Offset(0.85f, 0.2f)),
            listOf(Offset(0.35f, 0.2f), Offset(0.35f, 0.85f)),
            listOf(Offset(0.65f, 0.2f), Offset(0.65f, 0.85f))
        ))
        addBuiltIn("e", "e", listOf(listOf(
            Offset(0.2f, 0.55f), Offset(0.8f, 0.55f), Offset(0.5f, 0.2f),
            Offset(0.2f, 0.5f), Offset(0.4f, 0.85f), Offset(0.8f, 0.8f)
        )))
    }

    private fun addBuiltIn(label: String, latex: String, strokePolylines: List<List<Offset>>) {
        val points = processStrokes(strokePolylines)
        prototypes.add(
            SymbolPrototype(
                label = label,
                latex = latex,
                strokeCount = strokePolylines.size,
                points = points,
                isCustom = false
            )
        )
    }

    private fun makeEllipse(cx: Float, cy: Float, rx: Float, ry: Float, steps: Int = 16): List<Offset> {
        val points = ArrayList<Offset>(steps)
        for (i in 0 until steps) {
            val theta = (i.toFloat() / steps) * (2f * PI.toFloat())
            points.add(Offset(cx + rx * cos(theta), cy + ry * sin(theta)))
        }
        points.add(points.first())
        return points
    }
}
