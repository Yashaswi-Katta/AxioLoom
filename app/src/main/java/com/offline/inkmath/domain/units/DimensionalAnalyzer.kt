package com.offline.inkmath.domain.units

import com.offline.inkmath.domain.model.BinaryOperator
import com.offline.inkmath.domain.model.MathExpr
import com.offline.inkmath.domain.model.UnaryOperator

data class DimensionVector(
    val lengthM: Int = 0,       // Meter (m)
    val massKg: Int = 0,        // Kilogram (kg)
    val timeS: Int = 0,         // Second (s)
    val currentA: Int = 0,      // Ampere (A)
    val temperatureK: Int = 0,  // Kelvin (K)
    val amountMol: Int = 0,     // Mole (mol)
    val intensityCd: Int = 0    // Candela (cd)
) {
    val isDimensionless: Boolean
        get() = lengthM == 0 && massKg == 0 && timeS == 0 && currentA == 0 &&
                temperatureK == 0 && amountMol == 0 && intensityCd == 0

    operator fun plus(other: DimensionVector) = DimensionVector(
        lengthM = lengthM + other.lengthM,
        massKg = massKg + other.massKg,
        timeS = timeS + other.timeS,
        currentA = currentA + other.currentA,
        temperatureK = temperatureK + other.temperatureK,
        amountMol = amountMol + other.amountMol,
        intensityCd = intensityCd + other.intensityCd
    )

    operator fun minus(other: DimensionVector) = DimensionVector(
        lengthM = lengthM - other.lengthM,
        massKg = massKg - other.massKg,
        timeS = timeS - other.timeS,
        currentA = currentA - other.currentA,
        temperatureK = temperatureK - other.temperatureK,
        amountMol = amountMol - other.amountMol,
        intensityCd = intensityCd - other.intensityCd
    )

    operator fun times(scalar: Int) = DimensionVector(
        lengthM = lengthM * scalar,
        massKg = massKg * scalar,
        timeS = timeS * scalar,
        currentA = currentA * scalar,
        temperatureK = temperatureK * scalar,
        amountMol = amountMol * scalar,
        intensityCd = intensityCd * scalar
    )

    companion object {
        val DIMENSIONLESS = DimensionVector()

        // Base SI Units
        val METER = DimensionVector(lengthM = 1)
        val KILOGRAM = DimensionVector(massKg = 1)
        val SECOND = DimensionVector(timeS = 1)
        val AMPERE = DimensionVector(currentA = 1)
        val KELVIN = DimensionVector(temperatureK = 1)
        val MOLE = DimensionVector(amountMol = 1)
        val CANDELA = DimensionVector(intensityCd = 1)

        // Derived Mechanical & Energetic Units
        val HERTZ = DimensionVector(timeS = -1)
        val NEWTON = DimensionVector(lengthM = 1, massKg = 1, timeS = -2)
        val JOULE = DimensionVector(lengthM = 2, massKg = 1, timeS = -2)
        val WATT = DimensionVector(lengthM = 2, massKg = 1, timeS = -3)
        val PASCAL = DimensionVector(lengthM = -1, massKg = 1, timeS = -2)

        // Derived Electrical & Magnetic Units
        val COULOMB = DimensionVector(timeS = 1, currentA = 1)
        val VOLT = DimensionVector(lengthM = 2, massKg = 1, timeS = -3, currentA = -1)
        val OHM = DimensionVector(lengthM = 2, massKg = 1, timeS = -3, currentA = -2)
        val FARAD = DimensionVector(lengthM = -2, massKg = -1, timeS = 4, currentA = 2)
        val HENRY = DimensionVector(lengthM = 2, massKg = 1, timeS = -2, currentA = -2)
        val WEBER = DimensionVector(lengthM = 2, massKg = 1, timeS = -2, currentA = -1)
        val TESLA = DimensionVector(massKg = 1, timeS = -2, currentA = -1)
    }

    /**
     * Compact human-readable SI representation (e.g. "[V]", "[Ω]", "[s]").
     */
    fun toCompactUnitString(): String {
        if (isDimensionless) return ""
        return when (this) {
            SECOND -> "[s]"
            HERTZ -> "[Hz]"
            METER -> "[m]"
            KILOGRAM -> "[kg]"
            NEWTON -> "[N]"
            JOULE -> "[J]"
            WATT -> "[W]"
            PASCAL -> "[Pa]"
            AMPERE -> "[A]"
            VOLT -> "[V]"
            OHM -> "[Ω]"
            FARAD -> "[F]"
            HENRY -> "[H]"
            COULOMB -> "[C]"
            WEBER -> "[Wb]"
            TESLA -> "[T]"
            KELVIN -> "[K]"
            else -> {
                val parts = mutableListOf<String>()
                if (massKg != 0) parts.add("kg" + if (massKg != 1) "^$massKg" else "")
                if (lengthM != 0) parts.add("m" + if (lengthM != 1) "^$lengthM" else "")
                if (timeS != 0) parts.add("s" + if (timeS != 1) "^$timeS" else "")
                if (currentA != 0) parts.add("A" + if (currentA != 1) "^$currentA" else "")
                if (temperatureK != 0) parts.add("K" + if (temperatureK != 1) "^$temperatureK" else "")
                if (amountMol != 0) parts.add("mol" + if (amountMol != 1) "^$amountMol" else "")
                if (intensityCd != 0) parts.add("cd" + if (intensityCd != 1) "^$intensityCd" else "")
                "[" + parts.joinToString("·") + "]"
            }
        }
    }
}

data class DimensionalMismatchWarning(
    val message: String,
    val leftDim: DimensionVector,
    val rightDim: DimensionVector
)

data class DimensionalAnalysisResult(
    val dimension: DimensionVector,
    val compactUnit: String,
    val warnings: List<DimensionalMismatchWarning>
)

/**
 * 100% Offline 7-Tuple SI Base Dimension Algebra & Consistency Engine.
 */
object DimensionalAnalyzer {

    // Known standard physical units mapped from text strings
    val UNIT_LOOKUP: Map<String, DimensionVector> = mapOf(
        "s" to DimensionVector.SECOND,
        "sec" to DimensionVector.SECOND,
        "hz" to DimensionVector.HERTZ,
        "m" to DimensionVector.METER,
        "meter" to DimensionVector.METER,
        "kg" to DimensionVector.KILOGRAM,
        "g" to DimensionVector.KILOGRAM,
        "n" to DimensionVector.NEWTON,
        "j" to DimensionVector.JOULE,
        "w" to DimensionVector.WATT,
        "pa" to DimensionVector.PASCAL,
        "a" to DimensionVector.AMPERE,
        "amp" to DimensionVector.AMPERE,
        "v" to DimensionVector.VOLT,
        "volt" to DimensionVector.VOLT,
        "ohm" to DimensionVector.OHM,
        "omega" to DimensionVector.OHM,
        "\\omega" to DimensionVector.OHM,
        "f" to DimensionVector.FARAD,
        "h" to DimensionVector.HENRY,
        "c" to DimensionVector.COULOMB,
        "wb" to DimensionVector.WEBER,
        "t" to DimensionVector.TESLA,
        "k" to DimensionVector.KELVIN
    )

    // Standard variable names heuristic units in ECE/Physics
    val DEFAULT_VAR_DIMENSIONS: Map<String, DimensionVector> = mapOf(
        "v" to DimensionVector.VOLT,
        "v_in" to DimensionVector.VOLT,
        "v_out" to DimensionVector.VOLT,
        "i" to DimensionVector.AMPERE,
        "i_in" to DimensionVector.AMPERE,
        "r" to DimensionVector.OHM,
        "r_1" to DimensionVector.OHM,
        "r_2" to DimensionVector.OHM,
        "c" to DimensionVector.FARAD,
        "c_1" to DimensionVector.FARAD,
        "l" to DimensionVector.HENRY,
        "l_1" to DimensionVector.HENRY,
        "t" to DimensionVector.SECOND,
        "f" to DimensionVector.HERTZ,
        "p" to DimensionVector.WATT,
        "e" to DimensionVector.JOULE
    )

    /**
     * Checks an expression AST for dimensional consistency and infers resulting dimension.
     */
    fun analyze(
        expr: MathExpr,
        variableDimensions: Map<String, DimensionVector> = emptyMap()
    ): DimensionalAnalysisResult {
        val warnings = mutableListOf<DimensionalMismatchWarning>()
        val dim = inferDimension(expr, variableDimensions, warnings)
        return DimensionalAnalysisResult(
            dimension = dim,
            compactUnit = dim.toCompactUnitString(),
            warnings = warnings
        )
    }

    private fun inferDimension(
        expr: MathExpr,
        bindings: Map<String, DimensionVector>,
        warnings: MutableList<DimensionalMismatchWarning>
    ): DimensionVector {
        return when (expr) {
            is MathExpr.Number -> DimensionVector.DIMENSIONLESS
            is MathExpr.Constant -> DimensionVector.DIMENSIONLESS
            is MathExpr.Symbol -> {
                val key = expr.name.lowercase()
                bindings[expr.name]
                    ?: bindings[key]
                    ?: UNIT_LOOKUP[key]
                    ?: DEFAULT_VAR_DIMENSIONS[key]
                    ?: DimensionVector.DIMENSIONLESS
            }
            is MathExpr.Unary -> inferDimension(expr.value, bindings, warnings)
            is MathExpr.Binary -> {
                val leftDim = inferDimension(expr.left, bindings, warnings)
                val rightDim = inferDimension(expr.right, bindings, warnings)

                when (expr.op) {
                    MathExpr.Op.ADD, MathExpr.Op.SUBTRACT -> {
                        if (leftDim != rightDim && !leftDim.isDimensionless && !rightDim.isDimensionless) {
                            val msg = "Cannot ${if (expr.op == MathExpr.Op.ADD) "add" else "subtract"} ${leftDim.toCompactUnitString()} and ${rightDim.toCompactUnitString()}"
                            warnings.add(DimensionalMismatchWarning(msg, leftDim, rightDim))
                        }
                        if (leftDim.isDimensionless) rightDim else leftDim
                    }
                    MathExpr.Op.MULTIPLY -> leftDim + rightDim
                    MathExpr.Op.DIVIDE -> leftDim - rightDim
                    MathExpr.Op.POWER -> {
                        val expVal = (expr.right as? MathExpr.Number)?.value?.toIntOrNull() ?: 1
                        leftDim * expVal
                    }
                    MathExpr.Op.PARALLEL -> {
                        if (leftDim != rightDim && !leftDim.isDimensionless && !rightDim.isDimensionless) {
                            val msg = "Parallel impedance requires matching dimensions, got ${leftDim.toCompactUnitString()} and ${rightDim.toCompactUnitString()}"
                            warnings.add(DimensionalMismatchWarning(msg, leftDim, rightDim))
                        }
                        if (leftDim.isDimensionless) rightDim else leftDim
                    }
                }
            }
            is MathExpr.Call -> {
                val argDim = if (expr.arguments.isNotEmpty()) inferDimension(expr.arguments[0], bindings, warnings) else DimensionVector.DIMENSIONLESS
                if (!argDim.isDimensionless) {
                    warnings.add(
                        DimensionalMismatchWarning(
                            "Transcendental function '${expr.function}' requires dimensionless argument, got ${argDim.toCompactUnitString()}",
                            argDim,
                            DimensionVector.DIMENSIONLESS
                        )
                    )
                }
                DimensionVector.DIMENSIONLESS
            }
            is MathExpr.Builtin -> {
                val argDim = if (expr.arguments.isNotEmpty()) inferDimension(expr.arguments[0], bindings, warnings) else DimensionVector.DIMENSIONLESS
                when (expr.function) {
                    MathExpr.Function.ABS,
                    MathExpr.Function.RE,
                    MathExpr.Function.IM,
                    MathExpr.Function.CONJUGATE,
                    MathExpr.Function.TRANSPOSE -> argDim
                    MathExpr.Function.SQRT -> argDim * 1 // square root preserves physical unit scale
                    else -> {
                        if (!argDim.isDimensionless) {
                            warnings.add(
                                DimensionalMismatchWarning(
                                    "Function '${expr.function.syntax}' requires dimensionless argument, got ${argDim.toCompactUnitString()}",
                                    argDim,
                                    DimensionVector.DIMENSIONLESS
                                )
                            )
                        }
                        DimensionVector.DIMENSIONLESS
                    }
                }
            }
            is MathExpr.Phasor -> inferDimension(expr.magnitude, bindings, warnings)
            is MathExpr.Integral -> inferDimension(expr.body, bindings, warnings) + (bindings[expr.variable] ?: DimensionVector.SECOND)
            is MathExpr.Derivative -> inferDimension(expr.body, bindings, warnings) - (bindings[expr.variable] ?: DimensionVector.SECOND) * expr.order
            is MathExpr.Sum -> inferDimension(expr.body, bindings, warnings)
            is MathExpr.Limit -> inferDimension(expr.body, bindings, warnings)
            is MathExpr.Matrix -> if (expr.rows.isNotEmpty() && expr.rows[0].isNotEmpty()) inferDimension(expr.rows[0][0], bindings, warnings) else DimensionVector.DIMENSIONLESS
            is MathExpr.LaplaceTransform -> inferDimension(expr.expr, bindings, warnings) + DimensionVector.SECOND
            is MathExpr.InverseLaplaceTransform -> inferDimension(expr.expr, bindings, warnings) - DimensionVector.SECOND
            is MathExpr.ZTransform -> inferDimension(expr.expr, bindings, warnings)
            is MathExpr.Gradient -> inferDimension(expr.scalarField, bindings, warnings) - DimensionVector.METER
            is MathExpr.Divergence -> inferDimension(expr.vectorField, bindings, warnings) - DimensionVector.METER
            is MathExpr.Curl -> inferDimension(expr.vectorField, bindings, warnings) - DimensionVector.METER
            is MathExpr.TaylorSeries -> inferDimension(expr.body, bindings, warnings)
            is MathExpr.Relational -> DimensionVector.DIMENSIONLESS
            is MathExpr.Otherwise -> DimensionVector.DIMENSIONLESS
            is MathExpr.Piecewise -> if (expr.branches.isNotEmpty()) inferDimension(expr.branches[0].value, bindings, warnings) else DimensionVector.DIMENSIONLESS
            is MathExpr.DiscreteSequence -> if (expr.elements.isNotEmpty()) inferDimension(expr.elements[0], bindings, warnings) else DimensionVector.DIMENSIONLESS
            is MathExpr.DiscreteConvolution -> inferDimension(expr.left, bindings, warnings) + inferDimension(expr.right, bindings, warnings)
            is MathExpr.CircularConvolution -> inferDimension(expr.left, bindings, warnings) + inferDimension(expr.right, bindings, warnings)
            is MathExpr.DiscreteFourierTransform -> inferDimension(expr.expr, bindings, warnings)
            is MathExpr.IndexedSequence -> inferDimension(MathExpr.Symbol(expr.name), bindings, warnings)
        }
    }
}
