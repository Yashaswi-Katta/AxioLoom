package com.offline.inkmath.data.storage

import android.content.Context
import android.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import com.offline.inkmath.domain.clustering.InkWorkspaceState
import com.offline.inkmath.domain.dag.ReactiveSymbolGraph
import com.offline.inkmath.domain.model.BlockStatus
import com.offline.inkmath.domain.model.EquationBlock
import com.offline.inkmath.domain.model.InkPoint
import com.offline.inkmath.domain.model.TimedStroke
import com.offline.inkmath.domain.model.unionBounds
import com.offline.inkmath.domain.parser.AmbiguousSolveVariableException
import com.offline.inkmath.domain.parser.LatexToSymjaTranspiler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 100% offline, zero-dependency JSON workspace persistence.
 * Serializes and restores vector strokes, clustered equation blocks, and reactive graph state.
 */
class WorkspaceStorage(private val context: Context) {
    private val workspaceFile = File(context.filesDir, "workspace_v1.json")
    private val tmpFile = File(context.filesDir, "workspace_v1.json.tmp")

    @Synchronized
    fun save(state: InkWorkspaceState) {
        val root = JSONObject()
        root.put("version", state.version)

        val strokesArray = JSONArray()
        for (timed in state.strokes) {
            val strokeObj = JSONObject()
            strokeObj.put("id", timed.id.toString())
            strokeObj.put("finishedAtMillis", timed.finishedAtMillis)
            strokeObj.put("brushSize", timed.stroke.brush.size.toDouble())
            strokeObj.put("brushColor", Color.WHITE)

            val pointsArray = JSONArray()
            for (pt in timed.points) {
                val ptObj = JSONObject()
                ptObj.put("x", pt.x.toDouble())
                ptObj.put("y", pt.y.toDouble())
                ptObj.put("t", pt.elapsedTimeMillis)
                pointsArray.put(ptObj)
            }
            strokeObj.put("points", pointsArray)
            strokesArray.put(strokeObj)
        }
        root.put("strokes", strokesArray)

        val blocksArray = JSONArray()
        for (block in state.blocks) {
            val blockObj = JSONObject()
            blockObj.put("id", block.id.toString())
            blockObj.put("revision", block.revision)

            val strokeIdsArray = JSONArray()
            for (sId in block.strokeIds) {
                strokeIdsArray.put(sId.toString())
            }
            blockObj.put("strokeIds", strokeIdsArray)
            blockObj.put("recognizedLatex", block.recognizedLatex ?: JSONObject.NULL)
            blockObj.put("solveTarget", block.solveTarget ?: JSONObject.NULL)
            blockObj.put("displayMode", block.displayMode.name)
            blockObj.put("transform", block.transform.name)

            val candidateArray = JSONArray()
            for (c in block.candidateSolveVars) {
                candidateArray.put(c)
            }
            blockObj.put("candidateSolveVars", candidateArray)

            blocksArray.put(blockObj)
        }
        root.put("blocks", blocksArray)

        try {
            tmpFile.writeText(root.toString(), Charsets.UTF_8)
            if (workspaceFile.exists()) {
                workspaceFile.delete()
            }
            tmpFile.renameTo(workspaceFile)
        } finally {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    @Synchronized
    suspend fun load(
        parser: LatexToSymjaTranspiler,
        graph: ReactiveSymbolGraph
    ): InkWorkspaceState? {
        if (!workspaceFile.exists() || workspaceFile.length() == 0L) return null
        return try {
            val text = workspaceFile.readText(Charsets.UTF_8)
            val root = JSONObject(text)
            val version = root.optLong("version", 0L)

            val reconstructedStrokes = mutableListOf<TimedStroke>()
            val strokesById = mutableMapOf<UUID, TimedStroke>()

            val strokesArray = root.optJSONArray("strokes") ?: JSONArray()
            for (i in 0 until strokesArray.length()) {
                val strokeObj = strokesArray.getJSONObject(i)
                val id = UUID.fromString(strokeObj.getString("id"))
                val finishedAt = strokeObj.getLong("finishedAtMillis")
                val brushSize = strokeObj.optDouble("brushSize", 3.5).toFloat()
                val brushColor = strokeObj.optInt("brushColor", Color.WHITE)

                val pointsArray = strokeObj.getJSONArray("points")
                val points = ArrayList<InkPoint>(pointsArray.length())
                for (j in 0 until pointsArray.length()) {
                    val p = pointsArray.getJSONObject(j)
                    points += InkPoint(p.getDouble("x").toFloat(), p.getDouble("y").toFloat(), p.getLong("t"))
                }
                if (points.isEmpty()) continue

                val brush = Brush.createWithColorIntArgb(
                    family = StockBrushes.markerLatest,
                    colorIntArgb = brushColor,
                    size = brushSize,
                    epsilon = 0.1f
                )
                val batch = MutableStrokeInputBatch().apply {
                    points.forEach { pt ->
                        add(
                            StrokeInput.create(
                                x = pt.x,
                                y = pt.y,
                                elapsedTimeMillis = pt.elapsedTimeMillis,
                                toolType = InputToolType.STYLUS
                            )
                        )
                    }
                }
                val nativeStroke = Stroke(brush, batch)
                val timed = TimedStroke.fromStroke(nativeStroke, finishedAt, id)
                reconstructedStrokes += timed
                strokesById[id] = timed
            }

            val reconstructedBlocks = mutableListOf<EquationBlock>()
            val blocksArray = root.optJSONArray("blocks") ?: JSONArray()

            for (i in 0 until blocksArray.length()) {
                val blockObj = blocksArray.getJSONObject(i)
                val id = UUID.fromString(blockObj.getString("id"))
                val revision = blockObj.optLong("revision", 0L)
                val sIdsArray = blockObj.getJSONArray("strokeIds")
                val blockStrokes = mutableListOf<TimedStroke>()
                for (j in 0 until sIdsArray.length()) {
                    val sId = UUID.fromString(sIdsArray.getString(j))
                    strokesById[sId]?.let { blockStrokes += it }
                }
                if (blockStrokes.isEmpty()) continue

                val bounds = blockStrokes.drop(1).fold(blockStrokes.first().bounds) { acc, s -> acc.unionBounds(s.bounds) }
                val heights = blockStrokes.map { it.bounds.height }.filter { it > 4f }.sorted()
                val medianHeight = if (heights.isNotEmpty()) heights[heights.size / 2] else 24f

                val recognizedLatex = if (!blockObj.isNull("recognizedLatex")) blockObj.getString("recognizedLatex") else null
                val solveTarget = if (!blockObj.isNull("solveTarget")) blockObj.getString("solveTarget") else null
                val displayModeName = blockObj.optString("displayMode", com.offline.inkmath.domain.model.BlockDisplayMode.HANDWRITTEN.name)
                val displayMode = runCatching { com.offline.inkmath.domain.model.BlockDisplayMode.valueOf(displayModeName) }.getOrDefault(com.offline.inkmath.domain.model.BlockDisplayMode.HANDWRITTEN)
                val transformName = blockObj.optString("transform", com.offline.inkmath.domain.model.SymbolicTransform.AUTO.name)
                val transform = runCatching { com.offline.inkmath.domain.model.SymbolicTransform.valueOf(transformName) }.getOrDefault(com.offline.inkmath.domain.model.SymbolicTransform.AUTO)
                val candidateList = mutableListOf<String>()
                val cArray = blockObj.optJSONArray("candidateSolveVars")
                if (cArray != null) {
                    for (k in 0 until cArray.length()) {
                        candidateList += cArray.getString(k)
                    }
                }

                var intent = null as com.offline.inkmath.domain.model.MathIntent?
                var cleanLatex = recognizedLatex
                var finalTarget = solveTarget

                if (recognizedLatex != null) {
                    try {
                        val bound = graph.state.value.symbolOwners.keys.map { it.name }.toSet()
                        val transpiled = try {
                            parser.transpile(recognizedLatex, boundSymbols = bound, solveTarget = solveTarget)
                        } catch (ambiguous: AmbiguousSolveVariableException) {
                            if (candidateList.isEmpty()) candidateList.addAll(ambiguous.candidates)
                            finalTarget = ambiguous.candidates.first()
                            parser.transpile(recognizedLatex, boundSymbols = bound, solveTarget = finalTarget)
                        }
                        intent = transpiled.intent
                        cleanLatex = transpiled.cleanLatex
                        graph.upsert(id, transpiled.intent)
                    } catch (_: Exception) {
                        // Keep block as Dirty if parse fails
                    }
                }

                val graphBlock = graph.state.value.blocks[id]
                reconstructedBlocks += EquationBlock(
                    id = id,
                    strokes = blockStrokes,
                    bounds = bounds,
                    medianStrokeHeight = medianHeight.coerceAtLeast(16f),
                    resultAnchorPoint = null,
                    revision = revision,
                    recognizedLatex = cleanLatex,
                    intent = intent,
                    result = graphBlock?.result,
                    status = graphBlock?.status ?: if (recognizedLatex != null) BlockStatus.Dirty else BlockStatus.Dirty,
                    solveTarget = finalTarget,
                    candidateSolveVars = candidateList,
                    displayMode = displayMode,
                    transform = transform
                )
            }

            InkWorkspaceState(
                strokes = reconstructedStrokes,
                blocks = reconstructedBlocks,
                version = version
            )
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clear() {
        if (workspaceFile.exists()) workspaceFile.delete()
        if (tmpFile.exists()) tmpFile.delete()
    }
}
