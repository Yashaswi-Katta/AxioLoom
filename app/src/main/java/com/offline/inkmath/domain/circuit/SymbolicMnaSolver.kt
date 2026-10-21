package com.offline.inkmath.domain.circuit

import com.offline.inkmath.domain.cas.OfflineCasEngine
import com.offline.inkmath.domain.model.EngineeringConstants
import com.offline.inkmath.domain.model.MathExpr
import com.offline.inkmath.domain.model.MathIntent
import com.offline.inkmath.domain.model.SymjaCompiler
import com.offline.inkmath.domain.parser.LatexToSymjaTranspiler
import java.util.Locale
import kotlin.math.max

/**
 * Netlist circuit primitives for Modified Nodal Analysis (MNA).
 */
sealed class CircuitElement(val id: String) {
    data class Resistor(val name: String, val nodeA: Int, val nodeB: Int, val value: MathExpr) : CircuitElement(name)
    data class Capacitor(val name: String, val nodeA: Int, val nodeB: Int, val value: MathExpr) : CircuitElement(name)
    data class Inductor(val name: String, val nodeA: Int, val nodeB: Int, val value: MathExpr) : CircuitElement(name)
    data class CurrentSource(val name: String, val nodeFrom: Int, val nodeTo: Int, val current: MathExpr) : CircuitElement(name)
    data class VoltageSource(val name: String, val nodePos: Int, val nodeNeg: Int, val voltage: MathExpr) : CircuitElement(name)
    data class Vccs(
        val name: String,
        val outPos: Int,
        val outNeg: Int,
        val ctrlPos: Int,
        val ctrlNeg: Int,
        val transconductance: MathExpr
    ) : CircuitElement(name)
}

data class MnaResult(
    val gMatrixLatex: String,
    val iVectorLatex: String,
    val unknownNames: List<String>,
    val nodeVoltages: Map<Int, String>,
    val sourceCurrents: Map<String, String>,
    val theveninVoltage: String? = null,
    val theveninImpedance: String? = null
)

/**
 * Symbolic Modified Nodal Analysis (MNA) Engine.
 * Formulates the exact G * x = i matrix equations for RLC networks with independent
 * and dependent sources, solves node voltages via OfflineCasEngine, and calculates
 * Thevenin/Norton equivalents across any port.
 */
class SymbolicMnaSolver(private val cas: OfflineCasEngine) {

    private val parser = LatexToSymjaTranspiler()

    /**
     * Parses a text netlist into a list of CircuitElements.
     * Supports formats:
     *   R1 1 2 1000
     *   R2 2 0 1k
     *   C1 2 0 10u
     *   L1 1 2 100m
     *   V1 1 0 10
     *   I1 0 1 2
     *   G1 2 0 1 0 0.05
     * Also supports symbolic values like "R_1", "V_in", "C", "L".
     */
    fun parseNetlist(text: String): List<CircuitElement> {
        val elements = mutableListOf<CircuitElement>()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }

        for (line in lines) {
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 4) continue
            val name = parts[0]
            val typeChar = name.first().uppercaseChar()

            when (typeChar) {
                'R' -> {
                    val nA = parts[1].toIntOrNull() ?: continue
                    val nB = parts[2].toIntOrNull() ?: continue
                    val valExpr = parseValue(parts.subList(3, parts.size).joinToString(" "))
                    elements.add(CircuitElement.Resistor(name, nA, nB, valExpr))
                }
                'C' -> {
                    val nA = parts[1].toIntOrNull() ?: continue
                    val nB = parts[2].toIntOrNull() ?: continue
                    val valExpr = parseValue(parts.subList(3, parts.size).joinToString(" "))
                    elements.add(CircuitElement.Capacitor(name, nA, nB, valExpr))
                }
                'L' -> {
                    val nA = parts[1].toIntOrNull() ?: continue
                    val nB = parts[2].toIntOrNull() ?: continue
                    val valExpr = parseValue(parts.subList(3, parts.size).joinToString(" "))
                    elements.add(CircuitElement.Inductor(name, nA, nB, valExpr))
                }
                'V' -> {
                    val nPos = parts[1].toIntOrNull() ?: continue
                    val nNeg = parts[2].toIntOrNull() ?: continue
                    val valExpr = parseValue(parts.subList(3, parts.size).joinToString(" "))
                    elements.add(CircuitElement.VoltageSource(name, nPos, nNeg, valExpr))
                }
                'I' -> {
                    val nFrom = parts[1].toIntOrNull() ?: continue
                    val nTo = parts[2].toIntOrNull() ?: continue
                    val valExpr = parseValue(parts.subList(3, parts.size).joinToString(" "))
                    elements.add(CircuitElement.CurrentSource(name, nFrom, nTo, valExpr))
                }
                'G' -> {
                    if (parts.size >= 6) {
                        val outPos = parts[1].toIntOrNull() ?: continue
                        val outNeg = parts[2].toIntOrNull() ?: continue
                        val ctrlPos = parts[3].toIntOrNull() ?: continue
                        val ctrlNeg = parts[4].toIntOrNull() ?: continue
                        val valExpr = parseValue(parts.subList(5, parts.size).joinToString(" "))
                        elements.add(CircuitElement.Vccs(name, outPos, outNeg, ctrlPos, ctrlNeg, valExpr))
                    }
                }
            }
        }
        return elements
    }

    private fun parseValue(rawStr: String): MathExpr {
        val expanded = EngineeringConstants.expandSiSuffixes(rawStr.trim())
        return try {
            val transpiled = parser.transpile(expanded)
            when (val intent = transpiled.intent) {
                is MathIntent.EvaluationQuery -> intent.expression
                is MathIntent.Assignment -> intent.rhs
                else -> MathExpr.Symbol(rawStr.trim())
            }
        } catch (_: Exception) {
            val num = rawStr.toDoubleOrNull()
            if (num != null) MathExpr.Number(rawStr) else MathExpr.Symbol(rawStr)
        }
    }

    /**
     * Solves the MNA system symbolically using OfflineCasEngine.
     * Optionally computes Thevenin voltage and impedance across port [theveninPort] = (nodeA, nodeB).
     */
    suspend fun solve(
        elements: List<CircuitElement>,
        theveninPort: Pair<Int, Int>? = null
    ): MnaResult {
        // 1. Identify non-ground nodes and independent voltage sources
        var maxNode = 0
        val vSources = mutableListOf<CircuitElement.VoltageSource>()

        for (el in elements) {
            when (el) {
                is CircuitElement.Resistor -> { maxNode = max(maxNode, max(el.nodeA, el.nodeB)) }
                is CircuitElement.Capacitor -> { maxNode = max(maxNode, max(el.nodeA, el.nodeB)) }
                is CircuitElement.Inductor -> { maxNode = max(maxNode, max(el.nodeA, el.nodeB)) }
                is CircuitElement.CurrentSource -> { maxNode = max(maxNode, max(el.nodeFrom, el.nodeTo)) }
                is CircuitElement.VoltageSource -> {
                    maxNode = max(maxNode, max(el.nodePos, el.nodeNeg))
                    vSources.add(el)
                }
                is CircuitElement.Vccs -> {
                    maxNode = max(maxNode, max(max(el.outPos, el.outNeg), max(el.ctrlPos, el.ctrlNeg)))
                }
            }
        }

        val numNodes = maxNode // non-ground nodes: 1 .. numNodes
        val numV = vSources.size
        val dim = numNodes + numV

        if (dim == 0) {
            return MnaResult("[]", "[]", emptyList(), emptyMap(), emptyMap())
        }

        // Unknown names: V_1 .. V_numNodes, I_{V1} .. I_{VnumV}
        val unknownNames = mutableListOf<String>()
        for (n in 1..numNodes) unknownNames.add("V_$n")
        for (v in vSources) unknownNames.add("I_{${v.name}}")

        // 2. Build G and i symbolically
        val (gMatrix, iVector) = buildMnaSystem(elements, numNodes, vSources, dim)

        // 3. Solve via CAS: LinearSolve(G, i)
        val solvedValues = solveLinearSystem(gMatrix, iVector, dim)

        val nodeVoltages = mutableMapOf<Int, String>()
        for (n in 1..numNodes) {
            nodeVoltages[n] = solvedValues.getOrElse(n - 1) { "0" }
        }

        val sourceCurrents = mutableMapOf<String, String>()
        for ((idx, v) in vSources.withIndex()) {
            sourceCurrents[v.name] = solvedValues.getOrElse(numNodes + idx) { "0" }
        }

        // 4. Compute Thevenin equivalent if port specified
        var vTh: String? = null
        var zTh: String? = null

        if (theveninPort != null) {
            val (nA, nB) = theveninPort
            val vA = if (nA == 0) "0" else (nodeVoltages[nA] ?: "0")
            val vB = if (nB == 0) "0" else (nodeVoltages[nB] ?: "0")

            // Vth = Simplify(vA - vB)
            val vThRaw = cas.evaluate(MathIntent.EvaluationQuery(
                MathExpr.Binary(MathExpr.Op.SUBTRACT, parser.transpile(vA).intent.let { (it as MathIntent.EvaluationQuery).expression },
                                                      parser.transpile(vB).intent.let { (it as MathIntent.EvaluationQuery).expression })
            )).expression
            vTh = vThRaw

            // Zth: Zero all independent sources, inject 1A test source from nB to nA
            val passiveElements = elements.mapNotNull { el ->
                when (el) {
                    is CircuitElement.VoltageSource -> CircuitElement.VoltageSource(el.name, el.nodePos, el.nodeNeg, MathExpr.Number("0"))
                    is CircuitElement.CurrentSource -> null // Open circuit
                    else -> el
                }
            }.toMutableList()

            // Test current: enters nA, leaves nB
            passiveElements.add(CircuitElement.CurrentSource("__Itest", nodeFrom = nB, nodeTo = nA, current = MathExpr.Number("1")))

            val testRes = solve(passiveElements, theveninPort = null)
            val vTestA = if (nA == 0) "0" else (testRes.nodeVoltages[nA] ?: "0")
            val vTestB = if (nB == 0) "0" else (testRes.nodeVoltages[nB] ?: "0")
            val zThRaw = cas.evaluate(MathIntent.EvaluationQuery(
                MathExpr.Binary(MathExpr.Op.SUBTRACT, parser.transpile(vTestA).intent.let { (it as MathIntent.EvaluationQuery).expression },
                                                      parser.transpile(vTestB).intent.let { (it as MathIntent.EvaluationQuery).expression })
            )).expression
            zTh = zThRaw
        }

        val gLatex = formatMatrixLatex(gMatrix, dim)
        val iLatex = formatVectorLatex(iVector)

        return MnaResult(
            gMatrixLatex = gLatex,
            iVectorLatex = iLatex,
            unknownNames = unknownNames,
            nodeVoltages = nodeVoltages,
            sourceCurrents = sourceCurrents,
            theveninVoltage = vTh,
            theveninImpedance = zTh
        )
    }

    private fun buildMnaSystem(
        elements: List<CircuitElement>,
        numNodes: Int,
        vSources: List<CircuitElement.VoltageSource>,
        dim: Int
    ): Pair<Array<Array<MathExpr>>, Array<MathExpr>> {
        val zero = MathExpr.Number("0")
        val g = Array(dim) { Array<MathExpr>(dim) { zero } }
        val iVec = Array<MathExpr>(dim) { zero }

        fun addAdmittance(n1: Int, n2: Int, y: MathExpr) {
            if (n1 > 0) g[n1 - 1][n1 - 1] = add(g[n1 - 1][n1 - 1], y)
            if (n2 > 0) g[n2 - 1][n2 - 1] = add(g[n2 - 1][n2 - 1], y)
            if (n1 > 0 && n2 > 0) {
                g[n1 - 1][n2 - 1] = sub(g[n1 - 1][n2 - 1], y)
                g[n2 - 1][n1 - 1] = sub(g[n2 - 1][n1 - 1], y)
            }
        }

        for (el in elements) {
            when (el) {
                is CircuitElement.Resistor -> {
                    // Y = 1 / R
                    val y = MathExpr.Binary(MathExpr.Op.DIVIDE, MathExpr.Number("1"), el.value)
                    addAdmittance(el.nodeA, el.nodeB, y)
                }
                is CircuitElement.Capacitor -> {
                    // Y = s * C
                    val y = MathExpr.Binary(MathExpr.Op.MULTIPLY, MathExpr.Symbol("s"), el.value)
                    addAdmittance(el.nodeA, el.nodeB, y)
                }
                is CircuitElement.Inductor -> {
                    // Y = 1 / (s * L)
                    val sL = MathExpr.Binary(MathExpr.Op.MULTIPLY, MathExpr.Symbol("s"), el.value)
                    val y = MathExpr.Binary(MathExpr.Op.DIVIDE, MathExpr.Number("1"), sL)
                    addAdmittance(el.nodeA, el.nodeB, y)
                }
                is CircuitElement.CurrentSource -> {
                    // Current leaves nodeFrom, enters nodeTo
                    if (el.nodeFrom > 0) iVec[el.nodeFrom - 1] = sub(iVec[el.nodeFrom - 1], el.current)
                    if (el.nodeTo > 0) iVec[el.nodeTo - 1] = add(iVec[el.nodeTo - 1], el.current)
                }
                is CircuitElement.VoltageSource -> {
                    // Stamped below by index in vSources
                }
                is CircuitElement.Vccs -> {
                    // Current leaves outPos, enters outNeg
                    val gm = el.transconductance
                    if (el.outPos > 0) {
                        if (el.ctrlPos > 0) g[el.outPos - 1][el.ctrlPos - 1] = add(g[el.outPos - 1][el.ctrlPos - 1], gm)
                        if (el.ctrlNeg > 0) g[el.outPos - 1][el.ctrlNeg - 1] = sub(g[el.outPos - 1][el.ctrlNeg - 1], gm)
                    }
                    if (el.outNeg > 0) {
                        if (el.ctrlPos > 0) g[el.outNeg - 1][el.ctrlPos - 1] = sub(g[el.outNeg - 1][el.ctrlPos - 1], gm)
                        if (el.ctrlNeg > 0) g[el.outNeg - 1][el.ctrlNeg - 1] = add(g[el.outNeg - 1][el.ctrlNeg - 1], gm)
                    }
                }
            }
        }

        // Stamp voltage sources
        val one = MathExpr.Number("1")
        for ((idx, v) in vSources.withIndex()) {
            val row = numNodes + idx
            if (v.nodePos > 0) {
                g[v.nodePos - 1][row] = one
                g[row][v.nodePos - 1] = one
            }
            if (v.nodeNeg > 0) {
                g[v.nodeNeg - 1][row] = MathExpr.Unary(true, one)
                g[row][v.nodeNeg - 1] = MathExpr.Unary(true, one)
            }
            iVec[row] = v.voltage
        }

        return g to iVec
    }

    private suspend fun solveLinearSystem(
        g: Array<Array<MathExpr>>,
        iVec: Array<MathExpr>,
        dim: Int
    ): List<String> {
        // Render Symja LinearSolve command: LinearSolve({{g11, g12, ...}, ...}, {i1, i2, ...})
        val gRows = g.map { row ->
            row.joinToString(",", "{", "}") { SymjaCompiler.compile(MathIntent.EvaluationQuery(it)).command }
        }.joinToString(",", "{", "}")

        val iList = iVec.joinToString(",", "{", "}") { SymjaCompiler.compile(MathIntent.EvaluationQuery(it)).command }
        val cmd = "Together(LinearSolve($gRows,$iList))"

        val rawResult = cas.evalRaw(cmd)
        // Parse list result from Symja: {sol1, sol2, ...}
        val clean = rawResult.trim().removePrefix("{").removeSuffix("}").trim()
        if (clean.isBlank()) return List(dim) { "0" }

        return splitSymjaList(clean)
    }

    private fun splitSymjaList(listStr: String): List<String> {
        val results = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in 0 until listStr.length) {
            val c = listStr[i]
            if (c == '(' || c == '{' || c == '[') depth++
            else if (c == ')' || c == '}' || c == ']') depth--
            else if (c == ',' && depth == 0) {
                results.add(listStr.substring(start, i).trim())
                start = i + 1
            }
        }
        if (start < listStr.length) {
            results.add(listStr.substring(start).trim())
        }
        return results
    }

    private fun add(a: MathExpr, b: MathExpr): MathExpr {
        if (a is MathExpr.Number && a.value == "0") return b
        if (b is MathExpr.Number && b.value == "0") return a
        return MathExpr.Binary(MathExpr.Op.ADD, a, b)
    }

    private fun sub(a: MathExpr, b: MathExpr): MathExpr {
        if (b is MathExpr.Number && b.value == "0") return a
        if (a is MathExpr.Number && a.value == "0") return MathExpr.Unary(true, b)
        return MathExpr.Binary(MathExpr.Op.SUBTRACT, a, b)
    }

    private fun formatMatrixLatex(matrix: Array<Array<MathExpr>>, dim: Int): String {
        val rows = matrix.joinToString(" \\\\ ") { row ->
            row.joinToString(" & ") { expr ->
                when {
                    expr is MathExpr.Number -> expr.value
                    expr is MathExpr.Symbol -> expr.name
                    expr is MathExpr.Unary && expr.value is MathExpr.Number -> "-${(expr.value as MathExpr.Number).value}"
                    else -> SymjaCompiler.compile(MathIntent.EvaluationQuery(expr)).command
                }
            }
        }
        return "\\begin{bmatrix} $rows \\end{bmatrix}"
    }

    private fun formatVectorLatex(vector: Array<MathExpr>): String {
        val rows = vector.joinToString(" \\\\ ") { expr ->
            when {
                expr is MathExpr.Number -> expr.value
                expr is MathExpr.Symbol -> expr.name
                else -> SymjaCompiler.compile(MathIntent.EvaluationQuery(expr)).command
            }
        }
        return "\\begin{bmatrix} $rows \\end{bmatrix}"
    }
}
