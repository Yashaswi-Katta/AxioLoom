package com.offline.inkmath.domain.cas

import com.offline.inkmath.domain.model.*

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
            is MathIntent.EvaluationQuery -> SymjaCompiler.expression(intent.expression).command
            is MathIntent.Assignment -> "${intent.variableName} = ${SymjaCompiler.expression(intent.rhs).command}"
            is MathIntent.FunctionDef -> "${intent.funcName}(${intent.params.joinToString()}) = ${SymjaCompiler.expression(intent.body).command}"
            is MathIntent.EquationSolve -> "${SymjaCompiler.expression(intent.lhs).command} = ${SymjaCompiler.expression(intent.rhs).command}"
            is MathIntent.SystemOfEq -> intent.equations.joinToString(" \\\\ ") { "${SymjaCompiler.expression(it.lhs).command} = ${SymjaCompiler.expression(it.rhs).command}" }
            is MathIntent.OdeSolve -> "${SymjaCompiler.expression(intent.lhs).command} = ${SymjaCompiler.expression(intent.rhs).command}"
        }
        steps.add(DerivationStep("Initial Problem", initialLatex))

        // Step 2: Variable Substitution / Context (if referenced symbols exist in bindings)
        val referenced = intent.referencedSymbols
        val boundSubstitutions = referenced.filter { it.name in bindings.values }
        if (boundSubstitutions.isNotEmpty()) {
            val subText = boundSubstitutions.joinToString(", ") { key ->
                val boundVal = bindings.values[key.name]
                "${key.name} = ${boundVal?.let { SymjaCompiler.expression(it).command } ?: "?"}"
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
            val lhsCmd = SymjaCompiler.expression(intent.lhs, bindings).command
            val rhsCmd = SymjaCompiler.expression(intent.rhs, bindings).command
            val factorCmd = "Factor(($lhsCmd) - ($rhsCmd))"
            val factoredLatex = cas.evalCommandToLatex(factorCmd)
            if (factoredLatex.isNotBlank()) {
                steps.add(DerivationStep("Standard / Factored Form", "$factoredLatex = 0"))
            }
        } catch (_: Exception) {}
    }
}
