package com.offline.inkmath.domain.model

/** Only these nodes may cross the recognition-to-CAS boundary. No raw CAS code is accepted. */
sealed interface MathExpr {
    data class Number(val value: String) : MathExpr {
        init { require(value.length <= 128 && value.matches(Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))) }
    }
    data class Symbol(val name: String) : MathExpr { init { require(isIdentifier(name)) } }
    enum class Constant : MathExpr { PI, E, INFINITY, I }
    data class Unary(val negative: Boolean, val value: MathExpr) : MathExpr
    data class Binary(val op: Op, val left: MathExpr, val right: MathExpr) : MathExpr
    data class Call(val function: String, val arguments: List<MathExpr>) : MathExpr {
        init { require(isIdentifier(function)); require(arguments.isNotEmpty()) }
    }
    data class Builtin(val function: Function, val arguments: List<MathExpr>) : MathExpr {
        init { require(arguments.size == function.arity) }
    }
    data class Integral(val body: MathExpr, val variable: String, val lower: MathExpr?, val upper: MathExpr?) : MathExpr {
        init { require(isIdentifier(variable)); require((lower == null) == (upper == null)) }
    }
    data class Derivative(val body: MathExpr, val variable: String, val order: Int = 1) : MathExpr {
        init { require(isIdentifier(variable)); require(order >= 1) }
    }
    data class Sum(val body: MathExpr, val variable: String, val lower: MathExpr, val upper: MathExpr) : MathExpr {
        init { require(isIdentifier(variable)) }
    }
    data class Limit(val body: MathExpr, val variable: String, val toward: MathExpr) : MathExpr { init { require(isIdentifier(variable)) } }
    data class Matrix(val rows: List<List<MathExpr>>) : MathExpr {
        init { require(rows.isNotEmpty() && rows.first().isNotEmpty()); require(rows.all { it.size == rows.first().size }) }
    }
    data class Phasor(val magnitude: MathExpr, val angleDegrees: MathExpr) : MathExpr
    data class LaplaceTransform(val expr: MathExpr, val timeVar: String = "t", val freqVar: String = "s") : MathExpr {
        init { require(isIdentifier(timeVar)); require(isIdentifier(freqVar)) }
    }
    data class InverseLaplaceTransform(val expr: MathExpr, val freqVar: String = "s", val timeVar: String = "t") : MathExpr {
        init { require(isIdentifier(freqVar)); require(isIdentifier(timeVar)) }
    }
    data class ZTransform(val expr: MathExpr, val discreteVar: String = "n", val zVar: String = "z") : MathExpr {
        init { require(isIdentifier(discreteVar)); require(isIdentifier(zVar)) }
    }
    data class Gradient(val scalarField: MathExpr, val coords: List<String> = listOf("x", "y", "z")) : MathExpr {
        init { require(coords.isNotEmpty() && coords.all(::isIdentifier)) }
    }
    data class Divergence(val vectorField: MathExpr, val coords: List<String> = listOf("x", "y", "z")) : MathExpr {
        init { require(coords.isNotEmpty() && coords.all(::isIdentifier)) }
    }
    data class Curl(val vectorField: MathExpr, val coords: List<String> = listOf("x", "y", "z")) : MathExpr {
        init { require(coords.isNotEmpty() && coords.all(::isIdentifier)) }
    }
    data class TaylorSeries(val body: MathExpr, val variable: String = "x", val center: MathExpr = Number("0"), val order: Int = 5) : MathExpr {
        init { require(isIdentifier(variable)); require(order in 1..20) }
    }
    enum class RelOp(val syntax: String) { LT("<"), LE("<="), GT(">"), GE(">="), EQ("=="), NE("!=") }
    data class Relational(val op: RelOp, val left: MathExpr, val right: MathExpr) : MathExpr
    data class PiecewiseBranch(val value: MathExpr, val condition: MathExpr)
    data class Piecewise(val branches: List<PiecewiseBranch>, val defaultExpr: MathExpr = Number("0")) : MathExpr {
        init { require(branches.isNotEmpty()) }
    }
    data object Otherwise : MathExpr
    data class DiscreteSequence(val elements: List<MathExpr>) : MathExpr
    data class DiscreteConvolution(val left: MathExpr, val right: MathExpr) : MathExpr
    data class CircularConvolution(val left: MathExpr, val right: MathExpr, val length: MathExpr? = null) : MathExpr
    data class DiscreteFourierTransform(val expr: MathExpr, val inverse: Boolean = false) : MathExpr
    data class IndexedSequence(val name: String, val index: MathExpr) : MathExpr {
        init { require(isIdentifier(name)) }
    }
    enum class Op(val syntax: String) { ADD("+"), SUBTRACT("-"), MULTIPLY("*"), DIVIDE("/"), POWER("^"), PARALLEL("||") }
    enum class Function(val syntax: String, val arity: Int = 1) {
        SIN("Sin"), COS("Cos"), TAN("Tan"),
        ASIN("ArcSin"), ACOS("ArcCos"), ATAN("ArcTan"),
        SINH("Sinh"), COSH("Cosh"), TANH("Tanh"),
        LOG("Log"), EXP("Exp"), SQRT("Sqrt"), ABS("Abs"),
        FACTORIAL("Factorial"),
        DET("Det"), TRANSPOSE("Transpose"), INVERSE("Inverse"),
        EIGENVALUES("Eigenvalues"), EIGENVECTORS("Eigenvectors"),
        TRACE("Tr"), RANK("MatrixRank"), RREF("RowReduce"),
        RE("Re"), IM("Im"), CONJUGATE("Conjugate"), ARG("Arg"),
        UNIT_STEP("UnitStep"), DIRAC_DELTA("DiracDelta")
    }

    companion object {
        fun Variable(name: String): Symbol = Symbol(name)
        fun BinaryOp(op: Op, left: MathExpr, right: MathExpr): Binary = Binary(op, left, right)
        fun UnaryOp(negative: Boolean, value: MathExpr): Unary = Unary(negative, value)
        fun FunctionCall(name: String, argument: MathExpr): Call = Call(name, listOf(argument))
    }
}

typealias BinaryOperator = MathExpr.Op
enum class UnaryOperator { NEGATE, FACTORIAL }

fun isIdentifier(value: String): Boolean = value.matches(Regex("[a-zA-Z][a-zA-Z0-9]*(?:_[a-zA-Z0-9]+)?"))

data class SymbolKey(val name: String, val kind: Kind = Kind.VALUE) {
    init { require(isIdentifier(name)) }
    enum class Kind { VALUE, FUNCTION }
    val displayName: String get() = if (kind == Kind.VALUE) name else "$name()"
}

data class MathEquation(val lhs: MathExpr, val rhs: MathExpr)

sealed interface MathIntent {
    val isApproximate: Boolean
    data class Assignment(val variableName: String, val rhs: MathExpr, override val isApproximate: Boolean = false) : MathIntent {
        init { require(isIdentifier(variableName)) }
        val rhsExpression: String get() = SymjaCompiler.expression(rhs).command
    }
    data class FunctionDef(val funcName: String, val params: List<String>, val body: MathExpr, override val isApproximate: Boolean = false) : MathIntent {
        init { require(isIdentifier(funcName)); require(params.isNotEmpty() && params.all(::isIdentifier) && params.distinct().size == params.size) }
        val bodyExpression: String get() = SymjaCompiler.expression(body).command
    }
    data class EvaluationQuery(val expression: MathExpr, override val isApproximate: Boolean = false) : MathIntent
    data class EquationSolve(val lhs: MathExpr, val rhs: MathExpr, val targetVar: String, override val isApproximate: Boolean = false) : MathIntent {
        init { require(isIdentifier(targetVar)) }
    }
    data class SystemOfEq(val equations: List<MathEquation>, val targetVars: List<String>, override val isApproximate: Boolean = false) : MathIntent {
        init { require(equations.isNotEmpty()); require(targetVars.isNotEmpty() && targetVars.all(::isIdentifier) && targetVars.distinct().size == targetVars.size) }
    }
    data class OdeSolve(val lhs: MathExpr, val rhs: MathExpr, val dependentVar: String, val independentVar: String, override val isApproximate: Boolean = false) : MathIntent {
        init { require(isIdentifier(dependentVar)); require(isIdentifier(independentVar)) }
    }
}

val MathIntent.definedSymbol: SymbolKey?
    get() = when (this) {
        is MathIntent.Assignment -> SymbolKey(variableName)
        is MathIntent.FunctionDef -> SymbolKey(funcName, SymbolKey.Kind.FUNCTION)
        else -> null
    }

fun MathExpr.references(bound: Set<String> = emptySet()): Set<SymbolKey> = when (this) {
    is MathExpr.Number, is MathExpr.Constant -> emptySet()
    is MathExpr.Symbol -> if (name in bound) emptySet() else setOf(SymbolKey(name))
    is MathExpr.Unary -> value.references(bound)
    is MathExpr.Binary -> left.references(bound) + right.references(bound)
    is MathExpr.Call -> setOf(SymbolKey(function, SymbolKey.Kind.FUNCTION)) + arguments.flatMap { it.references(bound) }
    is MathExpr.Builtin -> arguments.flatMap { it.references(bound) }.toSet()
    is MathExpr.Integral -> body.references(bound + variable) + (lower?.references(bound) ?: emptySet()) + (upper?.references(bound) ?: emptySet())
    is MathExpr.Derivative -> body.references(bound + variable)
    is MathExpr.Sum -> body.references(bound + variable) + lower.references(bound) + upper.references(bound)
    is MathExpr.Limit -> body.references(bound + variable) + toward.references(bound)
    is MathExpr.Matrix -> rows.flatten().flatMap { it.references(bound) }.toSet()
    is MathExpr.Phasor -> magnitude.references(bound) + angleDegrees.references(bound)
    is MathExpr.LaplaceTransform -> expr.references(bound + timeVar + freqVar)
    is MathExpr.InverseLaplaceTransform -> expr.references(bound + freqVar + timeVar)
    is MathExpr.ZTransform -> expr.references(bound + discreteVar + zVar)
    is MathExpr.Gradient -> scalarField.references(bound + coords)
    is MathExpr.Divergence -> vectorField.references(bound + coords)
    is MathExpr.Curl -> vectorField.references(bound + coords)
    is MathExpr.TaylorSeries -> body.references(bound + variable) + center.references(bound)
    is MathExpr.Relational -> left.references(bound) + right.references(bound)
    is MathExpr.Piecewise -> branches.flatMap { it.value.references(bound) + it.condition.references(bound) }.toSet() + defaultExpr.references(bound)
    is MathExpr.Otherwise -> emptySet()
    is MathExpr.DiscreteSequence -> elements.flatMap { it.references(bound) }.toSet()
    is MathExpr.DiscreteConvolution -> left.references(bound) + right.references(bound)
    is MathExpr.CircularConvolution -> left.references(bound) + right.references(bound) + (length?.references(bound) ?: emptySet())
    is MathExpr.DiscreteFourierTransform -> expr.references(bound)
    is MathExpr.IndexedSequence -> (if (name in bound) emptySet() else setOf(SymbolKey(name))) + index.references(bound)
}

val MathIntent.referencedSymbols: Set<SymbolKey>
    get() = when (this) {
        is MathIntent.Assignment -> rhs.references()
        is MathIntent.FunctionDef -> body.references(params.toSet())
        is MathIntent.EvaluationQuery -> expression.references()
        is MathIntent.EquationSolve -> lhs.references(setOf(targetVar)) + rhs.references(setOf(targetVar))
        is MathIntent.SystemOfEq -> equations.flatMap { it.lhs.references(targetVars.toSet()) + it.rhs.references(targetVars.toSet()) }.toSet()
        is MathIntent.OdeSolve -> lhs.references(setOf(dependentVar, independentVar)) + rhs.references(setOf(dependentVar, independentVar))
    }

enum class CasMode { SYMBOLIC, NUMERIC }

data class CasResult(val expression: String, val latex: String, val approximate: Boolean)

sealed interface BlockStatus {
    data object Dirty : BlockStatus
    data object Ready : BlockStatus
    data class Error(val message: String) : BlockStatus
}

data class BindingSnapshot(
    val values: Map<String, MathExpr> = emptyMap(),
    val functions: Map<String, MathIntent.FunctionDef> = emptyMap()
)

/** Aliases keep subscripts and user function names separate from Symja's built-in namespace. */
data class CompiledExpression internal constructor(val command: String, val latexAliases: Map<String, String>)

/** Bounded, structural expansion avoids persistent Set/SetDelayed state and stale assignments. */
object SymjaCompiler {
    fun expression(expression: MathExpr, bindings: BindingSnapshot = BindingSnapshot()): CompiledExpression = Builder(bindings).run {
        CompiledExpression(render(expression), aliases.toMap())
    }

    fun compile(intent: MathIntent, bindings: BindingSnapshot = BindingSnapshot()): CompiledExpression = Builder(bindings).run {
        val command = when (intent) {
            is MathIntent.Assignment -> render(intent.rhs)
            is MathIntent.FunctionDef -> render(intent.body, intent.params.associateWith { symbol(it) })
            is MathIntent.EvaluationQuery -> render(intent.expression)
            is MathIntent.EquationSolve -> {
                val locals = mapOf(intent.targetVar to symbol(intent.targetVar))
                "Solve((${render(intent.lhs, locals)})==(${render(intent.rhs, locals)}),${symbol(intent.targetVar)})"
            }
            is MathIntent.SystemOfEq -> {
                val locals = intent.targetVars.associateWith { symbol(it) }
                val equations = intent.equations.joinToString(",", "{", "}") { "(${render(it.lhs, locals)})==(${render(it.rhs, locals)})" }
                "Solve($equations,${intent.targetVars.joinToString(",", "{", "}") { symbol(it) }})"
            }
            is MathIntent.OdeSolve -> {
                val dep = symbol(intent.dependentVar)
                val indep = symbol(intent.independentVar)
                val depCall = "$dep[$indep]"
                val locals = mapOf(intent.dependentVar to depCall)
                val lhsRendered = renderOde(intent.lhs, intent.dependentVar, intent.independentVar, depCall, locals)
                val rhsRendered = renderOde(intent.rhs, intent.dependentVar, intent.independentVar, depCall, locals)
                "DSolve(($lhsRendered)==($rhsRendered),$depCall,$indep)"
            }
        }
        CompiledExpression(command, aliases.toMap())
    }

    private class Builder(val bindings: BindingSnapshot) {
        val aliases = linkedMapOf<String, String>()
        private var budget = 16_384
        private val expanding = mutableSetOf<SymbolKey>()
        fun symbol(name: String): String {
            if ('_' !in name) return name
            val encoded = "inksub" + name.map { it.code.toString(16) }.joinToString("z")
            aliases[encoded] = name.substringBefore('_') + "_{" + name.substringAfter('_') + "}"
            return encoded
        }
        private fun function(name: String): String {
            val encoded = "inkfun" + name.map { it.code.toString(16) }.joinToString("z")
            aliases[encoded] = name.substringBefore('_') + if ('_' in name) "_{${name.substringAfter('_')}}" else ""
            return encoded
        }
        private inline fun expand(key: SymbolKey, action: () -> String): String {
            check(expanding.add(key)) { "Circular variable dependency" }
            try { return action() } finally { expanding.remove(key) }
        }
        fun isMatrix(expr: MathExpr): Boolean =
            expr is MathExpr.Matrix || (expr is MathExpr.Symbol && bindings.values[expr.name] is MathExpr.Matrix)

        fun renderOde(
            expr: MathExpr,
            depVar: String,
            indepVar: String,
            depCall: String,
            locals: Map<String, String>
        ): String {
            return when (expr) {
                is MathExpr.Derivative -> {
                    if (expr.variable == indepVar && expr.body is MathExpr.Symbol && expr.body.name == depVar) {
                        if (expr.order == 1) "D($depCall,$indepVar)" else "D($depCall,{$indepVar,${expr.order}})"
                    } else {
                        render(expr, locals)
                    }
                }
                is MathExpr.Binary -> {
                    "(${renderOde(expr.left, depVar, indepVar, depCall, locals)}${expr.op.syntax}${renderOde(expr.right, depVar, indepVar, depCall, locals)})"
                }
                is MathExpr.Unary -> {
                    if (expr.negative) "(-${renderOde(expr.value, depVar, indepVar, depCall, locals)})"
                    else "(+${renderOde(expr.value, depVar, indepVar, depCall, locals)})"
                }
                else -> render(expr, locals)
            }
        }

        fun render(expr: MathExpr, locals: Map<String, String> = emptyMap(), depth: Int = 0): String {
            require(--budget >= 0 && depth <= 128) { "Expression expansion limit exceeded" }
            fun r(value: MathExpr, scope: Map<String, String> = locals): String = render(value, scope, depth + 1)
            return when (expr) {
                is MathExpr.Number -> expr.value
                is MathExpr.Symbol -> locals[expr.name] ?: bindings.values[expr.name]?.let { value ->
                    expand(SymbolKey(expr.name)) { r(value, emptyMap()) }
                } ?: symbol(expr.name)
                is MathExpr.Constant -> when (expr) {
                    MathExpr.Constant.PI -> "Pi"; MathExpr.Constant.E -> "E"; MathExpr.Constant.INFINITY -> "Infinity"; MathExpr.Constant.I -> "I"
                }
                is MathExpr.Unary -> if (expr.negative) "(-${r(expr.value)})" else "(+${r(expr.value)})"
                is MathExpr.Binary -> {
                    if (expr.op == MathExpr.Op.POWER) {
                        val isNegOne = (expr.right is MathExpr.Unary && expr.right.negative && expr.right.value is MathExpr.Number && (expr.right.value as MathExpr.Number).value == "1") ||
                            (expr.right is MathExpr.Number && expr.right.value == "-1")
                        if (isNegOne && isMatrix(expr.left)) {
                            "Inverse(${r(expr.left)})"
                        } else {
                            "(${r(expr.left)}^${r(expr.right)})"
                        }
                    } else if (expr.op == MathExpr.Op.MULTIPLY) {
                        if (isMatrix(expr.left) || isMatrix(expr.right)) {
                            "Dot(${r(expr.left)},${r(expr.right)})"
                        } else {
                            "(${r(expr.left)}*${r(expr.right)})"
                        }
                    } else if (expr.op == MathExpr.Op.PARALLEL) {
                        "((${r(expr.left)}*${r(expr.right)})/(${r(expr.left)}+${r(expr.right)}))"
                    } else {
                        "(${r(expr.left)}${expr.op.syntax}${r(expr.right)})"
                    }
                }
                is MathExpr.Phasor -> "(${r(expr.magnitude)}*Exp(I*(${r(expr.angleDegrees)})*Pi/180))"
                is MathExpr.Builtin -> expr.function.syntax + expr.arguments.joinToString(",", "(", ")") { r(it) }
                is MathExpr.Call -> {
                    val args = expr.arguments.map { r(it) }
                    val definition = bindings.functions[expr.function]
                    if (definition == null) function(expr.function) + args.joinToString(",", "(", ")") else {
                        require(args.size == definition.params.size) { "${expr.function} expects ${definition.params.size} arguments" }
                        expand(SymbolKey(expr.function, SymbolKey.Kind.FUNCTION)) {
                            r(definition.body, definition.params.zip(args).toMap())
                        }
                    }
                }
                is MathExpr.Integral -> {
                    val v = symbol(expr.variable)
                    val body = r(expr.body, locals + (expr.variable to v))
                    if (expr.lower == null) "Integrate($body,$v)" else "Integrate($body,{$v,${r(expr.lower)},${r(requireNotNull(expr.upper))}})"
                }
                is MathExpr.Derivative -> {
                    val v = symbol(expr.variable)
                    val body = r(expr.body, locals + (expr.variable to v))
                    if (expr.order == 1) "D($body,$v)" else "D($body,{$v,${expr.order}})"
                }
                is MathExpr.Sum -> {
                    val v = symbol(expr.variable)
                    "Sum(${r(expr.body, locals + (expr.variable to v))},{$v,${r(expr.lower)},${r(expr.upper)}})"
                }
                is MathExpr.Limit -> {
                    val v = symbol(expr.variable)
                    "Limit(${r(expr.body, locals + (expr.variable to v))},$v->${r(expr.toward)})"
                }
                is MathExpr.Matrix -> expr.rows.joinToString(",", "{", "}") { row -> row.joinToString(",", "{", "}") { r(it) } }
                is MathExpr.LaplaceTransform -> {
                    val t = symbol(expr.timeVar)
                    val s = symbol(expr.freqVar)
                    "LaplaceTransform(${r(expr.expr, locals + (expr.timeVar to t))},$t,$s)"
                }
                is MathExpr.InverseLaplaceTransform -> {
                    val s = symbol(expr.freqVar)
                    val t = symbol(expr.timeVar)
                    "InverseLaplaceTransform(${r(expr.expr, locals + (expr.freqVar to s))},$s,$t)"
                }
                is MathExpr.ZTransform -> {
                    val n = symbol(expr.discreteVar)
                    val z = symbol(expr.zVar)
                    "ZTransform(${r(expr.expr, locals + (expr.discreteVar to n))},$n,$z)"
                }
                is MathExpr.Gradient -> {
                    val coordsList = expr.coords.joinToString(",", "{", "}") { symbol(it) }
                    "Grad(${r(expr.scalarField)},$coordsList)"
                }
                is MathExpr.Divergence -> {
                    val coordsList = expr.coords.joinToString(",", "{", "}") { symbol(it) }
                    "Div(${r(expr.vectorField)},$coordsList)"
                }
                is MathExpr.Curl -> {
                    val coordsList = expr.coords.joinToString(",", "{", "}") { symbol(it) }
                    "Curl(${r(expr.vectorField)},$coordsList)"
                }
                is MathExpr.TaylorSeries -> {
                    val v = symbol(expr.variable)
                    "Normal(Series(${r(expr.body, locals + (expr.variable to v))},{$v,${r(expr.center)},${expr.order}}))"
                }
                is MathExpr.Relational -> "(${r(expr.left)}${expr.op.syntax}${r(expr.right)})"
                is MathExpr.Otherwise -> "True"
                is MathExpr.Piecewise -> {
                    val branchList = expr.branches.joinToString(",", "{", "}") { "{${r(it.value)},${r(it.condition)}}" }
                    "Piecewise($branchList,${r(expr.defaultExpr)})"
                }
                is MathExpr.DiscreteSequence -> expr.elements.joinToString(",", "{", "}") { r(it) }
                is MathExpr.IndexedSequence -> "${symbol(expr.name)}[[${r(expr.index)}]]"
                is MathExpr.DiscreteConvolution -> {
                    val x = r(expr.left)
                    val h = r(expr.right)
                    "Table(Sum(If(n-k+1>=1 && n-k+1<=Length($h) && k>=1 && k<=Length($x), ($x)[[k]]*($h)[[n-k+1]], 0), {k, 1, Length($x)}), {n, 1, Length($x)+Length($h)-1})"
                }
                is MathExpr.CircularConvolution -> {
                    val x = r(expr.left)
                    val h = r(expr.right)
                    if (expr.length != null) {
                        val nLen = r(expr.length)
                        "Table(Sum(($x)[[Mod(k-1, $nLen)+1]] * ($h)[[Mod(n-k, $nLen)+1]], {k, 1, $nLen}), {n, 1, $nLen})"
                    } else {
                        "With({N=Max(Length($x), Length($h))}, Table(Sum(($x)[[Mod(k-1, N)+1]] * ($h)[[Mod(n-k, N)+1]], {k, 1, N}), {n, 1, N}))"
                    }
                }
                is MathExpr.DiscreteFourierTransform -> {
                    val x = r(expr.expr)
                    if (expr.inverse) {
                        "Table((1/Length($x)) * Sum(($x)[[k]] * Exp(I*2*Pi*(k-1)*(n-1)/Length($x)), {k, 1, Length($x)}), {n, 1, Length($x)})"
                    } else {
                        "Table(Sum(($x)[[n]] * Exp(-I*2*Pi*(k-1)*(n-1)/Length($x)), {n, 1, Length($x)}), {k, 1, Length($x)})"
                    }
                }
            }
        }
    }
}
