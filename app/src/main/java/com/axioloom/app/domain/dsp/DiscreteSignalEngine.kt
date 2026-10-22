package com.axioloom.app.domain.dsp

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class Complex(val re: Double, val im: Double = 0.0) {
    operator fun plus(other: Complex) = Complex(re + other.re, im + other.im)
    operator fun minus(other: Complex) = Complex(re - other.re, im - other.im)
    operator fun times(other: Complex) = Complex(re * other.re - im * other.im, re * other.im + im * other.re)
    operator fun times(scalar: Double) = Complex(re * scalar, im * scalar)
    operator fun div(scalar: Double) = Complex(re / scalar, im / scalar)
    val magnitude: Double get() = hypot(re, im)
    val phase: Double get() = atan2(im, re)
    val conjugate: Complex get() = Complex(re, -im)
}

data class ConvolutionStep(
    val n: Int,
    val equation: String,
    val terms: List<Pair<Double, Double>>,
    val sum: Double
)

data class ConvolutionResult(
    val output: List<Double>,
    val steps: List<ConvolutionStep>
)

data class SpectrumResult(
    val frequencies: List<Double>,
    val magnitude: List<Double>,
    val phase: List<Double>,
    val psd: List<Double>
)

/**
 * Pure Kotlin, zero-dependency Discrete-Time Signal Processing engine.
 * Computes linear convolution, circular convolution, Cooley-Tukey Radix-2 FFT, arbitrary-N DFT,
 * and spectral decomposition.
 */
object DiscreteSignalEngine {

    /**
     * Direct linear convolution y[n] = (x * h)[n] = sum_k x[k] h[n - k].
     * Output length is x.size + h.size - 1.
     */
    fun convolve(x: List<Double>, h: List<Double>): List<Double> {
        if (x.isEmpty() || h.isEmpty()) return emptyList()
        val outLen = x.size + h.size - 1
        val y = DoubleArray(outLen)
        for (i in x.indices) {
            val xi = x[i]
            for (j in h.indices) {
                y[i + j] += xi * h[j]
            }
        }
        return y.toList()
    }

    fun convolveWithSteps(x: List<Double>, h: List<Double>): ConvolutionResult {
        if (x.isEmpty() || h.isEmpty()) return ConvolutionResult(emptyList(), emptyList())
        val outLen = x.size + h.size - 1
        val steps = ArrayList<ConvolutionStep>(outLen)
        val y = DoubleArray(outLen)

        for (n in 0 until outLen) {
            val terms = mutableListOf<Pair<Double, Double>>()
            val eqParts = mutableListOf<String>()
            var sum = 0.0

            for (k in 0 until x.size) {
                val hIdx = n - k
                if (hIdx in h.indices) {
                    val xVal = x[k]
                    val hVal = h[hIdx]
                    val prod = xVal * hVal
                    sum += prod
                    terms.add(xVal to hVal)
                    eqParts.add("($xVal \\cdot $hVal)")
                }
            }
            y[n] = sum
            val eqStr = if (eqParts.isNotEmpty()) eqParts.joinToString(" + ") + " = $sum" else "0"
            steps.add(ConvolutionStep(n, eqStr, terms, sum))
        }

        return ConvolutionResult(y.toList(), steps)
    }

    /**
     * N-point Circular Convolution: y[n] = sum_{k=0}^{N-1} x[k] h[(n - k) mod N].
     */
    fun circularConvolve(x: List<Double>, h: List<Double>, N: Int = maxOf(x.size, h.size)): List<Double> {
        require(N > 0) { "Convolution length N must be positive" }
        val xPad = DoubleArray(N) { if (it < x.size) x[it] else 0.0 }
        val hPad = DoubleArray(N) { if (it < h.size) h[it] else 0.0 }
        val y = DoubleArray(N)

        for (n in 0 until N) {
            var sum = 0.0
            for (k in 0 until N) {
                val hIdx = (n - k) % N
                val validHIdx = if (hIdx < 0) hIdx + N else hIdx
                sum += xPad[k] * hPad[validHIdx]
            }
            y[n] = sum
        }
        return y.toList()
    }

    /**
     * Exact N-point Discrete Fourier Transform (DFT).
     * X[k] = sum_{n=0}^{N-1} x[n] * e^{-j 2pi k n / N}.
     */
    fun dft(x: List<Complex>): List<Complex> {
        val N = x.size
        if (N == 0) return emptyList()
        val result = ArrayList<Complex>(N)

        for (k in 0 until N) {
            var re = 0.0
            var im = 0.0
            val factor = -2.0 * PI * k / N
            for (n in 0 until N) {
                val angle = factor * n
                val cosA = cos(angle)
                val sinA = sin(angle)
                val xn = x[n]
                // (xn.re + j*xn.im) * (cosA + j*sinA)
                re += xn.re * cosA - xn.im * sinA
                im += xn.re * sinA + xn.im * cosA
            }
            result.add(Complex(re, im))
        }
        return result
    }

    /**
     * Exact N-point Inverse Discrete Fourier Transform (IDFT).
     * x[n] = (1 / N) * sum_{k=0}^{N-1} X[k] * e^{j 2pi k n / N}.
     */
    fun idft(X: List<Complex>): List<Complex> {
        val N = X.size
        if (N == 0) return emptyList()
        val result = ArrayList<Complex>(N)

        for (n in 0 until N) {
            var re = 0.0
            var im = 0.0
            val factor = 2.0 * PI * n / N
            for (k in 0 until N) {
                val angle = factor * k
                val cosA = cos(angle)
                val sinA = sin(angle)
                val Xk = X[k]
                re += Xk.re * cosA - Xk.im * sinA
                im += Xk.re * sinA + Xk.im * cosA
            }
            result.add(Complex(re / N, im / N))
        }
        return result
    }

    /**
     * Fast Fourier Transform: Radix-2 Cooley-Tukey for power-of-2 sizes;
     * falls back to exact DFT for other sizes.
     */
    fun fft(x: List<Complex>): List<Complex> {
        val n = x.size
        if (n <= 1) return x
        if ((n and (n - 1)) != 0) {
            return dft(x)
        }

        val evens = ArrayList<Complex>(n / 2)
        val odds = ArrayList<Complex>(n / 2)
        for (i in 0 until n step 2) {
            evens.add(x[i])
            odds.add(x[i + 1])
        }

        val q = fft(evens)
        val r = fft(odds)

        val result = Array(n) { Complex(0.0, 0.0) }
        val half = n / 2
        for (k in 0 until half) {
            val angle = -2.0 * PI * k / n
            val wk = Complex(cos(angle), sin(angle))
            val wkRk = wk * r[k]
            result[k] = q[k] + wkRk
            result[k + half] = q[k] - wkRk
        }
        return result.toList()
    }

    /**
     * Inverse FFT.
     */
    fun ifft(X: List<Complex>): List<Complex> {
        val n = X.size
        if (n == 0) return emptyList()
        if ((n and (n - 1)) != 0) {
            return idft(X)
        }
        // IFFT via FFT: conjugate -> FFT -> conjugate -> divide by N
        val conj = X.map { it.conjugate }
        val f = fft(conj)
        return f.map { it.conjugate / n.toDouble() }
    }

    fun analyzeSpectrum(x: List<Double>, sampleRate: Double = 1.0): SpectrumResult {
        val n = x.size
        if (n == 0) return SpectrumResult(emptyList(), emptyList(), emptyList(), emptyList())
        val complexInput = x.map { Complex(it, 0.0) }
        val dftOut = if ((n and (n - 1)) == 0) fft(complexInput) else dft(complexInput)

        val freqs = ArrayList<Double>(n)
        val mag = ArrayList<Double>(n)
        val phase = ArrayList<Double>(n)
        val psd = ArrayList<Double>(n)

        val df = sampleRate / n
        for (k in 0 until n) {
            freqs.add(k * df)
            val m = dftOut[k].magnitude
            mag.add(m)
            phase.add(dftOut[k].phase)
            psd.add((m * m) / n.toDouble())
        }
        return SpectrumResult(freqs, mag, phase, psd)
    }
}
