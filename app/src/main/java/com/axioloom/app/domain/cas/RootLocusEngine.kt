package com.axioloom.app.domain.cas

import com.axioloom.app.domain.model.MathExpr
import com.axioloom.app.domain.model.MathIntent
import kotlin.math.*

data class RootLocusBranch(
    val gainK: Double,
    val poles: List<ComplexNumber>
)

data class StabilityMargins(
    val gainMarginDb: Double?,
    val phaseMarginDeg: Double?,
    val phaseCrossoverFreqRad: Double?,
    val gainCrossoverFreqRad: Double?,
    val criticalGainK: Double?,
    val criticalFreqRad: Double?
)

data class NyquistPoint(
    val omega: Double,
    val re: Double,
    val im: Double
)

data class RootLocusAnalysis(
    val openLoopZeros: List<ComplexNumber>,
    val openLoopPoles: List<ComplexNumber>,
    val branches: List<RootLocusBranch>,
    val margins: StabilityMargins,
    val nyquistPoints: List<NyquistPoint>
)

/**
 * 100% Offline Evans Root-Locus & Nyquist Stability Designer.
 * Uses simultaneous Durand-Kerner complex polynomial root finding to compute 80-step
 * root-locus trajectories and Nyquist polar contours in < 2ms without CAS calls.
 */
object RootLocusEngine {

    fun analyze(intent: MathIntent): RootLocusAnalysis? {
        val analysis = TransferFunctionAnalyzer.analyze(intent) ?: return null
        return analyze(analysis.numeratorCoeffs, analysis.denominatorCoeffs)
    }

    fun analyze(expr: MathExpr, varName: String = "s"): RootLocusAnalysis? {
        val analysis = TransferFunctionAnalyzer.analyzeExpression(expr, varName = varName) ?: return null
        return analyze(analysis.numeratorCoeffs, analysis.denominatorCoeffs)
    }

    /**
     * numCoeffs[k] is coefficient of s^k.
     * denCoeffs[k] is coefficient of s^k.
     */
    fun analyze(
        numCoeffs: DoubleArray,
        denCoeffs: DoubleArray,
        kMax: Double = 50.0,
        numKSteps: Int = 80
    ): RootLocusAnalysis {
        val openLoopZeros = TransferFunctionAnalyzer.findRoots(numCoeffs)
        val openLoopPoles = TransferFunctionAnalyzer.findRoots(denCoeffs)

        // Generate logarithmic gain sweep from 0 to kMax
        val gains = DoubleArray(numKSteps)
        gains[0] = 0.0
        val minLogK = -2.0 // 0.01
        val maxLogK = log10(max(1.0, kMax))
        for (i in 1 until numKSteps) {
            val frac = (i - 1).toDouble() / (numKSteps - 2)
            gains[i] = 10.0.pow(minLogK + frac * (maxLogK - minLogK))
        }

        // Trace branches: D(s) + K * N(s) = 0
        val branches = ArrayList<RootLocusBranch>(numKSteps)
        var criticalGain: Double? = null
        var criticalFreq: Double? = null

        var prevPoles: List<ComplexNumber>? = null

        val maxDeg = max(numCoeffs.size - 1, denCoeffs.size - 1)

        for (k in gains) {
            val polyCoeffs = DoubleArray(maxDeg + 1)
            for (i in 0..maxDeg) {
                val d = if (i < denCoeffs.size) denCoeffs[i] else 0.0
                val n = if (i < numCoeffs.size) numCoeffs[i] else 0.0
                polyCoeffs[i] = d + k * n
            }

            val currentPoles = findRootsDurandKerner(polyCoeffs)
            branches.add(RootLocusBranch(k, currentPoles))

            // Detect crossing of imaginary axis
            if (prevPoles != null && criticalGain == null && k > 0.0) {
                for (cp in currentPoles) {
                    val matchingPrev = prevPoles.minByOrNull { abs(it.im - cp.im) + abs(it.re - cp.re) }
                    if (matchingPrev != null && matchingPrev.re <= 0.0 && cp.re > 0.0) {
                        // Linear interpolation of crossing gain
                        val dRe = cp.re - matchingPrev.re
                        val frac = if (abs(dRe) > 1e-9) (-matchingPrev.re) / dRe else 0.5
                        val prevK = branches[branches.size - 2].gainK
                        criticalGain = prevK + frac * (k - prevK)
                        criticalFreq = abs(matchingPrev.im + frac * (cp.im - matchingPrev.im))
                        break
                    }
                }
            }
            prevPoles = currentPoles
        }

        // Compute Frequency Margins (GM & PM)
        val margins = computeStabilityMargins(numCoeffs, denCoeffs, criticalGain, criticalFreq)

        // Compute Nyquist polar contour G(j*omega)
        val nyquistPoints = computeNyquistContour(numCoeffs, denCoeffs)

        return RootLocusAnalysis(
            openLoopZeros = openLoopZeros,
            openLoopPoles = openLoopPoles,
            branches = branches,
            margins = margins,
            nyquistPoints = nyquistPoints
        )
    }

    /**
     * Durand-Kerner simultaneous complex polynomial root finder.
     * polyCoeffs[k] is coefficient of s^k.
     */
    fun findRootsDurandKerner(polyCoeffs: DoubleArray): List<ComplexNumber> {
        // Strip high-degree zeros
        var n = polyCoeffs.size - 1
        while (n > 0 && abs(polyCoeffs[n]) < 1e-12) n--

        if (n <= 0) return emptyList()

        if (n == 1) {
            // a1 * s + a0 = 0 => s = -a0 / a1
            return listOf(ComplexNumber(-polyCoeffs[0] / polyCoeffs[1], 0.0))
        }

        if (n == 2) {
            val a = polyCoeffs[2]
            val b = polyCoeffs[1]
            val c = polyCoeffs[0]
            val disc = b * b - 4 * a * c
            return if (disc >= 0) {
                listOf(
                    ComplexNumber((-b + sqrt(disc)) / (2 * a), 0.0),
                    ComplexNumber((-b - sqrt(disc)) / (2 * a), 0.0)
                )
            } else {
                listOf(
                    ComplexNumber(-b / (2 * a), sqrt(-disc) / (2 * a)),
                    ComplexNumber(-b / (2 * a), -sqrt(-disc) / (2 * a))
                )
            }
        }

        val an = polyCoeffs[n]

        // Initial guesses on complex circle
        var maxCoeff = 0.0
        for (i in 0 until n) maxCoeff = max(maxCoeff, abs(polyCoeffs[i] / an))
        val r = 1.0 + maxCoeff

        val roots = Array(n) { j ->
            val theta = (2.0 * PI * j + 0.5) / n
            ComplexNumber(r * cos(theta), r * sin(theta))
        }

        // Iteration loop
        val maxIters = 40
        for (iter in 0 until maxIters) {
            var maxChange = 0.0
            for (j in 0 until n) {
                // Evaluate P(roots[j]) / an
                val zj = roots[j]
                var pVal = ComplexNumber(polyCoeffs[n] / an, 0.0)
                for (k in n - 1 downTo 0) {
                    pVal = pVal * zj + ComplexNumber(polyCoeffs[k] / an, 0.0)
                }

                // Compute product prod_{k != j} (roots[j] - roots[k])
                var denom = ComplexNumber(1.0, 0.0)
                for (k in 0 until n) {
                    if (k != j) {
                        denom = denom * (zj - roots[k])
                    }
                }

                if (denom.magnitude() > 1e-14) {
                    val delta = pVal / denom
                    roots[j] = roots[j] - delta
                    maxChange = max(maxChange, delta.magnitude())
                }
            }
            if (maxChange < 1e-9) break
        }

        return roots.map { root ->
            // Clean up near-zero imaginary parts
            if (abs(root.im) < 1e-7) ComplexNumber(root.re, 0.0) else root
        }.sortedWith(compareBy({ it.re }, { it.im }))
    }

    private fun computeStabilityMargins(
        numCoeffs: DoubleArray,
        denCoeffs: DoubleArray,
        criticalGainK: Double?,
        criticalFreqRad: Double?
    ): StabilityMargins {
        // Frequency sweep for Phase Crossover (phase = -180 deg) and Gain Crossover (magnitude = 0 dB)
        val numPoints = 200
        val omegaMin = 0.01
        val omegaMax = 1000.0

        var phaseCrossoverW: Double? = null
        var gainMarginDb: Double? = null

        var gainCrossoverW: Double? = null
        var phaseMarginDeg: Double? = null

        var prevPhaseDeg = 0.0
        var prevMag = 1.0
        var prevOmega = omegaMin

        for (i in 0 until numPoints) {
            val frac = i.toDouble() / (numPoints - 1)
            val omega = 10.0.pow(log10(omegaMin) + frac * (log10(omegaMax) - log10(omegaMin)))

            val jOmega = ComplexNumber(0.0, omega)
            val numVal = evalPolyComplex(numCoeffs, jOmega)
            val denVal = evalPolyComplex(denCoeffs, jOmega)

            if (denVal.magnitude() < 1e-12) continue
            val gVal = numVal / denVal

            val mag = gVal.magnitude()
            var phase = gVal.phaseDeg()
            if (phase > 0) phase -= 360.0 // Normalize to [-360, 0]

            // Phase Crossover: crossing -180 deg
            if (i > 0 && prevPhaseDeg > -180.0 && phase <= -180.0) {
                val f = (-180.0 - prevPhaseDeg) / (phase - prevPhaseDeg)
                phaseCrossoverW = prevOmega + f * (omega - prevOmega)
                val crossMag = prevMag + f * (mag - prevMag)
                if (crossMag > 1e-6) {
                    gainMarginDb = -20.0 * log10(crossMag)
                }
            }

            // Gain Crossover: crossing 1.0 (0 dB)
            if (i > 0 && prevMag > 1.0 && mag <= 1.0) {
                val f = (1.0 - prevMag) / (mag - prevMag)
                gainCrossoverW = prevOmega + f * (omega - prevOmega)
                val crossPhase = prevPhaseDeg + f * (phase - prevPhaseDeg)
                phaseMarginDeg = 180.0 + crossPhase
            }

            prevPhaseDeg = phase
            prevMag = mag
            prevOmega = omega
        }

        return StabilityMargins(
            gainMarginDb = gainMarginDb,
            phaseMarginDeg = phaseMarginDeg,
            phaseCrossoverFreqRad = phaseCrossoverW,
            gainCrossoverFreqRad = gainCrossoverW,
            criticalGainK = criticalGainK,
            criticalFreqRad = criticalFreqRad
        )
    }

    private fun computeNyquistContour(
        numCoeffs: DoubleArray,
        denCoeffs: DoubleArray,
        steps: Int = 100
    ): List<NyquistPoint> {
        val points = ArrayList<NyquistPoint>(steps)
        val wMin = 0.05
        val wMax = 200.0

        for (i in 0 until steps) {
            val frac = i.toDouble() / (steps - 1)
            val omega = 10.0.pow(log10(wMin) + frac * (log10(wMax) - log10(wMin)))
            val jOmega = ComplexNumber(0.0, omega)
            val numVal = evalPolyComplex(numCoeffs, jOmega)
            val denVal = evalPolyComplex(denCoeffs, jOmega)
            if (denVal.magnitude() > 1e-12) {
                val g = numVal / denVal
                points.add(NyquistPoint(omega, g.re, g.im))
            }
        }
        return points
    }

    private fun evalPolyComplex(coeffs: DoubleArray, s: ComplexNumber): ComplexNumber {
        var res = ComplexNumber(0.0, 0.0)
        var sPower = ComplexNumber(1.0, 0.0)
        for (c in coeffs) {
            res = res + sPower * ComplexNumber(c, 0.0)
            sPower = sPower * s
        }
        return res
    }
}
