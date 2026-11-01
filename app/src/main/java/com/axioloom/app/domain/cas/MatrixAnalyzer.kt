package com.axioloom.app.domain.cas

import com.axioloom.app.domain.model.BindingSnapshot
import com.axioloom.app.domain.model.MathExpr
import com.axioloom.app.domain.model.SymjaCompiler

data class MatrixAnalysisResult(
    val matrixLatex: String,
    val dimension: Int,
    val isSquare: Boolean,
    val determinant: String,
    val trace: String,
    val characteristicPolynomial: String,
    val characteristicPolynomialRaw: String,
    val eigenvalues: List<String>,
    val numericEigenvalues: List<Double>,
    val eigenvectors: List<List<String>>,
    val modalMatrixP: String?,
    val diagonalMatrixD: String?,
    val inverseModalMatrixPInv: String?,
    val isDiagonalizable: Boolean,
    val cayleyHamiltonEquation: String,
    val cayleyHamiltonVerified: Boolean,
    val cayleyHamiltonInverseFormula: String,
    val stepsLatex: List<String>
)

/**
 * Symbolic matrix analyzer computing Characteristic Polynomial, Eigenvalues, Eigenvectors,
 * Diagonalization (A = P D P^-1), and Cayley-Hamilton theorem verification.
 */
class MatrixAnalyzer(private val cas: OfflineCasEngine) {

    suspend fun analyzeMatrix(
        matrixExpr: MathExpr.Matrix,
        bindings: BindingSnapshot = BindingSnapshot()
    ): MatrixAnalysisResult {
        val compiled = SymjaCompiler.compile(com.axioloom.app.domain.model.MathIntent.EvaluationQuery(matrixExpr), bindings)
        return analyzeSymjaMatrix(compiled.command, matrixExpr.rows.size, matrixExpr.rows.firstOrNull()?.size ?: 0, compiled.latexAliases)
    }

    suspend fun analyzeMatrix(rows: List<List<Double>>): MatrixAnalysisResult {
        require(rows.isNotEmpty() && rows.first().isNotEmpty()) { "Matrix cannot be empty" }
        val symjaCmd = rows.joinToString(",", "{", "}") { row ->
            row.joinToString(",", "{", "}") { it.toString() }
        }
        return analyzeSymjaMatrix(symjaCmd, rows.size, rows.first().size)
    }

    suspend fun analyzeSymjaMatrix(
        symjaMatrix: String,
        numRows: Int,
        numCols: Int,
        aliases: Map<String, String> = emptyMap()
    ): MatrixAnalysisResult {
        val matrixLatex = cas.evalCommandToLatex(symjaMatrix, aliases)
        val isSquare = numRows == numCols && numRows > 0
        val dimension = numRows

        if (!isSquare) {
            return MatrixAnalysisResult(
                matrixLatex = matrixLatex,
                dimension = dimension,
                isSquare = false,
                determinant = "\\text{N/A}",
                trace = "\\text{N/A}",
                characteristicPolynomial = "\\text{N/A}",
                characteristicPolynomialRaw = "N/A",
                eigenvalues = emptyList(),
                numericEigenvalues = emptyList(),
                eigenvectors = emptyList(),
                modalMatrixP = null,
                diagonalMatrixD = null,
                inverseModalMatrixPInv = null,
                isDiagonalizable = false,
                cayleyHamiltonEquation = "\\text{Only square matrices have a characteristic polynomial}",
                cayleyHamiltonVerified = false,
                cayleyHamiltonInverseFormula = "\\text{N/A}",
                stepsLatex = listOf("Matrix is rectangular ($numRows \\times $numCols); eigenvalue decomposition requires a square matrix.")
            )
        }

        val detLatex = cas.evalCommandToLatex("Det($symjaMatrix)", aliases)
        val trLatex = cas.evalCommandToLatex("Tr($symjaMatrix)", aliases)

        // 1. Characteristic Polynomial p(\lambda) = Det(A - \lambda * I)
        val polyRaw = cas.evalRaw("Expand(CharacteristicPolynomial($symjaMatrix, lambda))")
        val coeffRaw = cas.evalRaw("CoefficientList($polyRaw, lambda)")
        val coeffs = parseSymjaList(coeffRaw)
        val polyLatex = formatCharacteristicPolynomialFromCoeffs(coeffs)

        // 2. Eigenvalues
        val eigRaw = cas.evalRaw("Eigenvalues($symjaMatrix)")
        val eigenvaluesList = parseSymjaList(eigRaw)
        val numEigRaw = cas.evalRaw("N(Eigenvalues($symjaMatrix))")
        val numEigenvaluesList = parseSymjaNumericList(numEigRaw)

        // 3. Eigenvectors
        val eigVecsRaw = cas.evalRaw("Eigenvectors($symjaMatrix)")
        val eigenvectorsList = parseSymja2DList(eigVecsRaw)

        // 4. Modal Matrix P and Diagonal Matrix D
        var modalP: String? = null
        var diagD: String? = null
        var invP: String? = null
        var isDiag = false

        if (eigenvectorsList.size == dimension && eigenvectorsList.all { it.size == dimension }) {
            // Check if vectors are linearly independent (det(P) != 0)
            // Symja Eigenvectors returns vectors as rows, so transpose to get modal matrix P
            val pCmd = "Transpose($eigVecsRaw)"
            val pDet = cas.evalRaw("Det($pCmd)")
            if (pDet != "0") {
                isDiag = true
                modalP = cas.evalCommandToLatex(pCmd, aliases)
                diagD = cas.evalCommandToLatex("DiagonalMatrix($eigRaw)", aliases)
                invP = cas.evalCommandToLatex("Inverse($pCmd)", aliases)
            }
        }

        // 5. Cayley-Hamilton Verification: p(A) == 0
        // Substitute A into characteristic polynomial
        val zeroMatrixRaw = cas.evalRaw("ConstantArray(0, {$dimension, $dimension})")
        val (chEquation, chVerified, chInverse) = verifyCayleyHamilton(symjaMatrix, polyRaw, dimension, detLatex)

        val steps = mutableListOf<String>()
        steps += "1. Characteristic equation: p(\\lambda) = \\det(A - \\lambda I) = $polyLatex = 0"
        steps += "2. Eigenvalues: \\lambda \\in \\{${eigenvaluesList.joinToString(", ")}\\}"
        steps += "3. Cayley-Hamilton Theorem: $chEquation = \\mathbf{0}"
        if (chVerified && chInverse.isNotEmpty()) {
            steps += "4. Matrix inverse via Cayley-Hamilton: $chInverse"
        }
        if (isDiag && modalP != null && diagD != null) {
            steps += "5. Diagonalization: A = P D P^{-1} where P = $modalP, D = $diagD"
        }

        return MatrixAnalysisResult(
            matrixLatex = matrixLatex,
            dimension = dimension,
            isSquare = true,
            determinant = detLatex,
            trace = trLatex,
            characteristicPolynomial = polyLatex,
            characteristicPolynomialRaw = polyRaw,
            eigenvalues = eigenvaluesList,
            numericEigenvalues = numEigenvaluesList,
            eigenvectors = eigenvectorsList,
            modalMatrixP = modalP,
            diagonalMatrixD = diagD,
            inverseModalMatrixPInv = invP,
            isDiagonalizable = isDiag,
            cayleyHamiltonEquation = chEquation,
            cayleyHamiltonVerified = chVerified,
            cayleyHamiltonInverseFormula = chInverse,
            stepsLatex = steps
        )
    }

    private fun formatCharacteristicPolynomialFromCoeffs(coeffs: List<String>): String {
        if (coeffs.isEmpty()) return "\\lambda"
        val parts = mutableListOf<String>()
        for (k in coeffs.indices.reversed()) {
            val c = coeffs[k].trim()
            if (c == "0" || c == "0.0") continue
            val cVal = c.toDoubleOrNull()
            val varTerm = when (k) {
                0 -> ""
                1 -> "\\lambda"
                else -> "\\lambda^$k"
            }
            val cClean = if (c.endsWith(".0")) c.substringBefore(".0") else c
            val isNegative = (cVal != null && cVal < 0) || cClean.startsWith("-")
            val absC = if (cClean.startsWith("-")) cClean.substring(1).trim() else cClean
            if (parts.isEmpty()) {
                if (varTerm.isEmpty()) parts += cClean
                else if (cClean == "1") parts += varTerm
                else if (cClean == "-1") parts += "-$varTerm"
                else parts += "$cClean$varTerm"
            } else {
                if (!isNegative) {
                    val coeffStr = if (varTerm.isEmpty()) cClean else if (cClean == "1") "" else cClean
                    parts += "+ $coeffStr$varTerm".trim()
                } else {
                    val coeffStr = if (varTerm.isEmpty()) absC else if (absC == "1") "" else absC
                    parts += "- $coeffStr$varTerm".trim()
                }
            }
        }
        return if (parts.isNotEmpty()) parts.joinToString(" ") else "0"
    }

    private suspend fun verifyCayleyHamilton(
        symjaMatrix: String,
        polyRaw: String,
        dimension: Int,
        detLatex: String
    ): Triple<String, Boolean, String> {
        return try {
            val coeffRaw = cas.evalRaw("CoefficientList(Expand(CharacteristicPolynomial($symjaMatrix, lambda)), lambda)")
            val coeffs = parseSymjaList(coeffRaw)
            if (coeffs.isEmpty()) return Triple("p(A) = 0", false, "")

            val terms = mutableListOf<String>()
            coeffs.forEachIndexed { k, cStr ->
                val term = when (k) {
                    0 -> "($cStr) * IdentityMatrix($dimension)"
                    1 -> "($cStr) * ($symjaMatrix)"
                    else -> "($cStr) * MatrixPower($symjaMatrix, $k)"
                }
                terms += term
            }
            val sumExpr = terms.joinToString(" + ")
            val evalZero = cas.evalRaw("Simplify(Total(Flatten(Abs($sumExpr))))")
            val isZero = evalZero == "0" || evalZero == "0.0"

            val eqParts = mutableListOf<String>()
            for (k in coeffs.indices.reversed()) {
                val c = coeffs[k].trim()
                if (c == "0" || c == "0.0") continue
                val cVal = c.toDoubleOrNull()
                val isNegative = (cVal != null && cVal < 0) || c.startsWith("-")
                val absC = if (c.startsWith("-")) c.substring(1).trim() else c
                val matSymbol = when (k) {
                    0 -> "I"
                    1 -> "A"
                    else -> "A^$k"
                }
                if (eqParts.isEmpty()) {
                    if (c == "1" || c == "1.0") eqParts += matSymbol
                    else if (c == "-1" || c == "-1.0") eqParts += "-$matSymbol"
                    else eqParts += "$c $matSymbol"
                } else {
                    if (!isNegative) {
                        val coeffStr = if (c == "1" || c == "1.0") "" else "$c "
                        eqParts += "+ $coeffStr$matSymbol"
                    } else {
                        val coeffStr = if (absC == "1" || absC == "1.0") "" else "$absC "
                        eqParts += "- $coeffStr$matSymbol"
                    }
                }
            }
            val chEq = if (eqParts.isNotEmpty()) eqParts.joinToString(" ") else "p(A)"

            val c0Str = coeffs.firstOrNull()?.trim() ?: "0"
            val c0Val = c0Str.toDoubleOrNull()
            val chInv = if (c0Val != null && c0Val != 0.0 && detLatex != "0") {
                val invParts = mutableListOf<String>()
                for (k in (1 until coeffs.size).reversed()) {
                    val c = coeffs[k].trim()
                    if (c == "0" || c == "0.0") continue
                    val cVal = c.toDoubleOrNull()
                    val isNegative = (cVal != null && cVal < 0) || c.startsWith("-")
                    val absC = if (c.startsWith("-")) c.substring(1).trim() else c
                    val matSymbol = when (k - 1) {
                        0 -> "I"
                        1 -> "A"
                        else -> "A^{${k - 1}}"
                    }
                    if (invParts.isEmpty()) {
                        if (c == "1" || c == "1.0") invParts += matSymbol
                        else if (c == "-1" || c == "-1.0") invParts += "-$matSymbol"
                        else invParts += "$c $matSymbol"
                    } else {
                        if (!isNegative) {
                            val coeffStr = if (c == "1" || c == "1.0") "" else "$c "
                            invParts += "+ $coeffStr$matSymbol"
                        } else {
                            val coeffStr = if (absC == "1" || absC == "1.0") "" else "$absC "
                            invParts += "- $coeffStr$matSymbol"
                        }
                    }
                }
                val inner = invParts.joinToString(" ")
                when {
                    c0Str == "1" || c0Str == "1.0" -> "A^{-1} = -($inner)"
                    c0Str == "-1" || c0Str == "-1.0" -> "A^{-1} = $inner"
                    c0Val < 0 -> {
                        val absC0 = if (c0Str.startsWith("-")) c0Str.substring(1) else c0Str
                        "A^{-1} = \\frac{1}{$absC0}($inner)"
                    }
                    else -> "A^{-1} = -\\frac{1}{$c0Str}($inner)"
                }
            } else ""

            Triple(chEq, isZero, chInv)
        } catch (_: Exception) {
            Triple("p(A) = 0", false, "")
        }
    }

    fun splitTopLevelCommas(input: String): List<String> {
        val result = mutableListOf<String>()
        var depthParen = 0
        var depthBrace = 0
        var depthBracket = 0
        val current = StringBuilder()
        for (ch in input) {
            when (ch) {
                '(' -> depthParen++
                ')' -> if (depthParen > 0) depthParen--
                '{' -> depthBrace++
                '}' -> if (depthBrace > 0) depthBrace--
                '[' -> depthBracket++
                ']' -> if (depthBracket > 0) depthBracket--
                ',' -> {
                    if (depthParen == 0 && depthBrace == 0 && depthBracket == 0) {
                        val s = current.toString().trim()
                        if (s.isNotEmpty()) result.add(s)
                        current.clear()
                        continue
                    }
                }
            }
            current.append(ch)
        }
        val remaining = current.toString().trim()
        if (remaining.isNotEmpty()) {
            result.add(remaining)
        }
        return result
    }

    private fun parseSymjaList(raw: String): List<String> {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return emptyList()
        val inner = trimmed.substring(1, trimmed.length - 1)
        if (inner.isBlank()) return emptyList()
        return splitTopLevelCommas(inner)
    }

    private fun parseSymjaNumericList(raw: String): List<Double> {
        return parseSymjaList(raw).mapNotNull { it.toDoubleOrNull() }
    }

    private fun parseSymja2DList(raw: String): List<List<String>> {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return emptyList()
        val inner = trimmed.substring(1, trimmed.length - 1)
        val rowStrings = splitTopLevelCommas(inner)
        return rowStrings.mapNotNull { rowStr ->
            val rowTrimmed = rowStr.trim()
            if (rowTrimmed.startsWith("{") && rowTrimmed.endsWith("}")) {
                parseSymjaList(rowTrimmed)
            } else null
        }
    }
}
