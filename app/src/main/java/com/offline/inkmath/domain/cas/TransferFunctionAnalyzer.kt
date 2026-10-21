package com.offline.inkmath.domain.cas

import com.offline.inkmath.domain.model.MathExpr
import com.offline.inkmath.domain.model.MathIntent
import kotlin.math.*

data class ComplexNumber(val re: Double, val im: Double) {
    operator fun plus(other: ComplexNumber) = ComplexNumber(re + other.re, im + other.im)
    operator fun minus(other: ComplexNumber) = ComplexNumber(re - other.re, im - other.im)
    operator fun times(other: ComplexNumber) = ComplexNumber(
        re * other.re - im * other.im,
        re * other.im + im * other.re
    )
    operator fun div(other: ComplexNumber): ComplexNumber {
        val denom = other.re * other.re + other.im * other.im
        require(denom > 0.0) { "Complex division by zero" }
        return ComplexNumber(
            (re * other.re + im * other.im) / denom,
            (im * other.re - re * other.im) / denom
        )
    }
    fun magnitude(): Double = hypot(re, im)
    fun phaseDeg(): Double = atan2(im, re) * 180.0 / PI

    fun formatted(): String {
        val reStr = if (abs(re) < 1e-4) "0" else String.format(java.util.Locale.US, "%.3f", re).trimEnd('0').trimEnd('.')
        val imStr = if (abs(im) < 1e-4) "0" else String.format(java.util.Locale.US, "%.3f", abs(im)).trimEnd('0').trimEnd('.')
        return when {
            abs(im) < 1e-4 -> reStr
            abs(re) < 1e-4 -> if (im < 0) "-j$imStr" else "j$imStr"
            im < 0 -> "$reStr - j$imStr"
            else -> "$reStr + j$imStr"
        }
    }
}

enum class StabilityVerdict(val label: String) {
    STABLE("BIBO Stable"),
    MARGINALLY_STABLE("Marginally Stable"),
    UNSTABLE("Unstable")
}

data class FrequencyPoint(
    val omega: Double,
    val magnitudeDb: Double,
    val phaseDeg: Double
)

data class TransferFunctionAnalysis(
    val functionName: String,
    val variable: String,
    val numeratorCoeffs: DoubleArray,
    val denominatorCoeffs: DoubleArray,
    val zeros: List<ComplexNumber>,
    val poles: List<ComplexNumber>,
    val stability: StabilityVerdict,
    val dcGainDb: Double,
    val cutoffFrequencyRad: Double?,
    val bodePoints: List<FrequencyPoint>
) {
    val isStable: Boolean get() = stability == StabilityVerdict.STABLE

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransferFunctionAnalysis) return false
        return functionName == other.functionName &&
               zeros == other.zeros &&
               poles == other.poles &&
               stability == other.stability
    }

    override fun hashCode(): Int {
        var result = functionName.hashCode()
        result = 31 * result + zeros.hashCode()
        result = 31 * result + poles.hashCode()
        result = 31 * result + stability.hashCode()
        return result
    }
}

/**
 * Control Systems & Signals Transfer Function Analyzer.
 * Extracts zeros, poles, s-plane stability verdict, and 120-point Bode frequency response
 * from rational transfer functions in s or omega.
 */
object TransferFunctionAnalyzer {

    fun analyze(intent: MathIntent): TransferFunctionAnalysis? {
        val (funcName, varName, expr) = when (intent) {
            is MathIntent.FunctionDef -> {
                val v = intent.params.firstOrNull() ?: "s"
                Triple(intent.funcName, v, intent.body)
            }
            is MathIntent.EvaluationQuery -> {
                Triple("H", "s", intent.expression)
            }
            is MathIntent.Assignment -> {
                Triple(intent.variableName, "s", intent.rhs)
            }
            else -> return null
        }

        return analyzeExpression(expr, funcName, varName)
    }

    fun analyzeExpression(
        expr: MathExpr,
        funcName: String = "H",
        varName: String = "s"
    ): TransferFunctionAnalysis? {
        val (numExpr, denExpr) = when {
            expr is MathExpr.Binary && expr.op == MathExpr.Op.DIVIDE -> expr.left to expr.right
            else -> expr to MathExpr.Number("1")
        }

        val numCoeffs = extractCoefficients(numExpr, varName) ?: return null
        val denCoeffs = extractCoefficients(denExpr, varName) ?: return null

        if (denCoeffs.all { abs(it) < 1e-12 }) return null

        val zeros = findRoots(numCoeffs)
        val poles = findRoots(denCoeffs)

        val stability = determineStability(poles)

        val bodePoints = computeBodeResponse(numCoeffs, denCoeffs)
        val dcGainDb = bodePoints.firstOrNull()?.magnitudeDb ?: 0.0

        // Find -3dB cutoff frequency relative to DC gain
        val target3Db = dcGainDb - 3.0
        var cutoff: Double? = null
        for (i in 0 until bodePoints.size - 1) {
            val p1 = bodePoints[i]
            val p2 = bodePoints[i + 1]
            if ((p1.magnitudeDb >= target3Db && p2.magnitudeDb <= target3Db) ||
                (p1.magnitudeDb <= target3Db && p2.magnitudeDb >= target3Db)) {
                val frac = if (abs(p2.magnitudeDb - p1.magnitudeDb) > 1e-6) {
                    (target3Db - p1.magnitudeDb) / (p2.magnitudeDb - p1.magnitudeDb)
                } else 0.5
                cutoff = p1.omega * 10.0.pow(frac * log10(p2.omega / p1.omega))
                break
            }
        }

        return TransferFunctionAnalysis(
            functionName = funcName,
            variable = varName,
            numeratorCoeffs = numCoeffs,
            denominatorCoeffs = denCoeffs,
            zeros = zeros,
            poles = poles,
            stability = stability,
            dcGainDb = dcGainDb,
            cutoffFrequencyRad = cutoff,
            bodePoints = bodePoints
        )
    }

    /**
     * Extracts polynomial coefficients: index 0 is s^0, index 1 is s^1, etc.
     */
    fun extractCoefficients(expr: MathExpr, varName: String): DoubleArray? {
        return when (expr) {
            is MathExpr.Number -> doubleArrayOf(expr.value.toDoubleOrNull() ?: return null)
            is MathExpr.Constant -> when (expr) {
                MathExpr.Constant.PI -> doubleArrayOf(PI)
                MathExpr.Constant.E -> doubleArrayOf(E)
                else -> return null
            }
            is MathExpr.Symbol -> {
                if (expr.name == varName) doubleArrayOf(0.0, 1.0)
                else null
            }
            is MathExpr.Unary -> {
                val inner = extractCoefficients(expr.value, varName) ?: return null
                if (expr.negative) DoubleArray(inner.size) { -inner[it] } else inner
            }
            is MathExpr.Binary -> when (expr.op) {
                MathExpr.Op.ADD -> {
                    val l = extractCoefficients(expr.left, varName) ?: return null
                    val r = extractCoefficients(expr.right, varName) ?: return null
                    addPoly(l, r)
                }
                MathExpr.Op.SUBTRACT -> {
                    val l = extractCoefficients(expr.left, varName) ?: return null
                    val r = extractCoefficients(expr.right, varName) ?: return null
                    subPoly(l, r)
                }
                MathExpr.Op.MULTIPLY -> {
                    val l = extractCoefficients(expr.left, varName) ?: return null
                    val r = extractCoefficients(expr.right, varName) ?: return null
                    mulPoly(l, r)
                }
                MathExpr.Op.POWER -> {
                    val base = extractCoefficients(expr.left, varName) ?: return null
                    val expNum = (expr.right as? MathExpr.Number)?.value?.toIntOrNull() ?: return null
                    if (expNum < 0 || expNum > 6) return null
                    var res = doubleArrayOf(1.0)
                    repeat(expNum) { res = mulPoly(res, base) }
                    res
                }
                MathExpr.Op.DIVIDE -> {
                    // Scalar division only
                    val num = extractCoefficients(expr.left, varName) ?: return null
                    val denVal = (expr.right as? MathExpr.Number)?.value?.toDoubleOrNull() ?: return null
                    if (abs(denVal) < 1e-12) return null
                    DoubleArray(num.size) { num[it] / denVal }
                }
                else -> null
            }
            else -> null
        }
    }

    private fun addPoly(a: DoubleArray, b: DoubleArray): DoubleArray {
        val size = max(a.size, b.size)
        val res = DoubleArray(size)
        for (i in 0 until size) {
            val av = if (i < a.size) a[i] else 0.0
            val bv = if (i < b.size) b[i] else 0.0
            res[i] = av + bv
        }
        return res
    }

    private fun subPoly(a: DoubleArray, b: DoubleArray): DoubleArray {
        val size = max(a.size, b.size)
        val res = DoubleArray(size)
        for (i in 0 until size) {
            val av = if (i < a.size) a[i] else 0.0
            val bv = if (i < b.size) b[i] else 0.0
            res[i] = av - bv
        }
        return res
    }

    private fun mulPoly(a: DoubleArray, b: DoubleArray): DoubleArray {
        val res = DoubleArray(a.size + b.size - 1)
        for (i in a.indices) {
            for (j in b.indices) {
                res[i + j] += a[i] * b[j]
            }
        }
        return res
    }

    /**
     * Solves for polynomial roots:
     * Closed-form formulas for degrees 1 and 2; Weierstrass (Durand-Kerner) for degree >= 3.
     */
    fun findRoots(rawCoeffs: DoubleArray): List<ComplexNumber> {
        // Trim trailing zeros (high degree zero coefficients)
        var deg = rawCoeffs.size - 1
        while (deg >= 0 && abs(rawCoeffs[deg]) < 1e-12) deg--
        if (deg <= 0) return emptyList()

        val a = DoubleArray(deg + 1) { rawCoeffs[it] }
        val an = a[deg]

        if (deg == 1) {
            // a1*s + a0 = 0 -> s = -a0/a1
            return listOf(ComplexNumber(-a[0] / an, 0.0))
        }

        if (deg == 2) {
            // a2*s^2 + a1*s + a0 = 0
            val a2 = an
            val a1 = a[1]
            val a0 = a[0]
            val disc = a1 * a1 - 4.0 * a2 * a0
            return if (disc >= 0.0) {
                val r1 = (-a1 + sqrt(disc)) / (2.0 * a2)
                val r2 = (-a1 - sqrt(disc)) / (2.0 * a2)
                listOf(ComplexNumber(r1, 0.0), ComplexNumber(r2, 0.0))
            } else {
                val realPart = -a1 / (2.0 * a2)
                val imagPart = sqrt(-disc) / (2.0 * a2)
                listOf(ComplexNumber(realPart, imagPart), ComplexNumber(realPart, -imagPart))
            }
        }

        // Durand-Kerner algorithm for degree >= 3
        val roots = Array(deg) { i ->
            val angle = 2.0 * PI * i / deg + 0.4
            ComplexNumber(0.4 * cos(angle), 0.4 * sin(angle))
        }

        val monic = DoubleArray(deg + 1) { a[it] / an }

        for (iter in 0 until 50) {
            var maxChange = 0.0
            for (i in 0 until deg) {
                val zi = roots[i]
                var pVal = ComplexNumber(monic[deg], 0.0)
                for (k in deg - 1 downTo 0) {
                    pVal = pVal * zi + ComplexNumber(monic[k], 0.0)
                }

                var denom = ComplexNumber(1.0, 0.0)
                for (j in 0 until deg) {
                    if (i != j) {
                        denom = denom * (zi - roots[j])
                    }
                }

                if (denom.magnitude() > 1e-12) {
                    val delta = pVal / denom
                    roots[i] = roots[i] - delta
                    maxChange = max(maxChange, delta.magnitude())
                }
            }
            if (maxChange < 1e-10) break
        }

        return roots.map { r ->
            val cleanRe = if (abs(r.re) < 1e-9) 0.0 else r.re
            val cleanIm = if (abs(r.im) < 1e-9) 0.0 else r.im
            ComplexNumber(cleanRe, cleanIm)
        }.sortedWith(compareBy({ it.re }, { it.im }))
    }

    private fun determineStability(poles: List<ComplexNumber>): StabilityVerdict {
        if (poles.isEmpty()) return StabilityVerdict.STABLE

        var hasImaginaryAxis = false
        val imagPoles = mutableListOf<Double>()

        for (p in poles) {
            if (p.re > 1e-5) return StabilityVerdict.UNSTABLE
            if (abs(p.re) <= 1e-5) {
                hasImaginaryAxis = true
                imagPoles.add(p.im)
            }
        }

        if (hasImaginaryAxis) {
            // Check for repeated poles on imaginary axis
            for (i in 0 until imagPoles.size) {
                for (j in i + 1 until imagPoles.size) {
                    if (abs(imagPoles[i] - imagPoles[j]) < 1e-4) {
                        return StabilityVerdict.UNSTABLE
                    }
                }
            }
            return StabilityVerdict.MARGINALLY_STABLE
        }

        return StabilityVerdict.STABLE
    }

    /**
     * Evaluates H(jw) across 120 points from 10^-1 to 10^4 rad/s.
     */
    fun computeBodeResponse(numCoeffs: DoubleArray, denCoeffs: DoubleArray): List<FrequencyPoint> {
        val count = 120
        val logMin = -1.0
        val logMax = 4.0
        val points = ArrayList<FrequencyPoint>(count)

        var lastPhaseDeg = 0.0

        for (i in 0 until count) {
            val logW = logMin + (logMax - logMin) * i / (count - 1)
            val w = 10.0.pow(logW)
            val s = ComplexNumber(0.0, w)

            val numVal = evalPolyComplex(numCoeffs, s)
            val denVal = evalPolyComplex(denCoeffs, s)

            val hVal = if (denVal.magnitude() > 1e-12) numVal / denVal else ComplexNumber(Double.NaN, Double.NaN)

            val magDb = if (!hVal.re.isNaN() && hVal.magnitude() > 1e-12) {
                20.0 * log10(hVal.magnitude())
            } else -200.0

            var rawPhaseDeg = if (!hVal.re.isNaN()) hVal.phaseDeg() else 0.0

            // Phase unwrapping
            if (i > 0) {
                val diff = rawPhaseDeg - lastPhaseDeg
                if (diff > 180.0) rawPhaseDeg -= 360.0 * round(diff / 360.0)
                else if (diff < -180.0) rawPhaseDeg -= 360.0 * round(diff / 360.0)
            }
            lastPhaseDeg = rawPhaseDeg

            points.add(FrequencyPoint(w, magDb, rawPhaseDeg))
        }

        return points
    }

    private fun evalPolyComplex(coeffs: DoubleArray, s: ComplexNumber): ComplexNumber {
        var res = ComplexNumber(0.0, 0.0)
        var sPow = ComplexNumber(1.0, 0.0)
        for (c in coeffs) {
            res = res + ComplexNumber(c, 0.0) * sPow
            sPow = sPow * s
        }
        return res
    }
}
