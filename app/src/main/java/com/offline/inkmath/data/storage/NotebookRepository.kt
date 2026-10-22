package com.offline.inkmath.data.storage

import android.content.Context
import android.graphics.Color
import com.offline.inkmath.domain.clustering.InkWorkspaceState
import com.offline.inkmath.domain.dag.ReactiveSymbolGraph
import com.offline.inkmath.domain.model.BlockDisplayMode
import com.offline.inkmath.domain.model.BlockStatus
import com.offline.inkmath.domain.model.EquationBlock
import com.offline.inkmath.domain.model.InkPoint
import com.offline.inkmath.domain.model.StrokeGeometry
import com.offline.inkmath.domain.model.SymbolicTransform
import com.offline.inkmath.domain.model.TimedStroke
import com.offline.inkmath.domain.model.unionBounds
import com.offline.inkmath.domain.parser.AmbiguousSolveVariableException
import com.offline.inkmath.domain.parser.LatexToSymjaTranspiler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class NotebookPageMeta(
    val id: UUID,
    val title: String,
    val updatedAtMillis: Long,
    val blockCount: Int
)

/**
 * 100% offline multi-page notebook repository.
 * Manages indexed notebook pages with atomic JSON persistence, Jetpack Ink stroke reconstruction,
 * and seamless migration from legacy single-workspace storage.
 */
class NotebookRepository(private val context: Context) {
    private val notebooksDir = File(context.filesDir, "notebooks").apply { mkdirs() }
    private val indexFile = File(notebooksDir, "notebooks_index.json")
    private val legacyFile = File(context.filesDir, "workspace_v1.json")

    private val lock = Any()

    init {
        synchronized(lock) {
            ensureInitialized()
        }
    }

    private fun ensureInitialized() {
        if (!indexFile.exists() || indexFile.length() == 0L) {
            val initialId = UUID.randomUUID()
            val initialTitle = "Main Notebook"
            val now = System.currentTimeMillis()

            if (legacyFile.exists() && legacyFile.length() > 0L) {
                // Migrate legacy workspace to default page
                val targetFile = getPageFile(initialId)
                legacyFile.copyTo(targetFile, overwrite = true)
            }

            val meta = NotebookPageMeta(
                id = initialId,
                title = initialTitle,
                updatedAtMillis = now,
                blockCount = 0
            )
            writeIndex(listOf(meta), activeId = initialId)
        }
    }

    private fun getPageFile(id: UUID): File = File(notebooksDir, "page_${id}.json")
    private fun getTmpFile(id: UUID): File = File(notebooksDir, "page_${id}.json.tmp")

    fun listPages(): List<NotebookPageMeta> = synchronized(lock) {
        readIndex().first
    }

    fun getActivePageId(): UUID = synchronized(lock) {
        val (pages, activeId) = readIndex()
        if (pages.any { it.id == activeId }) activeId
        else pages.firstOrNull()?.id ?: createPage("Main Notebook").id
    }

    fun setActivePageId(id: UUID) = synchronized(lock) {
        val (pages, _) = readIndex()
        if (pages.any { it.id == id }) {
            writeIndex(pages, activeId = id)
        }
    }

    fun createPage(title: String = "Untitled"): NotebookPageMeta = synchronized(lock) {
        val (pages, _) = readIndex()
        val newPage = NotebookPageMeta(
            id = UUID.randomUUID(),
            title = title.ifBlank { "Untitled" },
            updatedAtMillis = System.currentTimeMillis(),
            blockCount = 0
        )
        val updated = listOf(newPage) + pages
        writeIndex(updated, activeId = newPage.id)
        newPage
    }

    fun renamePage(id: UUID, newTitle: String) = synchronized(lock) {
        val (pages, activeId) = readIndex()
        val updated = pages.map { page ->
            if (page.id == id) page.copy(title = newTitle.ifBlank { "Untitled" }, updatedAtMillis = System.currentTimeMillis())
            else page
        }
        writeIndex(updated, activeId = activeId)
    }

    fun deletePage(id: UUID): Boolean = synchronized(lock) {
        val (pages, activeId) = readIndex()
        if (pages.size <= 1) return false // Prevent deleting the last page
        val target = pages.firstOrNull { it.id == id } ?: return false

        val remaining = pages.filterNot { it.id == id }
        val newActiveId = if (activeId == id) remaining.first().id else activeId

        getPageFile(id).delete()
        getTmpFile(id).delete()
        writeIndex(remaining, activeId = newActiveId)
        true
    }

    fun savePage(id: UUID, state: InkWorkspaceState) = synchronized(lock) {
        val file = getPageFile(id)
        val tmp = getTmpFile(id)

        val root = JSONObject()
        root.put("version", state.version)

        val strokesArray = JSONArray()
        for (timed in state.strokes) {
            val strokeObj = JSONObject()
            strokeObj.put("id", timed.id.toString())
            strokeObj.put("finishedAtMillis", timed.finishedAtMillis)
            val brushSize = try { timed.stroke.brush.size.toDouble() } catch (_: Exception) { 3.5 }
            strokeObj.put("brushSize", brushSize)
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
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } finally {
            if (tmp.exists()) tmp.delete()
        }

        // Update block count & timestamp in index
        val (pages, activeId) = readIndex()
        val updated = pages.map { page ->
            if (page.id == id) page.copy(blockCount = state.blocks.size, updatedAtMillis = System.currentTimeMillis())
            else page
        }
        writeIndex(updated, activeId = activeId)
    }

    suspend fun loadPage(
        id: UUID,
        parser: LatexToSymjaTranspiler,
        graph: ReactiveSymbolGraph
    ): InkWorkspaceState? {
        val file = getPageFile(id)
        if (!file.exists() || file.length() == 0L) return null

        return try {
            val text = file.readText(Charsets.UTF_8)
            val root = JSONObject(text)
            val version = root.optLong("version", 0L)

            val reconstructedStrokes = mutableListOf<TimedStroke>()
            val strokesById = mutableMapOf<UUID, TimedStroke>()

            val strokesArray = root.optJSONArray("strokes") ?: JSONArray()
            for (i in 0 until strokesArray.length()) {
                val strokeObj = strokesArray.getJSONObject(i)
                val strokeId = UUID.fromString(strokeObj.getString("id"))
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

                var left = Float.POSITIVE_INFINITY
                var top = Float.POSITIVE_INFINITY
                var right = Float.NEGATIVE_INFINITY
                var bottom = Float.NEGATIVE_INFINITY
                points.forEach { pt ->
                    left = kotlin.math.min(left, pt.x); top = kotlin.math.min(top, pt.y)
                    right = kotlin.math.max(right, pt.x); bottom = kotlin.math.max(bottom, pt.y)
                }
                val radius = brushSize / 2f
                val bounds = androidx.compose.ui.geometry.Rect(left - radius, top - radius, right + radius, bottom + radius)
                val timed = TimedStroke(null, finishedAt, StrokeGeometry(strokeId, points, bounds))
                reconstructedStrokes += timed
                strokesById[strokeId] = timed
            }

            val reconstructedBlocks = mutableListOf<EquationBlock>()
            val blocksArray = root.optJSONArray("blocks") ?: JSONArray()

            for (i in 0 until blocksArray.length()) {
                val blockObj = blocksArray.getJSONObject(i)
                val blockId = UUID.fromString(blockObj.getString("id"))
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
                val displayModeName = blockObj.optString("displayMode", BlockDisplayMode.HANDWRITTEN.name)
                val displayMode = runCatching { BlockDisplayMode.valueOf(displayModeName) }.getOrDefault(BlockDisplayMode.HANDWRITTEN)
                val transformName = blockObj.optString("transform", SymbolicTransform.AUTO.name)
                val transform = runCatching { SymbolicTransform.valueOf(transformName) }.getOrDefault(SymbolicTransform.AUTO)

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
                        graph.upsert(blockId, transpiled.intent, transform)
                    } catch (_: Exception) {
                        // Keep block as Dirty if parse fails
                    }
                }

                val graphBlock = graph.state.value.blocks[blockId]
                reconstructedBlocks += EquationBlock(
                    id = blockId,
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

    private fun readIndex(): Pair<List<NotebookPageMeta>, UUID> {
        try {
            if (!indexFile.exists() || indexFile.length() == 0L) {
                ensureInitialized()
            }
            val text = indexFile.readText(Charsets.UTF_8)
            val root = JSONObject(text)
            val activeIdStr = root.optString("activeId", "")
            val activeId = if (activeIdStr.isNotBlank()) runCatching { UUID.fromString(activeIdStr) }.getOrNull() ?: UUID.randomUUID() else UUID.randomUUID()
            val pagesArray = root.optJSONArray("pages") ?: JSONArray()
            val pages = mutableListOf<NotebookPageMeta>()
            for (i in 0 until pagesArray.length()) {
                val obj = pagesArray.optJSONObject(i) ?: continue
                val idStr = obj.optString("id", "")
                val id = runCatching { UUID.fromString(idStr) }.getOrNull() ?: continue
                pages += NotebookPageMeta(
                    id = id,
                    title = obj.optString("title", "Untitled"),
                    updatedAtMillis = obj.optLong("updatedAtMillis", System.currentTimeMillis()),
                    blockCount = obj.optInt("blockCount", 0)
                )
            }
            if (pages.isEmpty()) {
                val defaultPage = NotebookPageMeta(UUID.randomUUID(), "Main Notebook", System.currentTimeMillis(), 0)
                writeIndex(listOf(defaultPage), defaultPage.id)
                return listOf(defaultPage) to defaultPage.id
            }
            return pages to activeId
        } catch (_: Exception) {
            val defaultPage = NotebookPageMeta(UUID.randomUUID(), "Main Notebook", System.currentTimeMillis(), 0)
            runCatching { writeIndex(listOf(defaultPage), defaultPage.id) }
            return listOf(defaultPage) to defaultPage.id
        }
    }

    private fun writeIndex(pages: List<NotebookPageMeta>, activeId: UUID) {
        val root = JSONObject()
        root.put("activeId", activeId.toString())
        val array = JSONArray()
        for (p in pages) {
            val obj = JSONObject()
            obj.put("id", p.id.toString())
            obj.put("title", p.title)
            obj.put("updatedAtMillis", p.updatedAtMillis)
            obj.put("blockCount", p.blockCount)
            array.put(obj)
        }
        root.put("pages", array)

        val tmp = File(notebooksDir, "notebooks_index.json.tmp")
        try {
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (indexFile.exists()) indexFile.delete()
            tmp.renameTo(indexFile)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
