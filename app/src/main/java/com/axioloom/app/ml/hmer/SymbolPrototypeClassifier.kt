package com.axioloom.app.ml.hmer

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.axioloom.app.domain.model.StrokeGeometry
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
 * Offline Multi-Stroke Elastic DTW (Dynamic Time Warping) and N-Protractor Symbol Classifier.
 * Operates directly on resampled stroke geometry (N = 32 equidistant points normalized to [0, 1]^2).
 * Pre-populated with canonical prototypes for digits 0-9, variables, operators, and constants.
 * Supports personal calibration via JSON persistence in user_symbol_prototypes.json.
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

        /**
         * Resamples a polyline of 2D points to exactly [targetCount] equidistant points along its arc length.
         */
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

            var currentDist = 0f
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

        /**
         * Converts a multi-stroke gesture (list of stroke point lists) into N=32 normalized points in [0, 1]^2.
         */
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
                // Distribute TARGET_POINTS among strokes proportional to length
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

            // Normalization to [0, 1]^2 preserving aspect ratio
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
    }

    // ------------------------------------------------------------------------
    // Elastic DTW Matching
    // ------------------------------------------------------------------------

    private fun dtwDistance(
        a: List<NormalizedPoint>,
        b: List<NormalizedPoint>,
        window: Int = 6
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

        val dtwScore = if (dp[n][m].isFinite()) dp[n][m] / n.toFloat() else 10f

        // Also blend with direct Euclidean point-by-point distance
        var eucSum = 0f
        val len = min(n, m)
        for (i in 0 until len) {
            eucSum += hypot(a[i].x - b[i].x, a[i].y - b[i].y)
        }
        val eucScore = eucSum / len.toFloat()

        return (0.6f * dtwScore) + (0.4f * eucScore)
    }

    /**
     * Classifies a list of strokes against known prototypes.
     * Evaluates stroke permutations for multi-stroke inputs to guarantee direction invariance.
     */
    fun classifyStrokes(strokePolylines: List<List<Offset>>): List<ClassificationMatch> = synchronized(lock) {
        val validStrokes = strokePolylines.filter { it.isNotEmpty() }
        if (validStrokes.isEmpty()) return emptyList()

        val strokeCount = validStrokes.size

        // Generate stroke permutations for multi-stroke inputs (e.g. forward, reversed stroke order)
        val variations = mutableListOf<List<List<Offset>>>()
        variations.add(validStrokes)
        if (strokeCount == 2) {
            variations.add(listOf(validStrokes[1], validStrokes[0]))
            variations.add(listOf(validStrokes[0].reversed(), validStrokes[1]))
            variations.add(listOf(validStrokes[0], validStrokes[1].reversed()))
        } else if (strokeCount == 1) {
            variations.add(listOf(validStrokes[0].reversed()))
        }

        val processedVariations = variations.map { processStrokes(it) }

        val matches = mutableListOf<ClassificationMatch>()

        for (proto in prototypes) {
            var bestScore = Float.POSITIVE_INFINITY

            for (cand in processedVariations) {
                val dist = dtwDistance(cand, proto.points)
                // Add penalty if stroke counts differ
                val strokeCountDiff = abs(strokeCount - proto.strokeCount)
                val strokePenalty = strokeCountDiff * 0.40f
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
     * High-level expression recognizer: segments horizontally separated symbols,
     * classifies each symbol, and formats into combined LaTeX string.
     */
    fun recognizeExpression(timedStrokes: List<TimedStroke>): ExpressionRecognitionResult? {
        if (timedStrokes.isEmpty()) return null

        // 1. Cluster strokes horizontally into symbol groups
        val clusters = clusterStrokesHorizontally(timedStrokes)
        if (clusters.isEmpty()) return null

        val tokens = mutableListOf<String>()
        val allAlternatives = mutableListOf<String>()

        for (cluster in clusters) {
            val polylines = cluster.map { stroke -> stroke.points.map { it.offset } }
            val matches = classifyStrokes(polylines)
            if (matches.isNotEmpty()) {
                val top = matches.first()
                tokens.add(top.latex)
                matches.take(3).forEach { m ->
                    if (m.latex != top.latex && m.latex !in allAlternatives) {
                        allAlternatives.add(m.latex)
                    }
                }
            }
        }

        val latex = tokens.joinToString(" ")
        return ExpressionRecognitionResult(latex, allAlternatives)
    }

    private fun clusterStrokesHorizontally(strokes: List<TimedStroke>): List<List<TimedStroke>> {
        if (strokes.size <= 1) return listOf(strokes)

        val sorted = strokes.sortedBy { it.bounds.left }
        val clusters = mutableListOf<MutableList<TimedStroke>>()

        for (stroke in sorted) {
            val bounds = stroke.bounds
            var placed = false

            for (cluster in clusters) {
                val clusterLeft = cluster.minOf { it.bounds.left }
                val clusterRight = cluster.maxOf { it.bounds.right }
                val clusterTop = cluster.minOf { it.bounds.top }
                val clusterBottom = cluster.maxOf { it.bounds.bottom }
                val clusterHeight = max(clusterBottom - clusterTop, 20f)

                // Overlaps horizontally or vertically stacked (like '=' or ':' or division bar)
                val horizOverlap = (bounds.left <= clusterRight + clusterHeight * 0.25f) &&
                        (bounds.right >= clusterLeft - clusterHeight * 0.25f)
                val vertStack = abs(bounds.left - clusterLeft) < clusterHeight * 0.6f

                if (horizOverlap || vertStack) {
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
        prototypes.add(0, proto) // Custom prototypes placed at front for higher priority
        if (saveToFile && context != null) {
            saveCustomPrototypes(context)
        }
    }

    fun loadCustomPrototypes(ctx: Context) = synchronized(lock) {
        try {
            val file = File(ctx.filesDir, PROTOTYPES_FILE_NAME)
            if (!file.exists()) return

            val jsonStr = file.readText()
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val label = obj.getString("label")
                val latex = obj.getString("latex")
                val strokeCount = obj.getInt("strokeCount")
                val ptsArray = obj.getJSONArray("points")
                val pts = mutableListOf<NormalizedPoint>()
                for (j in 0 until ptsArray.length()) {
                    val ptObj = ptsArray.getJSONObject(j)
                    pts.add(
                        NormalizedPoint(
                            x = ptObj.getDouble("x").toFloat(),
                            y = ptObj.getDouble("y").toFloat()
                        )
                    )
                }
                prototypes.add(0, SymbolPrototype(label, latex, strokeCount, pts, isCustom = true))
            }
        } catch (_: Exception) {
            // Graceful fallback if corrupt or read error
        }
    }

    fun saveCustomPrototypes(ctx: Context) = synchronized(lock) {
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
        addBuiltIn("2", "2", listOf(listOf(
            Offset(0.2f, 0.3f), Offset(0.5f, 0.1f), Offset(0.8f, 0.3f),
            Offset(0.2f, 0.9f), Offset(0.85f, 0.9f)
        )))
        addBuiltIn("3", "3", listOf(listOf(
            Offset(0.2f, 0.15f), Offset(0.8f, 0.15f), Offset(0.45f, 0.5f),
            Offset(0.8f, 0.75f), Offset(0.2f, 0.9f)
        )))
        addBuiltIn("4", "4", listOf(
            listOf(Offset(0.3f, 0.1f), Offset(0.15f, 0.6f), Offset(0.85f, 0.6f)),
            listOf(Offset(0.7f, 0.1f), Offset(0.7f, 0.9f))
        ))
        addBuiltIn("5", "5", listOf(listOf(
            Offset(0.8f, 0.15f), Offset(0.25f, 0.15f), Offset(0.2f, 0.5f),
            Offset(0.8f, 0.6f), Offset(0.25f, 0.9f)
        )))
        addBuiltIn("6", "6", listOf(listOf(
            Offset(0.8f, 0.15f), Offset(0.25f, 0.5f), Offset(0.25f, 0.85f),
            Offset(0.8f, 0.85f), Offset(0.8f, 0.55f), Offset(0.25f, 0.55f)
        )))
        addBuiltIn("7", "7", listOf(listOf(
            Offset(0.15f, 0.15f), Offset(0.85f, 0.15f), Offset(0.4f, 0.9f)
        )))
        addBuiltIn("8", "8", listOf(listOf(
            Offset(0.5f, 0.5f), Offset(0.25f, 0.25f), Offset(0.5f, 0.1f),
            Offset(0.75f, 0.25f), Offset(0.5f, 0.5f), Offset(0.2f, 0.75f),
            Offset(0.5f, 0.9f), Offset(0.8f, 0.75f), Offset(0.5f, 0.5f)
        )))
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

        // Variables: a, b, c, x, y, z, t, s
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
