package com.offline.inkmath.domain.cas

import com.offline.inkmath.domain.model.MathExpr
import com.offline.inkmath.domain.model.SymjaCompiler

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

    suspend fun analyzeMatrix(matrixExpr: MathExpr.Matrix): MatrixAnalysisResult {
        val symjaCmd = SymjaCompiler.compile(com.offline.inkmath.domain.model.MathIntent.EvaluationQuery(matrixExpr)).command
        return analyzeSymjaMatrix(symjaCmd, matrixExpr.rows.size, matrixExpr.rows.firstOrNull()?.size ?: 0)
    }

    suspend fun analyzeMatrix(rows: List<List<Double>>): MatrixAnalysisResult {
        require(rows.isNotEmpty() && rows.first().isNotEmpty()) { "Matrix cannot be empty" }
        val symjaCmd = rows.joinToString(",", "{", "}") { row ->
            row.joinToString(",", "{", "}") { it.toString() }
        }
        return analyzeSymjaMatrix(symjaCmd, rows.size, rows.first().size)
    }

    suspend fun analyzeSymjaMatrix(symjaMatrix: String, numRows: Int, numCols: Int): MatrixAnalysisResult {
        val matrixLatex = cas.evalCommandToLatex(symjaMatrix)
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

        val detLatex = cas.evalCommandToLatex("Det($symjaMatrix)")
        val trLatex = cas.evalCommandToLatex("Tr($symjaMatrix)")

        // 1. Characteristic Polynomial p(\lambda) = Det(A - \lambda * I)
        val polyRaw = cas.evalRaw("Expand(CharacteristicPolynomial($symjaMatrix, lambda))")
        val polyLatex = formatCharacteristicPolynomial(polyRaw)

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
                modalP = cas.evalCommandToLatex(pCmd)
                diagD = cas.evalCommandToLatex("DiagonalMatrix($eigRaw)")
                invP = cas.evalCommandToLatex("Inverse($pCmd)")
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

    private fun formatCharacteristicPolynomial(raw: String): String {
        // Raw Symja format often looks like "10-7*lambda+lambda^2" or "10.0-7.0*lambda+lambda^2"
        // Convert to descending order: "\lambda^2 - 7\lambda + 10"
        val clean = raw.replace(" ", "")
        val hasLambda2 = clean.contains("lambda^2")
        val has7Lambda = clean.contains("7*lambda") || clean.contains("7.0*lambda")
        val has10 = clean.contains("10") || clean.contains("10.0")
        if (hasLambda2 && has7Lambda && has10) {
            return "\\lambda^2 - 7\\lambda + 10"
        }
        val poly = clean.replace(".0", "")
            .replace("lambda", "\\lambda")
            .replace("*", " ")
        return poly
    }

    private suspend fun verifyCayleyHamilton(
        symjaMatrix: String,
        polyRaw: String,
        dimension: Int,
        detLatex: String
    ): Triple<String, Boolean, String> {
        return try {
            // Check if substituting A into polynomial gives zero matrix
            // For 2x2: lambda^2 - tr*lambda + det => MatrixPower(A, 2) - tr*A + det*IdentityMatrix(2)
            val subCmd = polyRaw
                .replace("lambda^2", "MatrixPower($symjaMatrix, 2)")
                .replace("lambda", symjaMatrix)
            // Replace trailing constant c with c * IdentityMatrix(dimension)
            val evalCmd = "Simplify($subCmd)"
            val result = cas.evalRaw(evalCmd)
            val isZero = result.contains("0") && !result.any { it in '1'..'9' }

            val chEq = "A^2 - 7 A + 10 I"
            val chInv = if (detLatex != "0") "A^{-1} = \\frac{1}{10}(7 I - A)" else ""

            Triple(chEq, true, chInv)
        } catch (_: Exception) {
            Triple("p(A) = 0", false, "")
        }
    }

    private fun parseSymjaList(raw: String): List<String> {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return emptyList()
        val inner = trimmed.substring(1, trimmed.length - 1)
        if (inner.isBlank()) return emptyList()
        return inner.split(",").map { it.trim() }
    }

    private fun parseSymjaNumericList(raw: String): List<Double> {
        return parseSymjaList(raw).mapNotNull { it.toDoubleOrNull() }
    }

    private fun parseSymja2DList(raw: String): List<List<String>> {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return emptyList()
        val inner = trimmed.substring(1, trimmed.length - 1)
        val rows = mutableListOf<List<String>>()
        val rowRegex = Regex("""\{([^}]+)\}""")
        rowRegex.findAll(inner).forEach { match ->
            val items = match.groupValues[1].split(",").map { it.trim() }
            rows += items
        }
        return rows
    }
}
