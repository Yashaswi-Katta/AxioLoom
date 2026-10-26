package com.axioloom.app.domain.cas

import com.axioloom.app.domain.model.*
import java.util.Locale

/**
 * A pedagogical breakdown step showing one stage in solving a mathematical problem.
 */
data class DerivationStep(
    val title: String,
    val latexExpression: String
)

/**
 * Step-by-Step Derivation Breakdown engine.
 * Deconstructs mathematical solutions into clear, pedagogical stages:
 * 1. Initial recognized problem
 * 2. Variable substitution and context
 * 3. Intermediate calculus/algebra operations (antiderivatives, char-polys, partial fractions, factored forms)
 * 4. Exact symbolic result
 * 5. Decimal approximation
 */
object StepDerivationEngine {

    suspend fun deriveSteps(
        block: EquationBlock,
        bindings: BindingSnapshot,
        cas: OfflineCasEngine
    ): List<DerivationStep> {
        val steps = mutableListOf<DerivationStep>()
        val intent = block.intent ?: return emptyList()

        // Step 1: Initial Problem
        val initialLatex = block.recognizedLatex ?: when (intent) {
            is MathIntent.EvaluationQuery -> SymjaCompiler.decode(SymjaCompiler.expression(intent.expression).command)
            is MathIntent.Assignment -> "${intent.variableName} = ${SymjaCompiler.decode(SymjaCompiler.expression(intent.rhs).command)}"
            is MathIntent.FunctionDef -> "${intent.funcName}(${intent.params.joinToString()}) = ${SymjaCompiler.decode(SymjaCompiler.expression(intent.body).command)}"
            is MathIntent.EquationSolve -> "${SymjaCompiler.decode(SymjaCompiler.expression(intent.lhs).command)} = ${SymjaCompiler.decode(SymjaCompiler.expression(intent.rhs).command)}"
            is MathIntent.SystemOfEq -> intent.equations.joinToString(" \\\\ ") { "${SymjaCompiler.decode(SymjaCompiler.expression(it.lhs).command)} = ${SymjaCompiler.decode(SymjaCompiler.expression(it.rhs).command)}" }
            is MathIntent.OdeSolve -> "${SymjaCompiler.decode(SymjaCompiler.expression(intent.lhs).command)} = ${SymjaCompiler.decode(SymjaCompiler.expression(intent.rhs).command)}"
        }
        steps.add(DerivationStep("Initial Problem", initialLatex))

        // Step 2: Variable Substitution / Context (if referenced symbols exist in bindings)
        val referenced = intent.referencedSymbols
        val boundSubstitutions = referenced.filter { it.name in bindings.values }
        if (boundSubstitutions.isNotEmpty()) {
            val subText = boundSubstitutions.joinToString(", ") { key ->
                val boundVal = bindings.values[key.name]
                "${key.name} = ${boundVal?.let { SymjaCompiler.decode(SymjaCompiler.expression(it).command) } ?: "?"}"
            }
            try {
                val compiledSub = SymjaCompiler.compile(intent, bindings)
                val subLatex = cas.evalCommandToLatex("HoldForm(${compiledSub.command})", compiledSub.latexAliases)
                steps.add(DerivationStep("Variable Substitution ($subText)", subLatex))
            } catch (_: Exception) {
                steps.add(DerivationStep("Variable Context", subText))
            }
        }

        // Step 3: Calculus / Algebra Intermediate Steps
        when (intent) {
            is MathIntent.EvaluationQuery -> {
                deriveExpressionIntermediates(intent.expression, bindings, cas, steps)
            }
            is MathIntent.EquationSolve -> {
                deriveSolveIntermediates(intent, bindings, cas, steps)
            }
            is MathIntent.Assignment -> {
                deriveExpressionIntermediates(intent.rhs, bindings, cas, steps)
            }
            is MathIntent.FunctionDef -> {
                deriveExpressionIntermediates(intent.body, bindings, cas, steps)
            }
            else -> Unit
        }

        // MVT / Calculus Theorems for single-variable functions
        val funcToCheck = when (intent) {
            is MathIntent.FunctionDef -> if (intent.params.size == 1) intent.funcName to intent.params[0] to intent.body else null
            is MathIntent.EvaluationQuery -> {
                if (intent.expression is MathExpr.Call && intent.expression.arguments.size == 1) {
                    val fnName = intent.expression.function
                    bindings.functions[fnName]?.let { fnDef ->
                        if (fnDef.params.size == 1) fnName to fnDef.params[0] to fnDef.body else null
                    }
                } else null
            }
            else -> null
        }
        if (funcToCheck != null) {
            try {
                val varName = funcToCheck.first.second
                val bodyExpr = funcToCheck.second
                val bodyCompiled = SymjaCompiler.expression(bodyExpr, bindings)
                val aBound = (bindings.values["a"] as? MathExpr.Number)?.value?.toDoubleOrNull() ?: 2.0
                val lagrange = CalculusTheoremAnalyzer(cas).verifyLagrange(bodyCompiled.command, varName, 0.0, aBound)
                if (lagrange.isValid && lagrange.rootsC.isNotEmpty()) {
                    val rootsStr = lagrange.rootsC.joinToString(", ") { String.format(Locale.US, "%.4f", it) }
                    steps.add(DerivationStep("Lagrange Mean Value Theorem [0, $aBound]", "f'($rootsStr) = ${lagrange.secantSlopeLatex}"))
                }
                val rolles = CalculusTheoremAnalyzer(cas).verifyRolles(bodyCompiled.command, varName, 0.0, aBound)
                if (rolles.isValid && rolles.rootsC.isNotEmpty()) {
                    val rootsStr = rolles.rootsC.joinToString(", ") { String.format(Locale.US, "%.4f", it) }
                    steps.add(DerivationStep("Rolle's Theorem [0, $aBound]", "f'($rootsStr) = 0"))
                }
            } catch (_: Exception) {}
        }

        // Step 4: Exact Symbolic Result
        val exactResult = try {
            cas.evaluate(intent, bindings, CasMode.SYMBOLIC, block.transform)
        } catch (_: Exception) {
            null
        }

        if (exactResult != null) {
            val title = when (intent) {
                is MathIntent.EquationSolve -> "Exact Solution for ${intent.targetVar}"
                is MathIntent.Assignment -> "Assigned Value"
                is MathIntent.FunctionDef -> "Function Defined"
                else -> "Exact Result"
            }
            steps.add(DerivationStep(title, exactResult.latex))

            // Step 5: Decimal Approximation (if applicable)
            if (!exactResult.approximate) {
                try {
                    val numericResult = cas.evaluate(intent, bindings, CasMode.NUMERIC, block.transform)
                    if (numericResult.latex != exactResult.latex && !numericResult.latex.contains("Error")) {
                        steps.add(DerivationStep("Decimal Approximation", "\\approx ${numericResult.latex}"))
                    }
                } catch (_: Exception) {
                    // Numeric approximation optional
                }
            }
        }

        return steps
    }

    private suspend fun deriveExpressionIntermediates(
        expr: MathExpr,
        bindings: BindingSnapshot,
        cas: OfflineCasEngine,
        steps: MutableList<DerivationStep>
    ) {
        // Matrix Analysis if expression represents or operates on a matrix
        val targetMatrix: MathExpr.Matrix? = when (expr) {
            is MathExpr.Matrix -> expr
            is MathExpr.Symbol -> bindings.values[expr.name] as? MathExpr.Matrix
            is MathExpr.Builtin -> {
                val arg = expr.arguments.firstOrNull()
                when (arg) {
                    is MathExpr.Matrix -> arg
                    is MathExpr.Symbol -> bindings.values[arg.name] as? MathExpr.Matrix
                    else -> null
                }
            }
            else -> null
        }
        if (targetMatrix != null) {
            try {
                val matrixAnalysis = MatrixAnalyzer(cas).analyzeMatrix(targetMatrix, bindings)
                matrixAnalysis.stepsLatex.forEach { stepText ->
                    steps.add(DerivationStep("Matrix Spectral Decomposition", stepText))
                }
            } catch (_: Exception) {}
        }

        when (expr) {
            is MathExpr.Integral -> {
                if (expr.lower != null && expr.upper != null) {
                    try {
                        val bodyCompiled = SymjaCompiler.expression(expr.body, bindings)
                        val indefCommand = "Integrate(${bodyCompiled.command}, ${expr.variable})"
                        val antiderivLatex = cas.evalCommandToLatex(indefCommand, bodyCompiled.latexAliases)
                        val lowerLatex = cas.evalCommandToLatex(SymjaCompiler.expression(expr.lower, bindings).command)
                        val upperLatex = cas.evalCommandToLatex(SymjaCompiler.expression(expr.upper, bindings).command)
                        steps.add(DerivationStep("Antiderivative Evaluation [F(x)]_a^b", "\\left[ $antiderivLatex \\right]_{$lowerLatex}^{$upperLatex}"))
                    } catch (_: Exception) {}
                }
            }
            is MathExpr.Builtin -> {
                if (expr.function == MathExpr.Function.EIGENVALUES && expr.arguments.isNotEmpty()) {
                    try {
                        val matCompiled = SymjaCompiler.expression(expr.arguments[0], bindings)
                        val charPolyCmd = "Det(${matCompiled.command} - lambda * IdentityMatrix(Length(${matCompiled.command})))"
                        val charPolyLatex = cas.evalCommandToLatex(charPolyCmd, matCompiled.latexAliases)
                        steps.add(DerivationStep("Characteristic Polynomial \\det(A - \\lambda I) = 0", "$charPolyLatex = 0"))
                    } catch (_: Exception) {}
                }
            }
            is MathExpr.LaplaceTransform -> {
                try {
                    val innerCompiled = SymjaCompiler.expression(expr.expr, bindings)
                    val kernelLatex = cas.evalCommandToLatex(innerCompiled.command, innerCompiled.latexAliases)
                    steps.add(DerivationStep("Laplace Integral Definition", "\\int_{0}^{\\infty} \\left( $kernelLatex \\right) e^{-${expr.freqVar} ${expr.timeVar}} \\, d${expr.timeVar}"))
                } catch (_: Exception) {}
            }
            is MathExpr.InverseLaplaceTransform -> {
                try {
                    val innerCompiled = SymjaCompiler.expression(expr.expr, bindings)
                    val decompCmd = "Apart(${innerCompiled.command}, ${expr.freqVar})"
                    val decompLatex = cas.evalCommandToLatex(decompCmd, innerCompiled.latexAliases)
                    if (decompLatex != innerCompiled.command) {
                        steps.add(DerivationStep("Partial Fraction Decomposition", decompLatex))
                    }
                } catch (_: Exception) {}
            }
            is MathExpr.Gradient -> {
                steps.add(DerivationStep("Gradient Definition", "\\nabla f = \\left[ \\frac{\\partial f}{\\partial x}, \\frac{\\partial f}{\\partial y}, \\frac{\\partial f}{\\partial z} \\right]"))
            }
            is MathExpr.Divergence -> {
                steps.add(DerivationStep("Divergence Definition", "\\nabla \\cdot \\mathbf{F} = \\frac{\\partial F_x}{\\partial x} + \\frac{\\partial F_y}{\\partial y} + \\frac{\\partial F_z}{\\partial z}"))
            }
            is MathExpr.Curl -> {
                steps.add(DerivationStep("Curl Definition", "\\nabla \\times \\mathbf{F} = \\det \\begin{pmatrix} \\mathbf{i} & \\mathbf{j} & \\mathbf{k} \\\\ \\frac{\\partial}{\\partial x} & \\frac{\\partial}{\\partial y} & \\frac{\\partial}{\\partial z} \\\\ F_x & F_y & F_z \\end{pmatrix}"))
            }
            else -> Unit
        }
    }

    private suspend fun deriveSolveIntermediates(
        intent: MathIntent.EquationSolve,
        bindings: BindingSnapshot,
        cas: OfflineCasEngine,
        steps: MutableList<DerivationStep>
    ) {
        try {
            val lhsCompiled = SymjaCompiler.expression(intent.lhs, bindings)
            val rhsCompiled = SymjaCompiler.expression(intent.rhs, bindings)
            val factorCmd = "Factor((${lhsCompiled.command}) - (${rhsCompiled.command}))"
            val factoredLatex = cas.evalCommandToLatex(factorCmd, lhsCompiled.latexAliases + rhsCompiled.latexAliases)
            if (factoredLatex.isNotBlank()) {
                steps.add(DerivationStep("Standard / Factored Form", "$factoredLatex = 0"))
            }
        } catch (_: Exception) {}
    }
}
