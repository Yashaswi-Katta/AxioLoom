package com.offline.inkmath.domain.codegen

import com.offline.inkmath.domain.cas.TransferFunctionAnalysis
import com.offline.inkmath.domain.cas.TransferFunctionAnalyzer
import com.offline.inkmath.domain.model.MathExpr
import com.offline.inkmath.domain.model.MathIntent
import kotlin.math.max
import kotlin.math.pow

data class DiscretizedFilter(
    val continuousNumCoeffs: DoubleArray,
    val continuousDenCoeffs: DoubleArray,
    val bCoeffs: DoubleArray, // Numerator coefficients b_0, b_1, ... b_M
    val aCoeffs: DoubleArray, // Denominator coefficients a_0, a_1, ... a_N
    val samplingFreqHz: Double,
    val samplingPeriodSec: Double,
    val differenceEquationLatex: String
) {
    val normalizedB: DoubleArray get() = DoubleArray(bCoeffs.size) { bCoeffs[it] / aCoeffs[0] }
    val normalizedA: DoubleArray get() = DoubleArray(aCoeffs.size) { aCoeffs[it] / aCoeffs[0] }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiscretizedFilter) return false
        return bCoeffs.contentEquals(other.bCoeffs) &&
               aCoeffs.contentEquals(other.aCoeffs) &&
               samplingFreqHz == other.samplingFreqHz
    }

    override fun hashCode(): Int {
        var result = bCoeffs.contentHashCode()
        result = 31 * result + aCoeffs.contentHashCode()
        result = 31 * result + samplingFreqHz.hashCode()
        return result
    }
}

/**
 * Applies the Bilinear (Tustin) transform s = (2/T) * (1 - z^-1) / (1 + z^-1) to continuous-time
 * rational transfer functions H(s) to produce discrete difference equation coefficients.
 */
object BilinearDiscretizer {

    fun discretize(
        intent: MathIntent,
        samplingFreqHz: Double = 1000.0
    ): DiscretizedFilter? {
        val analysis = TransferFunctionAnalyzer.analyze(intent) ?: return null
        return discretize(analysis.numeratorCoeffs, analysis.denominatorCoeffs, samplingFreqHz)
    }

    fun discretize(
        expr: MathExpr,
        samplingFreqHz: Double = 1000.0,
        varName: String = "s"
    ): DiscretizedFilter? {
        val analysis = TransferFunctionAnalyzer.analyzeExpression(expr, varName = varName) ?: return null
        return discretize(analysis.numeratorCoeffs, analysis.denominatorCoeffs, samplingFreqHz)
    }

    /**
     * Discretizes H(s) = N(s) / D(s) using Tustin's approximation.
     * numCoeffs[k] is coefficient of s^k.
     * denCoeffs[k] is coefficient of s^k.
     */
    fun discretize(
        numCoeffs: DoubleArray,
        denCoeffs: DoubleArray,
        samplingFreqHz: Double
    ): DiscretizedFilter {
        require(samplingFreqHz > 0.0) { "Sampling frequency must be positive" }
        val ts = 1.0 / samplingFreqHz
        val c = 2.0 / ts // 2 * fs

        // Find true degrees (ignore trailing ~0 coefficients)
        var degDen = denCoeffs.size - 1
        while (degDen > 0 && kotlin.math.abs(denCoeffs[degDen]) < 1e-12) degDen--

        var degNum = numCoeffs.size - 1
        while (degNum > 0 && kotlin.math.abs(numCoeffs[degNum]) < 1e-12) degNum--

        val n = max(degNum, degDen)
        val bCoeffs = DoubleArray(n + 1)
        val aCoeffs = DoubleArray(n + 1)

        // Expand (1 - z^-1)^k * (1 + z^-1)^(N - k)
        for (k in 0..degNum) {
            val poly = expandTustinBasis(k, n - k)
            val factor = numCoeffs[k] * c.pow(k)
            for (p in 0..n) {
                bCoeffs[p] += factor * poly[p]
            }
        }

        for (k in 0..degDen) {
            val poly = expandTustinBasis(k, n - k)
            val factor = denCoeffs[k] * c.pow(k)
            for (p in 0..n) {
                aCoeffs[p] += factor * poly[p]
            }
        }

        val a0 = aCoeffs[0]
        val diffEq = formatDifferenceEquation(bCoeffs, aCoeffs)

        return DiscretizedFilter(
            continuousNumCoeffs = numCoeffs,
            continuousDenCoeffs = denCoeffs,
            bCoeffs = bCoeffs,
            aCoeffs = aCoeffs,
            samplingFreqHz = samplingFreqHz,
            samplingPeriodSec = ts,
            differenceEquationLatex = diffEq
        )
    }

    /**
     * Multiplies polynomial (1 - z^-1)^numMinus * (1 + z^-1)^numPlus
     * Returns coefficients indexed by power of z^-1 (index 0 is (z^-1)^0, index 1 is z^-1, etc.).
     */
    private fun expandTustinBasis(numMinus: Int, numPlus: Int): DoubleArray {
        var poly = doubleArrayOf(1.0)
        val minusFactor = doubleArrayOf(1.0, -1.0) // 1 - z^-1
        val plusFactor = doubleArrayOf(1.0, 1.0)   // 1 + z^-1

        repeat(numMinus) {
            poly = convolvePolynomials(poly, minusFactor)
        }
        repeat(numPlus) {
            poly = convolvePolynomials(poly, plusFactor)
        }
        return poly
    }

    private fun convolvePolynomials(p1: DoubleArray, p2: DoubleArray): DoubleArray {
        val res = DoubleArray(p1.size + p2.size - 1)
        for (i in p1.indices) {
            for (j in p2.indices) {
                res[i + j] += p1[i] * p2[j]
            }
        }
        return res
    }

    private fun formatDifferenceEquation(b: DoubleArray, a: DoubleArray): String {
        val a0 = a[0]
        if (kotlin.math.abs(a0) < 1e-12) return "y[n] = 0"

        val xTerms = mutableListOf<String>()
        for (k in b.indices) {
            val coeff = b[k] / a0
            if (kotlin.math.abs(coeff) > 1e-6) {
                val formatted = "%.4f".format(coeff)
                val delayStr = if (k == 0) "x[n]" else "x[n - $k]"
                xTerms.add(if (formatted == "1.0000") delayStr else "$formatted $delayStr")
            }
        }

        val yTerms = mutableListOf<String>()
        for (k in 1 until a.size) {
            val coeff = a[k] / a0
            if (kotlin.math.abs(coeff) > 1e-6) {
                val formatted = "%.4f".format(coeff)
                val delayStr = "y[n - $k]"
                yTerms.add(if (formatted == "1.0000") delayStr else "$formatted $delayStr")
            }
        }

        val rhsParts = mutableListOf<String>()
        if (xTerms.isNotEmpty()) {
            rhsParts.add(xTerms.joinToString(" + ").replace("+ -", "- "))
        }
        if (yTerms.isNotEmpty()) {
            rhsParts.add("- (" + yTerms.joinToString(" + ").replace("+ -", "- ") + ")")
        }

        return "y[n] = " + (if (rhsParts.isNotEmpty()) rhsParts.joinToString(" ") else "0")
    }
}
