package com.axioloom.app.domain.logic

import kotlin.math.pow

data class Implicant(
    val pattern: String, // e.g. "-0-0"
    val minterms: Set<Int>,
    var isUsed: Boolean = false
) {
    val varCount: Int get() = pattern.length
    val onesCount: Int get() = pattern.count { it == '1' }
}

data class TruthTableRow(
    val index: Int,
    val binary: String,
    val output: String // "0", "1", or "X"
)

data class KMapCell(
    val rowIdx: Int,
    val colIdx: Int,
    val minterm: Int,
    val value: String
)

data class KMapLoop(
    val minterms: Set<Int>,
    val label: String,
    val colorHex: Long
)

data class MinimizationResult(
    val numVars: Int,
    val varNames: List<String>,
    val minterms: Set<Int>,
    val dontCares: Set<Int>,
    val primeImplicants: List<String>,
    val essentialPrimeImplicants: List<String>,
    val minimalSopLatex: String,
    val minimalPosLatex: String,
    val truthTable: List<TruthTableRow>,
    val loops: List<KMapLoop>
)

/**
 * Exact Boolean Minimizer using Quine-McCluskey and Petrick's Method.
 * Supports up to 4 variables (A, B, C, D) with full Don't-Care support.
 * Generates minimal Sum of Products (SOP), Product of Sums (POS), and K-Map Gray-code grids.
 */
object KarnaughMinimizer {

    private val DEFAULT_VARS = listOf("A", "B", "C", "D")

    val LOOP_COLORS = listOf(
        0xFFFFB300L, // Warm Amber
        0xFFFFD54FL, // Gold
        0xFFDC143CL, // Crimson
        0xFF00E5FFL, // Cyan
        0xFF00E676L, // Green
        0xFFFF4081L  // Pink
    )

    /**
     * Minimizes boolean function given minterms, optional don't-cares, and number of variables (2..4).
     */
    fun minimize(
        minterms: Set<Int>,
        dontCares: Set<Int> = emptySet(),
        numVars: Int = 4,
        customVarNames: List<String>? = null
    ): MinimizationResult {
        require(numVars in 2..4) { "K-Map supported for 2, 3, or 4 variables" }
        val maxIndex = (1 shl numVars) - 1
        require(minterms.all { it in 0..maxIndex }) { "Minterm exceeds variable count range" }
        require(dontCares.all { it in 0..maxIndex }) { "Don't-care term exceeds range" }

        val vars = customVarNames?.take(numVars) ?: DEFAULT_VARS.take(numVars)

        // 1. Quine-McCluskey to find Prime Implicants for SOP
        val allOnTerms = minterms + dontCares
        val primeImplicants = findPrimeImplicants(allOnTerms, numVars)

        // 2. Petrick's method to find minimal SOP cover of minterms
        val minimalCover = findMinimalCover(primeImplicants, minterms)

        // 3. Dualize for POS: find minimal SOP of 0-terms (maxterms)
        val allMaxterms = (0..maxIndex).filter { it !in allOnTerms }.toSet()
        val maxtermPIs = findPrimeImplicants(allMaxterms + dontCares, numVars)
        val minimalPosCover = findMinimalCover(maxtermPIs, allMaxterms)

        val sopLatex = formatSopLatex(minimalCover, vars)
        val posLatex = formatPosLatex(minimalPosCover, vars)

        // 4. Generate full truth table
        val truthTable = (0..maxIndex).map { idx ->
            val bin = idx.toString(2).padStart(numVars, '0')
            val out = when {
                idx in minterms -> "1"
                idx in dontCares -> "X"
                else -> "0"
            }
            TruthTableRow(idx, bin, out)
        }

        // 5. Generate K-Map Loops for visual overlay
        val loops = minimalCover.mapIndexed { idx, imp ->
            val color = LOOP_COLORS[idx % LOOP_COLORS.size]
            val termLatex = formatImplicantSop(imp.pattern, vars)
            KMapLoop(imp.minterms.filter { it in minterms }.toSet(), termLatex, color)
        }

        val epiPatterns = findEssentialPrimeImplicants(primeImplicants, minterms).map { it.pattern }

        return MinimizationResult(
            numVars = numVars,
            varNames = vars,
            minterms = minterms,
            dontCares = dontCares,
            primeImplicants = primeImplicants.map { it.pattern },
            essentialPrimeImplicants = epiPatterns,
            minimalSopLatex = sopLatex,
            minimalPosLatex = posLatex,
            truthTable = truthTable,
            loops = loops
        )
    }

    private fun findPrimeImplicants(terms: Set<Int>, numVars: Int): List<Implicant> {
        if (terms.isEmpty()) return emptyList()

        // Initial 0-th order implicants
        var currentGroups = terms.map { idx ->
            val pat = idx.toString(2).padStart(numVars, '0')
            Implicant(pat, setOf(idx))
        }

        val primeImplicants = mutableListOf<Implicant>()

        while (currentGroups.isNotEmpty()) {
            val nextGroups = mutableListOf<Implicant>()
            val combinedPatterns = mutableSetOf<String>()

            for (i in 0 until currentGroups.size) {
                for (j in i + 1 until currentGroups.size) {
                    val a = currentGroups[i]
                    val b = currentGroups[j]
                    val diffIdx = singleBitDiff(a.pattern, b.pattern)
                    if (diffIdx >= 0) {
                        a.isUsed = true
                        b.isUsed = true
                        val newPat = a.pattern.substring(0, diffIdx) + '-' + a.pattern.substring(diffIdx + 1)
                        if (combinedPatterns.add(newPat)) {
                            nextGroups.add(Implicant(newPat, a.minterms + b.minterms))
                        }
                    }
                }
            }

            for (imp in currentGroups) {
                if (!imp.isUsed) {
                    if (primeImplicants.none { it.pattern == imp.pattern }) {
                        primeImplicants.add(imp)
                    }
                }
            }

            currentGroups = nextGroups
        }

        return primeImplicants
    }

    private fun singleBitDiff(a: String, b: String): Int {
        var diffIndex = -1
        for (i in a.indices) {
            if (a[i] != b[i]) {
                if ((a[i] == '0' && b[i] == '1') || (a[i] == '1' && b[i] == '0')) {
                    if (diffIndex != -1) return -1
                    diffIndex = i
                } else {
                    return -1
                }
            }
        }
        return diffIndex
    }

    private fun findEssentialPrimeImplicants(
        pis: List<Implicant>,
        minterms: Set<Int>
    ): List<Implicant> {
        val epis = mutableListOf<Implicant>()
        for (m in minterms) {
            val covering = pis.filter { it.minterms.contains(m) }
            if (covering.size == 1) {
                val epi = covering.first()
                if (epi !in epis) epis.add(epi)
            }
        }
        return epis
    }

    private fun findMinimalCover(
        pis: List<Implicant>,
        targetMinterms: Set<Int>
    ): List<Implicant> {
        if (targetMinterms.isEmpty()) return emptyList()

        val epis = findEssentialPrimeImplicants(pis, targetMinterms)
        val covered = epis.flatMap { it.minterms }.toSet()
        val remainingMinterms = targetMinterms.filter { it !in covered }

        if (remainingMinterms.isEmpty()) return epis

        val remainingPis = pis.filter { it !in epis }

        // Petrick's Method / Branch-and-bound minimal set cover
        val bestSolution = mutableListOf<Implicant>()
        var minTerms = Int.MAX_VALUE

        fun search(index: Int, currentCovered: Set<Int>, currentSelection: List<Implicant>) {
            if (remainingMinterms.all { it in currentCovered }) {
                if (currentSelection.size < minTerms) {
                    minTerms = currentSelection.size
                    bestSolution.clear()
                    bestSolution.addAll(currentSelection)
                }
                return
            }
            if (currentSelection.size >= minTerms || index >= remainingPis.size) return

            // Include remainingPis[index]
            val nextCovered = currentCovered + remainingPis[index].minterms
            search(index + 1, nextCovered, currentSelection + remainingPis[index])

            // Exclude remainingPis[index]
            search(index + 1, currentCovered, currentSelection)
        }

        search(0, covered, emptyList())

        return (epis + bestSolution).distinctBy { it.pattern }
    }

    private fun formatSopLatex(implicants: List<Implicant>, vars: List<String>): String {
        if (implicants.isEmpty()) return "0"
        return implicants.joinToString(" + ") { formatImplicantSop(it.pattern, vars) }
    }

    private fun formatImplicantSop(pattern: String, vars: List<String>): String {
        val literals = mutableListOf<String>()
        for (i in pattern.indices) {
            val v = vars.getOrElse(i) { "X$i" }
            when (pattern[i]) {
                '1' -> literals.add(v)
                '0' -> literals.add("\\overline{$v}")
                else -> Unit
            }
        }
        return if (literals.isEmpty()) "1" else literals.joinToString(" ")
    }

    private fun formatPosLatex(implicants: List<Implicant>, vars: List<String>): String {
        if (implicants.isEmpty()) return "1"
        return implicants.joinToString(" ") { imp ->
            val sumTerms = mutableListOf<String>()
            for (i in imp.pattern.indices) {
                val v = vars.getOrElse(i) { "X$i" }
                when (imp.pattern[i]) {
                    '1' -> sumTerms.add("\\overline{$v}")
                    '0' -> sumTerms.add(v)
                    else -> Unit
                }
            }
            if (sumTerms.isEmpty()) "0" else "(${sumTerms.joinToString(" + ")})"
        }
    }

    /**
     * Parses a boolean / minterm expression like "\operatorname{kmap}(4, \sum m(0, 1, 2, 5, 8, 9, 10))"
     * or "kmap(4, m(0, 1, 2), d(5))"
     */
    fun parseKmapCommand(raw: String): MinimizationResult? {
        val clean = raw.trim()
        val numVars = when {
            clean.contains("4") -> 4
            clean.contains("3") -> 3
            clean.contains("2") -> 2
            else -> 4
        }

        val minterms = mutableSetOf<Int>()
        val dontCares = mutableSetOf<Int>()

        val mRegex = Regex("m\\s*\\(?\\s*([0-9,\\s]+)\\)?", RegexOption.IGNORE_CASE)
        mRegex.find(clean)?.let { match ->
            match.groupValues[1].split(Regex("[,\\s]+")).mapNotNull { it.toIntOrNull() }.forEach {
                minterms.add(it)
            }
        }

        val dRegex = Regex("d\\s*\\(?\\s*([0-9,\\s]+)\\)?", RegexOption.IGNORE_CASE)
        dRegex.find(clean)?.let { match ->
            match.groupValues[1].split(Regex("[,\\s]+")).mapNotNull { it.toIntOrNull() }.forEach {
                dontCares.add(it)
            }
        }

        if (minterms.isEmpty()) {
            // Check direct numbers in arguments
            val nums = Regex("[0-9]+").findAll(clean).map { it.value.toInt() }.toList()
            if (nums.size > 1) {
                minterms.addAll(nums.drop(1))
            }
        }

        if (minterms.isEmpty()) return null
        return minimize(minterms, dontCares, numVars)
    }

    /**
     * Returns Gray code order for rows and columns.
     */
    fun getGrayCodeOrder(bits: Int): List<Pair<Int, String>> = when (bits) {
        1 -> listOf(0 to "0", 1 to "1")
        2 -> listOf(0 to "00", 1 to "01", 3 to "11", 2 to "10")
        else -> listOf(0 to "0")
    }

    /**
     * Builds K-Map grid cells for 2, 3, or 4 variables.
     */
    fun buildGrid(numVars: Int, minterms: Set<Int>, dontCares: Set<Int>): List<List<KMapCell>> {
        val rowBits = if (numVars == 4) 2 else 1
        val colBits = if (numVars == 2) 1 else 2

        val rowGray = getGrayCodeOrder(rowBits)
        val colGray = getGrayCodeOrder(colBits)

        return rowGray.mapIndexed { rIdx, (rVal, _) ->
            colGray.mapIndexed { cIdx, (cVal, _) ->
                val minterm = (rVal shl colBits) or cVal
                val v = when {
                    minterm in minterms -> "1"
                    minterm in dontCares -> "X"
                    else -> "0"
                }
                KMapCell(rIdx, cIdx, minterm, v)
            }
        }
    }
}
