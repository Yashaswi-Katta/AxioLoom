package com.axioloom.app.domain.circuit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.axioloom.app.domain.model.EquationBlock
import com.axioloom.app.domain.model.InkPoint
import com.axioloom.app.domain.model.MathExpr
import com.axioloom.app.domain.model.StrokeGeometry
import com.axioloom.app.domain.model.TimedStroke
import com.axioloom.app.domain.model.isIdentifier
import com.axioloom.app.domain.parser.LatexToSymjaTranspiler
import java.util.UUID
import kotlin.math.*

/**
 * Recognized electronic component types from schematic handwriting.
 */
enum class SchematicComponentType {
    WIRE,
    RESISTOR,
    CAPACITOR,
    INDUCTOR,
    VOLTAGE_SOURCE,
    GROUND
}

data class SchematicTerminal(
    val componentId: UUID,
    val terminalIndex: Int, // 0 = A/pos, 1 = B/neg
    val position: Offset
)

data class RecognizedComponent(
    val id: UUID = UUID.randomUUID(),
    val type: SchematicComponentType,
    val strokes: List<TimedStroke>,
    val bounds: Rect,
    val terminals: List<SchematicTerminal>,
    var label: String? = null,
    var valueExpr: MathExpr? = null
)

data class RecognizedSchematic(
    val components: List<RecognizedComponent>,
    val elements: List<CircuitElement>,
    val nodePositions: Map<Int, Offset>,
    val strokeNodeMapping: Map<UUID, List<Int>>
)

/**
 * 100% Offline Geometric Stroke Classifier and Topological Netlist Extractor for hand-drawn schematics.
 */
object SchematicStrokeRecognizer {

    private val parser = LatexToSymjaTranspiler()

    /**
     * Analyzes canvas strokes and nearby equation blocks to synthesize a complete netlist.
     */
    fun recognize(
        strokes: List<TimedStroke>,
        equationBlocks: List<EquationBlock> = emptyList(),
        snapRadiusPx: Float = 24f
    ): RecognizedSchematic {
        if (strokes.isEmpty()) {
            return RecognizedSchematic(emptyList(), emptyList(), emptyMap(), emptyMap())
        }

        // 1. Exclude strokes belonging to recognized equation value labels
        val eqStrokeIds = equationBlocks.filter { it.recognizedLatex?.isNotBlank() == true }.flatMap { it.strokeIds }.toSet()
        val schematicStrokes = strokes.filter { it.id !in eqStrokeIds }

        // 2. Identify multi-stroke ground symbols (3 parallel decreasing bars + stem)
        val remainingStrokes = schematicStrokes.toMutableList()
        val recognizedComponents = mutableListOf<RecognizedComponent>()

        val grounds = extractGroundSymbols(remainingStrokes)
        recognizedComponents.addAll(grounds)

        // 2. Identify two-stroke capacitors (two parallel lines of equal length)
        val capacitors = extractCapacitors(remainingStrokes)
        recognizedComponents.addAll(capacitors)

        // 3. Classify single-stroke components: Resistors, Inductors, Voltage Sources, and Wires
        for (stroke in remainingStrokes) {
            val comp = classifySingleStroke(stroke)
            recognizedComponents.add(comp)
        }

        // 4. Value association from nearby equation blocks
        associateValuesWithComponents(recognizedComponents, equationBlocks)

        // 5. Build Union-Find Disjoint Set on all terminals and wire endpoints
        val allTerminals = mutableListOf<SchematicTerminal>()
        for (comp in recognizedComponents) {
            allTerminals.addAll(comp.terminals)
        }

        val uf = DisjointSet(allTerminals.size)

        for (i in 0 until allTerminals.size) {
            for (j in i + 1 until allTerminals.size) {
                val t1 = allTerminals[i]
                val t2 = allTerminals[j]
                val dist = hypot(t1.position.x - t2.position.x, t1.position.y - t2.position.y)
                if (dist <= snapRadiusPx) {
                    uf.union(i, j)
                }
            }
        }

        // 6. Assign electrical node numbers (Ground = 0)
        val groundRootIndices = mutableSetOf<Int>()
        for (comp in recognizedComponents) {
            if (comp.type == SchematicComponentType.GROUND) {
                for (term in comp.terminals) {
                    val idx = allTerminals.indexOf(term)
                    if (idx >= 0) groundRootIndices.add(uf.find(idx))
                }
            }
        }

        val rootToNodeId = mutableMapOf<Int, Int>()
        // Ground cluster is always node 0
        for (gRoot in groundRootIndices) {
            rootToNodeId[gRoot] = 0
        }

        var nextNodeId = 1
        for (i in 0 until allTerminals.size) {
            val root = uf.find(i)
            if (!rootToNodeId.containsKey(root)) {
                rootToNodeId[root] = nextNodeId++
            }
        }

        val terminalToNode = mutableMapOf<SchematicTerminal, Int>()
        val nodePositions = mutableMapOf<Int, Offset>()
        val nodeCount = mutableMapOf<Int, Int>()

        for (i in 0 until allTerminals.size) {
            val term = allTerminals[i]
            val nodeId = rootToNodeId[uf.find(i)] ?: 0
            terminalToNode[term] = nodeId

            val prevPos = nodePositions[nodeId] ?: Offset.Zero
            val count = nodeCount[nodeId] ?: 0
            nodePositions[nodeId] = Offset(prevPos.x + term.position.x, prevPos.y + term.position.y)
            nodeCount[nodeId] = count + 1
        }

        // Average positions for clean voltage badge anchor rendering
        for ((nodeId, posSum) in nodePositions.toList()) {
            val count = nodeCount[nodeId] ?: 1
            nodePositions[nodeId] = Offset(posSum.x / count, posSum.y / count)
        }

        // 7. Convert recognized components into CircuitElements for MNA
        val circuitElements = mutableListOf<CircuitElement>()
        var rIdx = 1
        var cIdx = 1
        var lIdx = 1
        var vIdx = 1

        val strokeNodeMap = mutableMapOf<UUID, MutableList<Int>>()

        for (comp in recognizedComponents) {
            val compNodes = comp.terminals.map { terminalToNode[it] ?: 0 }
            for (st in comp.strokes) {
                strokeNodeMap.getOrPut(st.id) { mutableListOf() }.addAll(compNodes)
            }

            if (compNodes.size < 2 && comp.type != SchematicComponentType.GROUND) continue

            val nA = compNodes.getOrNull(0) ?: 0
            val nB = compNodes.getOrNull(1) ?: 0

            // Skip degenerate elements with both pins tied to same node
            if (nA == nB && comp.type != SchematicComponentType.GROUND) continue

            when (comp.type) {
                SchematicComponentType.RESISTOR -> {
                    val name = "R${rIdx++}"
                    val valExpr = comp.valueExpr ?: MathExpr.Number("1000")
                    circuitElements.add(CircuitElement.Resistor(name, nA, nB, valExpr))
                }
                SchematicComponentType.CAPACITOR -> {
                    val name = "C${cIdx++}"
                    val valExpr = comp.valueExpr ?: MathExpr.Number("0.000001") // 1uF
                    circuitElements.add(CircuitElement.Capacitor(name, nA, nB, valExpr))
                }
                SchematicComponentType.INDUCTOR -> {
                    val name = "L${lIdx++}"
                    val valExpr = comp.valueExpr ?: MathExpr.Number("0.001") // 1mH
                    circuitElements.add(CircuitElement.Inductor(name, nA, nB, valExpr))
                }
                SchematicComponentType.VOLTAGE_SOURCE -> {
                    val name = "V${vIdx++}"
                    val valExpr = comp.valueExpr ?: MathExpr.Number("10") // 10V
                    circuitElements.add(CircuitElement.VoltageSource(name, nA, nB, valExpr))
                }
                SchematicComponentType.WIRE,
                SchematicComponentType.GROUND -> {
                    // Wires and Grounds only define topology
                }
            }
        }

        return RecognizedSchematic(
            components = recognizedComponents,
            elements = circuitElements,
            nodePositions = nodePositions,
            strokeNodeMapping = strokeNodeMap
        )
    }

    /**
     * Classifies a single stroke into Resistor, Inductor, Voltage Source, or Wire.
     */
    fun classifySingleStroke(stroke: TimedStroke): RecognizedComponent {
        val geom = stroke.geometry
        val pts = geom.points
        val pStart = Offset(pts.first().x, pts.first().y)
        val pEnd = Offset(pts.last().x, pts.last().y)
        val endDist = hypot(pEnd.x - pStart.x, pEnd.y - pStart.y)
        val arcLen = geom.arcLength
        val pathDensity = arcLen / max(endDist.toDouble(), 1.0)
        val bounds = geom.bounds

        // 1. Voltage Source Check: Closed loop (endpoints close, high circularity)
        val maxBboxDim = max(bounds.width, bounds.height)
        if (endDist <= 0.35f * maxBboxDim && maxBboxDim >= 15f) {
            val area = bounds.width * bounds.height
            val circularity = (4.0 * PI * area) / (arcLen * arcLen)
            if (circularity >= 0.45 && bounds.width / bounds.height in 0.5f..2.0f) {
                // Terminals at top and bottom of circle
                val tTop = SchematicTerminal(stroke.id, 0, Offset(bounds.left + bounds.width / 2f, bounds.top))
                val tBottom = SchematicTerminal(stroke.id, 1, Offset(bounds.left + bounds.width / 2f, bounds.bottom))
                return RecognizedComponent(
                    type = SchematicComponentType.VOLTAGE_SOURCE,
                    strokes = listOf(stroke),
                    bounds = bounds,
                    terminals = listOf(tTop, tBottom)
                )
            }
        }

        // 2. Resistor vs Inductor vs Wire:
        // Compute lateral peaks along the chord connecting pStart to pEnd
        val chordAngle = atan2(pEnd.y - pStart.y, pEnd.x - pStart.x)
        val cosA = cos(-chordAngle)
        val sinA = sin(-chordAngle)

        val lateralOffsets = DoubleArray(pts.size)
        for (i in pts.indices) {
            val dx = pts[i].x - pStart.x
            val dy = pts[i].y - pStart.y
            // Rotated y gives lateral displacement perpendicular to chord
            lateralOffsets[i] = (dx * sinA + dy * cosA).toDouble()
        }

        val lateralSpan = (lateralOffsets.maxOrNull() ?: 0.0) - (lateralOffsets.minOrNull() ?: 0.0)
        val peakThreshold = lateralSpan * 0.25

        var peakCount = 0
        var prevSign = 0
        for (i in 1 until pts.size - 1) {
            val d1 = lateralOffsets[i] - lateralOffsets[i - 1]
            val d2 = lateralOffsets[i + 1] - lateralOffsets[i]
            if (d1 * d2 < 0 && abs(lateralOffsets[i]) >= peakThreshold) {
                val currentSign = if (lateralOffsets[i] > 0) 1 else -1
                if (currentSign != prevSign) {
                    peakCount++
                    prevSign = currentSign
                }
            }
        }

        // Resistor: High path density, 4 to 12 alternating lateral peaks
        if (pathDensity >= 1.6 && peakCount in 4..12) {
            val t1 = SchematicTerminal(stroke.id, 0, pStart)
            val t2 = SchematicTerminal(stroke.id, 1, pEnd)
            return RecognizedComponent(
                type = SchematicComponentType.RESISTOR,
                strokes = listOf(stroke),
                bounds = bounds,
                terminals = listOf(t1, t2)
            )
        }

        // Inductor: 3 to 8 consecutive loops/cusps with significant path length
        if (pathDensity in 1.4..3.2 && peakCount in 3..8 && kotlin.math.max(lateralOffsets.count { it > 0 }, lateralOffsets.count { it < 0 }) >= pts.size * 0.6) {
            val t1 = SchematicTerminal(stroke.id, 0, pStart)
            val t2 = SchematicTerminal(stroke.id, 1, pEnd)
            return RecognizedComponent(
                type = SchematicComponentType.INDUCTOR,
                strokes = listOf(stroke),
                bounds = bounds,
                terminals = listOf(t1, t2)
            )
        }

        // Default: Wire segment
        val t1 = SchematicTerminal(stroke.id, 0, pStart)
        val t2 = SchematicTerminal(stroke.id, 1, pEnd)
        return RecognizedComponent(
            type = SchematicComponentType.WIRE,
            strokes = listOf(stroke),
            bounds = bounds,
            terminals = listOf(t1, t2)
        )
    }

    /**
     * Detects two-stroke capacitors: two parallel strokes of similar length with a narrow gap.
     */
    private fun extractCapacitors(strokes: MutableList<TimedStroke>): List<RecognizedComponent> {
        val capacitors = mutableListOf<RecognizedComponent>()
        val usedIndices = mutableSetOf<Int>()

        for (i in 0 until strokes.size) {
            if (i in usedIndices) continue
            val s1 = strokes[i]
            val g1 = s1.geometry
            if (g1.points.size < 2) continue
            val len1 = g1.arcLength

            val p1s = Offset(g1.points.first().x, g1.points.first().y)
            val p1e = Offset(g1.points.last().x, g1.points.last().y)
            val d1 = hypot(p1e.x - p1s.x, p1e.y - p1s.y)
            // Capacitor plate must be relatively straight
            if (len1 / max(d1.toDouble(), 1.0) > 1.35) continue

            for (j in i + 1 until strokes.size) {
                if (j in usedIndices) continue
                val s2 = strokes[j]
                val g2 = s2.geometry
                if (g2.points.size < 2) continue
                val len2 = g2.arcLength

                val p2s = Offset(g2.points.first().x, g2.points.first().y)
                val p2e = Offset(g2.points.last().x, g2.points.last().y)
                val d2 = hypot(p2e.x - p2s.x, p2e.y - p2s.y)
                if (len2 / max(d2.toDouble(), 1.0) > 1.35) continue

                // Check length similarity: 0.65 to 1.5
                val lengthRatio = len1 / max(len2, 1e-4)
                if (lengthRatio !in 0.65..1.55) continue

                // Check parallelism: angle difference < 25 degrees
                val angle1 = atan2(p1e.y - p1s.y, p1e.x - p1s.x)
                val angle2 = atan2(p2e.y - p2s.y, p2e.x - p2s.x)
                var angleDiff = abs(angle1 - angle2)
                if (angleDiff > PI) angleDiff = (2 * PI - angleDiff).toFloat()
                if (angleDiff > PI / 2) angleDiff = abs(PI - angleDiff).toFloat()

                if (angleDiff <= 0.45) { // ~25 degrees
                    // Distance between plate centers
                    val c1 = Offset((p1s.x + p1e.x) / 2f, (p1s.y + p1e.y) / 2f)
                    val c2 = Offset((p2s.x + p2e.x) / 2f, (p2s.y + p2e.y) / 2f)
                    val plateDist = hypot(c2.x - c1.x, c2.y - c1.y)
                    val avgLen = (len1 + len2) / 2.0

                    // Gap should be 0.15 to 0.7 times plate length
                    if (plateDist in (0.15 * avgLen)..(0.85 * avgLen)) {
                        usedIndices.add(i)
                        usedIndices.add(j)

                        val unionBounds = Rect(
                            min(g1.bounds.left, g2.bounds.left),
                            min(g1.bounds.top, g2.bounds.top),
                            max(g1.bounds.right, g2.bounds.right),
                            max(g1.bounds.bottom, g2.bounds.bottom)
                        )

                        val t1 = SchematicTerminal(s1.id, 0, c1)
                        val t2 = SchematicTerminal(s2.id, 1, c2)

                        capacitors.add(
                            RecognizedComponent(
                                type = SchematicComponentType.CAPACITOR,
                                strokes = listOf(s1, s2),
                                bounds = unionBounds,
                                terminals = listOf(t1, t2)
                            )
                        )
                        break
                    }
                }
            }
        }

        // Remove matched strokes
        val remaining = strokes.filterIndexed { index, _ -> index !in usedIndices }
        strokes.clear()
        strokes.addAll(remaining)

        return capacitors
    }

    /**
     * Detects 3-bar ground symbols stacked vertically with decreasing widths.
     */
    private fun extractGroundSymbols(strokes: MutableList<TimedStroke>): List<RecognizedComponent> {
        val grounds = mutableListOf<RecognizedComponent>()
        if (strokes.size < 3) return grounds

        val used = mutableSetOf<Int>()

        // Look for triples of horizontal bars
        val horizontalStrokes = mutableListOf<Pair<Int, TimedStroke>>()
        for (i in strokes.indices) {
            val s = strokes[i]
            val b = s.geometry.bounds
            if (b.width > b.height * 1.5f && s.geometry.arcLength / max(b.width.toDouble(), 1.0) < 1.4) {
                horizontalStrokes.add(i to s)
            }
        }

        for (i in 0 until horizontalStrokes.size) {
            if (horizontalStrokes[i].first in used) continue
            val (idx1, s1) = horizontalStrokes[i]

            for (j in 0 until horizontalStrokes.size) {
                if (horizontalStrokes[i].first in used) break
                if (i == j || horizontalStrokes[j].first in used) continue
                val (idx2, s2) = horizontalStrokes[j]

                for (k in 0 until horizontalStrokes.size) {
                    if (horizontalStrokes[i].first in used) break
                    if (k == i || k == j || horizontalStrokes[k].first in used) continue
                    val (idx3, s3) = horizontalStrokes[k]

                    // Sort vertically by top Y
                    val triplet = listOf(idx1 to s1, idx2 to s2, idx3 to s3).sortedBy { it.second.geometry.bounds.top }
                    val bTop = triplet[0].second.geometry.bounds
                    val bMid = triplet[1].second.geometry.bounds
                    val bBot = triplet[2].second.geometry.bounds

                    // Check strictly decreasing widths: bTop.width > bMid.width > bBot.width
                    val vGap1 = bMid.top - bTop.bottom
                    val vGap2 = bBot.top - bMid.bottom
                    val maxGap = bTop.width * 0.8f
                    val validGap = vGap1 >= -4f && vGap1 <= maxGap && vGap2 >= -4f && vGap2 <= maxGap

                    if (validGap && bTop.width > bMid.width * 1.05f && bMid.width > bBot.width * 1.05f) {
                        // Check horizontal alignment of centers
                        val cTopX = bTop.left + bTop.width / 2f
                        val cMidX = bMid.left + bMid.width / 2f
                        val cBotX = bBot.left + bBot.width / 2f

                        val maxCenterXDev = max(abs(cTopX - cMidX), abs(cMidX - cBotX))
                        if (maxCenterXDev <= bTop.width * 0.35f) {
                            used.add(triplet[0].first)
                            used.add(triplet[1].first)
                            used.add(triplet[2].first)

                            val unionBounds = Rect(
                                min(bTop.left, min(bMid.left, bBot.left)),
                                bTop.top,
                                max(bTop.right, max(bMid.right, bBot.right)),
                                bBot.bottom
                            )

                            // Terminal is connected at the top center of the top bar
                            val terminal = SchematicTerminal(triplet[0].second.id, 0, Offset(cTopX, bTop.top))

                            grounds.add(
                                RecognizedComponent(
                                    type = SchematicComponentType.GROUND,
                                    strokes = triplet.map { it.second },
                                    bounds = unionBounds,
                                    terminals = listOf(terminal)
                                )
                            )
                            break
                        }
                    }
                }
            }
        }

        val remaining = strokes.filterIndexed { index, _ -> index !in used }
        strokes.clear()
        strokes.addAll(remaining)

        return grounds
    }

    /**
     * Associates nearby handwritten values (e.g. "10k", "5V", "100u") with schematic components.
     */
    private fun associateValuesWithComponents(
        components: List<RecognizedComponent>,
        blocks: List<EquationBlock>
    ) {
        val usedBlockIds = mutableSetOf<UUID>()
        for (comp in components) {
            if (comp.type == SchematicComponentType.WIRE || comp.type == SchematicComponentType.GROUND) continue

            val compCenter = Offset(
                comp.bounds.left + comp.bounds.width / 2f,
                comp.bounds.top + comp.bounds.height / 2f
            )

            var nearestBlock: EquationBlock? = null
            var nearestDist = Float.MAX_VALUE

            for (block in blocks) {
                if (block.id in usedBlockIds) continue
                val blockCenter = Offset(
                    block.bounds.left + block.bounds.width / 2f,
                    block.bounds.top + block.bounds.height / 2f
                )
                val dist = hypot(blockCenter.x - compCenter.x, blockCenter.y - compCenter.y)
                val maxSearchDist = max(comp.bounds.width, comp.bounds.height) * 2.5f + 50f
                if (dist <= maxSearchDist && dist < nearestDist) {
                    nearestDist = dist
                    nearestBlock = block
                }
            }

            if (nearestBlock != null) {
                usedBlockIds.add(nearestBlock.id)
                val latex = nearestBlock.recognizedLatex?.trim().orEmpty()
                comp.label = latex
                comp.valueExpr = parseSchematicValue(latex)
            }
        }
    }

    fun parseSchematicValue(raw: String): MathExpr {
        var cleaned = raw.replace("=", "").trim()
        cleaned = cleaned.replace(Regex("(?<=[0-9GMkmunp])\\s*(?:\\\\Omega|[VFH])\\s*$"), "").trim()
        val expanded = com.axioloom.app.domain.model.EngineeringConstants.expandSiSuffixes(cleaned)
        return try {
            val intent = parser.transpile(expanded).intent
            when (intent) {
                is com.axioloom.app.domain.model.MathIntent.EvaluationQuery -> intent.expression
                is com.axioloom.app.domain.model.MathIntent.Assignment -> intent.rhs
                else -> MathExpr.Number(expanded)
            }
        } catch (_: Exception) {
            val d = expanded.toDoubleOrNull()
            if (d != null) MathExpr.Number(expanded)
            else if (isIdentifier(expanded)) MathExpr.Symbol(expanded)
            else MathExpr.Number("1")
        }
    }

    /**
     * Disjoint-set union-find data structure for electrical node connectivity.
     */
    class DisjointSet(size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size) { 0 }

        fun find(i: Int): Int {
            var root = i
            while (root != parent[root]) {
                root = parent[root]
            }
            var curr = i
            while (curr != root) {
                val nxt = parent[curr]
                parent[curr] = root
                curr = nxt
            }
            return root
        }

        fun union(i: Int, j: Int) {
            val rootI = find(i)
            val rootJ = find(j)
            if (rootI == rootJ) return
            if (rank[rootI] < rank[rootJ]) {
                parent[rootI] = rootJ
            } else if (rank[rootI] > rank[rootJ]) {
                parent[rootJ] = rootI
            } else {
                parent[rootJ] = rootI
                rank[rootI]++
            }
        }
    }
}
