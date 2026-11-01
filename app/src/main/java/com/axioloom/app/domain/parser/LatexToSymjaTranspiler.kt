package com.axioloom.app.domain.parser

import com.axioloom.app.domain.model.*
import com.axioloom.app.domain.logic.KarnaughMinimizer

open class MathParseException(message: String) : IllegalArgumentException(message)

class AmbiguousSolveVariableException(val candidates: List<String>) :
    MathParseException("Select one solve variable; candidates: ${candidates.joinToString()}")

data class TranspiledMath(val cleanLatex: String, val intent: MathIntent) {
    val canonicalSymja: String get() = SymjaCompiler.compile(intent).command
}

/** A deliberately bounded math grammar, not a permissive TeX or Symja program interpreter. */
class LatexToSymjaTranspiler {
    private fun sopToMathExpr(sopLatex: String): MathExpr {
            val trimmed = sopLatex.trim()
            if (trimmed == "0" || trimmed == "1") return MathExpr.Number(trimmed)
            if (isIdentifier(trimmed)) return MathExpr.Symbol(trimmed)
            val terms = trimmed.split("+").map { it.trim() }.filter { it.isNotEmpty() }
            if (terms.isEmpty()) return MathExpr.Number("0")

            val factorRegex = SOP_FACTOR_REGEX
            val termExprs = terms.map { term ->
                val factors = factorRegex.findAll(term).map { match ->
                    val notVar = match.groups[1]?.value
                    val plainVar = match.groups[2]?.value
                    if (notVar != null && isIdentifier(notVar)) {
                        MathExpr.Call("Not", listOf(MathExpr.Symbol(notVar)))
                    } else if (plainVar != null && isIdentifier(plainVar)) {
                        MathExpr.Symbol(plainVar)
                    } else {
                        MathExpr.Number("1")
                    }
                }.toList()
                if (factors.isEmpty()) {
                    MathExpr.Number("1")
                } else {
                    factors.reduce { acc, factor -> MathExpr.Binary(MathExpr.Op.MULTIPLY, acc, factor) }
                }
            }
            return termExprs.reduce { acc, termExpr -> MathExpr.Binary(MathExpr.Op.ADD, acc, termExpr) }
        }
    fun transpile(latex: String, boundSymbols: Set<String> = emptySet(), solveTarget: String? = null): TranspiledMath {
        require(latex.length <= 8192) { "Recognition output exceeds 8192 characters" }
        require(solveTarget == null || isIdentifier(solveTarget)) { "Invalid solve target" }
        var clean = latex.trim()
        if (clean.startsWith("$$") && clean.endsWith("$$") && clean.length >= 4) clean = clean.substring(2, clean.length - 2)
        else if (clean.startsWith('$') && clean.endsWith('$') && clean.length >= 2) clean = clean.substring(1, clean.length - 1)
        val kmapRegex = KMAP_REGEX
        if (kmapRegex.containsMatchIn(clean)) {
            val kmapResult = KarnaughMinimizer.parseKmapCommand(clean)
            if (kmapResult != null) {
                return TranspiledMath(
                    cleanLatex = kmapResult.minimalSopLatex,
                    intent = MathIntent.EvaluationQuery(sopToMathExpr(kmapResult.minimalSopLatex), false)
                )
            }
        }
        clean = com.axioloom.app.domain.model.EngineeringConstants.expandSiSuffixes(clean)
        clean = clean.replace(TEX_FORMATTING_REGEX, "")
            .replace("\\{", "{")
            .replace("\\}", "}")
            .replace("\\ast", " \\ast ")
            .replace("\\star", " \\ast ")
            .replace(PUNCTUATION_SPACING_REGEX, " ")
            .replace(LE_REGEX, "<=")
            .replace(GE_REGEX, ">=")
            .replace(NEQ_REGEX, "!=")
            .replace(OTHERWISE_REGEX, "\\otherwise ")
            .replace(IF_REGEX, " ")
            .replace(PARALLEL_WORD_REGEX, " || ")
            .replace(PARALLEL_BARS_REGEX, " || ")
            .replace(PARTIAL_REGEX, "d")
            .replace(DELTA_DIFF_QUOTIENT_REGEX) { match ->
                "\\frac{d ${match.groupValues[1]}}{d ${match.groupValues[2]}}"
            }
            .replace(DELTA_DIFF_OPERATOR_REGEX) { match ->
                "\\frac{d}{d ${match.groupValues[1]}}"
            }
            .replace(PM_REGEX, "+")
            .replace(MP_REGEX, "-")
            .replace(EQUIV_REGEX, "==")
            .replace(PROPTO_REGEX, "==")
            .replace(LEFTARROW_REGEX, "->")
            .replace(IINT_REGEX, "\\int ")
            .replace(IIINT_REGEX, "\\int ")
            .replace(OINT_REGEX, "\\int ")
            .replace(FOURIER_REGEX, "\\fourier ")
            .replace(DEG_REGEX) { "((${it.groupValues[1]}) * 180 / \\pi)" }
            .replace(RAD_REGEX) { "((${it.groupValues[1]}) * \\pi / 180)" }
            .replace(FONT_WRAPPERS_REGEX) { it.groupValues[1] }
            .replace(MATHRM_D_REGEX, "d")
            .replace(ILAPLACE_REGEX) { "\\ilaplace " }
            .replace(IZTRANS_REGEX) { "\\iztrans " }
            .replace(LAPLACE_REGEX) { "\\laplace " }
            .replace(ZTRANS_REGEX) { "\\ztrans " }
            .replace(OPERATOR_NAME_REGEX) {
                "\\" + (if (it.groupValues[1] == "div") "divergence" else it.groupValues[1])
            }
            .trim()
        val tokens = Lexer(clean).lex().toMutableList()
        var approximate = false
        if (tokens.lastOrNull()?.kind == Kind.QUESTION) {
            tokens.removeAt(tokens.lastIndex)
            expect(tokens.lastOrNull()?.kind == Kind.EQUAL, "A question suffix must be =?")
            tokens.removeAt(tokens.lastIndex)
            approximate = true
        } else if (tokens.lastOrNull()?.kind == Kind.APPROX) {
            tokens.removeAt(tokens.lastIndex)
            approximate = true
        } else if (tokens.lastOrNull()?.kind == Kind.EQUAL) {
            tokens.removeAt(tokens.lastIndex)
        }
        expect(tokens.isNotEmpty(), "Empty mathematical expression")
        val isEquationSystem = tokens.first().kind == Kind.BEGIN && tokens.first().text == "cases" &&
            splitTop(tokens.subList(1, tokens.lastIndex), Kind.ROW).all { row ->
                val halves = splitTop(row, Kind.EQUAL)
                halves.size == 2 && halves[0].none { it.kind == Kind.AMP }
            }
        val intent = if (isEquationSystem) {
            expect(tokens.last().kind == Kind.END && tokens.last().text == "cases", "Unclosed cases environment")
            val rows = splitTop(tokens.subList(1, tokens.lastIndex), Kind.ROW)
            val equations = rows.filter { it.isNotEmpty() }.map { row ->
                val halves = splitTop(row, Kind.EQUAL)
                expect(halves.size == 2, "Every system row must contain one equation")
                MathEquation(parse(halves[0], boundSymbols), parse(halves[1], boundSymbols))
            }
            expect(equations.isNotEmpty(), "Empty equation system")
            val targets = if (solveTarget != null) listOf(solveTarget) else equations.flatMap {
                (it.lhs.references() + it.rhs.references()).filter { key -> key.kind == SymbolKey.Kind.VALUE && key.name !in boundSymbols }.map { key -> key.name }
            }.distinct().sorted()
            expect(targets.isNotEmpty(), "No unbound variables in equation system")
            MathIntent.SystemOfEq(equations, targets, approximate)
        } else {
            val halves = splitTop(tokens, Kind.EQUAL)
            expect(halves.size <= 2, "Only a definition followed by one terminal equals sign is supported")
            if (halves.size == 1) MathIntent.EvaluationQuery(parse(tokens, boundSymbols), approximate) else {
                expect(halves.all { it.isNotEmpty() }, "Missing side of equation")
                val rhs = parse(halves[1], boundSymbols)
                val signature = functionSignature(halves[0])
                if (signature != null && solveTarget == null) {
                    MathIntent.FunctionDef(signature.first, signature.second, rhs, approximate)
                } else {
                    val lhs = parse(halves[0], boundSymbols)
                    val odeDeriv = findOdeDerivative(lhs) ?: findOdeDerivative(rhs)
                    if (odeDeriv != null && odeDeriv.body is MathExpr.Symbol) {
                        val depVar = (odeDeriv.body as MathExpr.Symbol).name
                        val indepVar = odeDeriv.variable
                        MathIntent.OdeSolve(lhs, rhs, depVar, indepVar, approximate)
                    } else if (lhs is MathExpr.Symbol && SymbolKey(lhs.name) !in rhs.references() && solveTarget == null) {
                        MathIntent.Assignment(lhs.name, rhs, approximate)
                    } else {
                        val candidates = (lhs.references() + rhs.references()).filter {
                            it.kind == SymbolKey.Kind.VALUE && it.name !in boundSymbols
                        }.map { it.name }.distinct().sorted()
                        val target = if (solveTarget != null) {
                            solveTarget
                        } else if (candidates.size > 1) {
                            throw AmbiguousSolveVariableException(candidates)
                        } else {
                            candidates.singleOrNull()
                        }
                        expect(target != null, "Select one solve variable; candidates: ${candidates.joinToString()}")
                        MathIntent.EquationSolve(lhs, rhs, requireNotNull(target), approximate)
                    }
                }
            }
        }
        return TranspiledMath(clean, intent)
    }

    private fun functionSignature(tokens: List<Token>): Pair<String, List<String>>? {
        if (tokens.size < 4 || tokens.first().kind != Kind.NAME || tokens[1].kind != Kind.LP || tokens.last().kind != Kind.RP) return null
        val params = splitTop(tokens.subList(2, tokens.lastIndex), Kind.COMMA)
        if (params.any { it.size != 1 || it[0].kind != Kind.NAME }) return null
        val names = params.map { it[0].text }
        expect(names.distinct().size == names.size, "Duplicate function parameter")
        return tokens.first().text to names
    }

    private fun findOdeDerivative(expr: MathExpr): MathExpr.Derivative? = when (expr) {
        is MathExpr.Derivative -> expr
        is MathExpr.Unary -> findOdeDerivative(expr.value)
        is MathExpr.Binary -> findOdeDerivative(expr.left) ?: findOdeDerivative(expr.right)
        is MathExpr.Call -> expr.arguments.firstNotNullOfOrNull(::findOdeDerivative)
        is MathExpr.Builtin -> expr.arguments.firstNotNullOfOrNull(::findOdeDerivative)
        else -> null
    }

    private enum class Kind { NUMBER, NAME, COMMAND, PLUS, MINUS, STAR, CROSS, NABLA, SLASH, POWER, UNDER, LP, RP, LB, RB, LS, RS, COMMA, EQUAL, APPROX, QUESTION, ARROW, AMP, ROW, BEGIN, END, BAR, BANG, PRIME, ANGLE, LT, LE, GT, GE, EQEQ, NEQ, OTHERWISE, PARALLEL, CONV }
    private data class Token(val kind: Kind, val text: String, val offset: Int)

    private class Lexer(val text: String) {
        var at = 0
        val tokens = mutableListOf<Token>()
        fun lex(): List<Token> {
            while (at < text.length) {
                val start = at
                val c = text[at++]
                when {
                    c.isWhitespace() -> Unit
                    c in '0'..'9' || (c == '.' && at < text.length && text[at].isDigit()) -> {
                        while (at < text.length && text[at].isDigit()) at++
                        if (c != '.' && at < text.length && text[at] == '.') { at++; while (at < text.length && text[at].isDigit()) at++ }
                        if (at < text.length && text[at] in "eE") {
                            var end = at + 1
                            if (end < text.length && text[end] in "+-") end++
                            val digits = end
                            while (end < text.length && text[end].isDigit()) end++
                            if (end > digits) at = end
                        }
                        tokens += Token(Kind.NUMBER, text.substring(start, at), start)
                    }
                    c in 'a'..'z' || c in 'A'..'Z' -> {
                        var end = at
                        while (end < text.length && (text[end] in 'a'..'z' || text[end] in 'A'..'Z')) end++
                        val word = text.substring(start, end)
                        if (word == "otherwise") { at = end; tokens += Token(Kind.OTHERWISE, word, start) }
                        else if (word in functions || word.equals("Zc", ignoreCase = true) || word.equals("Zl", ignoreCase = true) || word in setOf("taylor", "series", "conv", "cconv", "dft", "idft", "laplace", "ilaplace", "ztrans", "iztrans", "vec", "mathbf", "boldsymbol", "mathrm", "text")) { at = end; tokens += Token(Kind.COMMAND, word, start) }
                        else if (end < text.length && text[end] == '_') {
                            at = end
                            tokens += Token(Kind.NAME, word, start)
                        } else tokens += Token(Kind.NAME, c.toString(), start)
                    }
                    c == '\\' -> {
                        if (at < text.length && text[at] == '\\') { at++; tokens += Token(Kind.ROW, "\\\\", start) }
                        else {
                            while (at < text.length && text[at].isLetter()) at++
                            val command = text.substring(start + 1, at)
                            when (command) {
                                "begin", "end" -> {
                                    while (at < text.length && text[at].isWhitespace()) at++
                                    expect(at < text.length && text[at++] == '{', "Missing environment name at $start")
                                    val envStart = at
                                    while (at < text.length && text[at] != '}') at++
                                    expect(at < text.length, "Unclosed environment name")
                                    val environment = text.substring(envStart, at++)
                                    expect(environment in setOf("matrix", "pmatrix", "bmatrix", "vmatrix", "cases"), "Unsupported environment: $environment")
                                    tokens += Token(if (command == "begin") Kind.BEGIN else Kind.END, environment, start)
                                }
                                "times" -> tokens += Token(Kind.CROSS, "*", start)
                                "cdot" -> tokens += Token(Kind.STAR, "*", start)
                                "ast", "star" -> tokens += Token(Kind.CONV, "\\ast", start)
                                "nabla" -> tokens += Token(Kind.NABLA, "\\nabla", start)
                                "div" -> tokens += Token(Kind.SLASH, "/", start)
                                "approx" -> tokens += Token(Kind.APPROX, command, start)
                                "to", "rightarrow" -> tokens += Token(Kind.ARROW, "->", start)
                                "angle" -> tokens += Token(Kind.ANGLE, "\\angle", start)
                                "otherwise" -> tokens += Token(Kind.OTHERWISE, "otherwise", start)
                                "le", "leq" -> tokens += Token(Kind.LE, "<=", start)
                                "ge", "geq" -> tokens += Token(Kind.GE, ">=", start)
                                "neq" -> tokens += Token(Kind.NEQ, "!=", start)
                                "Re", "Im" -> tokens += Token(Kind.COMMAND, command, start)
                                "frac", "dfrac", "tfrac", "sqrt", "int", "sum", "lim", "pi", "infty", "imaginaryi", "imath" -> tokens += Token(Kind.COMMAND, command, start)
                                "laplace", "ilaplace", "ztrans", "iztrans", "grad", "curl", "divergence", "unitstep", "step", "diracdelta", "dirac", "taylor", "series", "conv", "cconv", "dft", "idft", "linreg", "polyreg", "mean", "stdev", "vec", "mathbf", "boldsymbol", "mathrm", "text", "prod", "fourier", "zc", "zl", "Zc", "Zl" -> tokens += Token(Kind.COMMAND, command, start)
                                in functions -> tokens += Token(Kind.COMMAND, command, start)
                                in setOf("alpha", "beta", "gamma", "delta", "epsilon", "varepsilon", "zeta", "eta", "theta", "iota", "kappa", "lambda", "mu", "nu", "xi", "rho", "sigma", "tau", "upsilon", "phi", "varphi", "chi", "psi", "omega", "Delta", "Gamma", "Theta", "Lambda", "Xi", "Pi", "Sigma", "Phi", "Psi", "Omega", "hbar") -> tokens += Token(Kind.NAME, command, start)
                                else -> throw MathParseException("Unsupported LaTeX command \\$command at $start")
                            }
                        }
                    }
                    else -> {
                        val kind = when (c) {
                            '+' -> Kind.PLUS
                            '-', '\u2212' -> Kind.MINUS
                            '*', '\u00B7' -> Kind.STAR
                            '\u00D7' -> Kind.CROSS
                            '\u2207' -> Kind.NABLA
                            '/', '\u00F7' -> Kind.SLASH
                            '^' -> Kind.POWER
                            '_' -> Kind.UNDER
                            '(' -> Kind.LP
                            ')' -> Kind.RP
                            '{' -> Kind.LB
                            '}' -> Kind.RB
                            '[' -> Kind.LS
                            ']' -> Kind.RS
                            ',' -> Kind.COMMA
                            '=' -> if (at < text.length && text[at] == '=') { at++; Kind.EQEQ } else Kind.EQUAL
                            '<' -> if (at < text.length && text[at] == '=') { at++; Kind.LE } else Kind.LT
                            '>' -> if (at < text.length && text[at] == '=') { at++; Kind.GE } else Kind.GT
                            '!' -> if (at < text.length && text[at] == '=') { at++; Kind.NEQ } else Kind.BANG
                            '\u2264' -> Kind.LE
                            '\u2265' -> Kind.GE
                            '\u2260' -> Kind.NEQ
                            '?' -> Kind.QUESTION
                            '&' -> Kind.AMP
                            '|' -> if (at < text.length && text[at] == '|') { at++; Kind.PARALLEL } else Kind.BAR
                            '\'' -> Kind.PRIME
                            '\u2220' -> Kind.ANGLE
                            '\u2248' -> Kind.APPROX
                            '\u03C0', '\u221E' -> Kind.COMMAND
                            else -> throw MathParseException("Unsupported character '$c' at $start")
                        }
                        val tokenText = when (c) {
                            '\u03C0' -> "pi"
                            '\u221E' -> "infty"
                            '\u2207' -> "\\nabla"
                            '\u00D7' -> "*"
                            '\u2264' -> "<="
                            '\u2265' -> ">="
                            '\u2260' -> "!="
                            else -> if (kind == Kind.PARALLEL) "||" else if (kind == Kind.LE) "<=" else if (kind == Kind.GE) ">=" else if (kind == Kind.NEQ) "!=" else if (kind == Kind.EQEQ) "==" else c.toString()
                        }
                        tokens += Token(kind, tokenText, start)
                    }
                }
                expect(tokens.size <= 4096, "Too many math tokens")
            }
            var depth = 0
            tokens.forEach {
                if (it.kind in opens) depth++
                if (it.kind in closes) depth--
                expect(depth in 0..96, "Unbalanced or excessively nested expression near ${it.offset}")
            }
            expect(depth == 0, "Unclosed expression delimiter")
            return tokens
        }
    }

    private class Parser(
        val tokens: List<Token>,
        val boundSymbols: Set<String> = emptySet(),
        val localScope: MutableSet<String> = mutableSetOf()
    ) {
        var at = 0
        var nesting = 0
        fun peek(): Token? = tokens.getOrNull(at)
        fun take(): Token = tokens.getOrNull(at++) ?: throw MathParseException("Unexpected end of expression")
        fun accept(kind: Kind): Boolean = if (peek()?.kind == kind) { at++; true } else false
        fun need(kind: Kind) { expect(accept(kind), "Expected $kind near ${peek()?.offset ?: "end"}") }
        fun all(): MathExpr {
            expect(tokens.isNotEmpty(), "Empty expression group")
            val expression = expression()
            expect(at == tokens.size, "Unexpected token ${peek()?.text} near ${peek()?.offset}")
            return expression
        }
        fun expression(minimum: Int = 0): MathExpr {
            expect(++nesting <= 128, "Expression nesting limit exceeded")
            try {
                var left = prefix()
                while (true) {
                    if (accept(Kind.BANG)) {
                        left = MathExpr.Builtin(MathExpr.Function.FACTORIAL, listOf(left))
                        continue
                    }
                    val next = peek()?.kind ?: break
                    val implicit = next in starts
                    val precedence = when (next) {
                        Kind.LT, Kind.LE, Kind.GT, Kind.GE, Kind.EQEQ, Kind.NEQ -> 5
                        Kind.PLUS, Kind.MINUS -> 10
                        Kind.STAR, Kind.CROSS, Kind.SLASH -> 20
                        Kind.CONV -> 21
                        Kind.PARALLEL -> 22
                        Kind.ANGLE -> 25
                        Kind.POWER -> 40
                        else -> if (implicit) 20 else -1
                    }
                    if (precedence < minimum) break
                    if (next in setOf(Kind.LT, Kind.LE, Kind.GT, Kind.GE, Kind.EQEQ, Kind.NEQ)) {
                        val relOp = when (take().kind) {
                            Kind.LT -> MathExpr.RelOp.LT
                            Kind.LE -> MathExpr.RelOp.LE
                            Kind.GT -> MathExpr.RelOp.GT
                            Kind.GE -> MathExpr.RelOp.GE
                            Kind.EQEQ -> MathExpr.RelOp.EQ
                            Kind.NEQ -> MathExpr.RelOp.NE
                            else -> throw MathParseException("Invalid relational operator")
                        }
                        val right = expression(6)
                        left = MathExpr.Relational(relOp, left, right)
                        continue
                    }
                    if (next == Kind.CONV) {
                        take()
                        val right = expression(22)
                        left = MathExpr.DiscreteConvolution(left, right)
                        continue
                    }
                    if (next == Kind.PARALLEL) {
                        take()
                        val right = expression(23)
                        left = MathExpr.Binary(MathExpr.Op.PARALLEL, left, right)
                        continue
                    }
                    if (next == Kind.ANGLE) {
                        take()
                        val right = expression(26)
                        left = MathExpr.Phasor(left, right)
                        continue
                    }
                    val op = if (implicit) MathExpr.Op.MULTIPLY else when (take().kind) {
                        Kind.PLUS -> MathExpr.Op.ADD; Kind.MINUS -> MathExpr.Op.SUBTRACT
                        Kind.STAR, Kind.CROSS -> MathExpr.Op.MULTIPLY
                        Kind.SLASH -> MathExpr.Op.DIVIDE; Kind.POWER -> MathExpr.Op.POWER
                        else -> throw MathParseException("Invalid binary operator")
                    }
                    val right = expression(if (op == MathExpr.Op.POWER) precedence else precedence + 1)
                    left = MathExpr.Binary(op, left, right)
                }
                return left
            } finally { nesting-- }
        }
        fun prefix(): MathExpr {
            val token = take()
            return when (token.kind) {
                Kind.NUMBER -> MathExpr.Number(token.text)
                Kind.NAME -> {
                    val name = identifierTail(token.text)
                    var primes = 0
                    while (accept(Kind.PRIME)) {
                        primes++
                    }
                    when {
                        primes > 0 -> {
                            val indep = if (accept(Kind.LP)) {
                                val arg = expression()
                                need(Kind.RP)
                                if (arg is MathExpr.Symbol) arg.name else "x"
                            } else "x"
                            MathExpr.Derivative(MathExpr.Symbol(name), indep, primes)
                        }
                        accept(Kind.LP) || accept(Kind.LB) -> {
                            val closing = if (tokens[at - 1].kind == Kind.LB) Kind.RB else Kind.RP
                            when (name) {
                                "u", "step", "unitstep" -> MathExpr.Builtin(MathExpr.Function.UNIT_STEP, arguments(closing))
                                "delta", "dirac", "diracdelta" -> MathExpr.Builtin(MathExpr.Function.DIRAC_DELTA, arguments(closing))
                                "grad" -> {
                                    val args = arguments(closing)
                                    val target = if (args.size > 1) MathExpr.Matrix(listOf(args)) else args.first()
                                    MathExpr.Gradient(target, listOf("x", "y", "z"))
                                }
                                "div", "divergence" -> {
                                    val args = arguments(closing)
                                    val target = if (args.size > 1) MathExpr.Matrix(listOf(args)) else args.first()
                                    MathExpr.Divergence(target, listOf("x", "y", "z"))
                                }
                                "curl" -> {
                                    val args = arguments(closing)
                                    val target = if (args.size > 1) MathExpr.Matrix(listOf(args)) else args.first()
                                    MathExpr.Curl(target, listOf("x", "y", "z"))
                                }
                                "laplace" -> {
                                    val args = arguments(closing)
                                    val tVar = if (args.size >= 3 && args[1] is MathExpr.Symbol) (args[1] as MathExpr.Symbol).name else "t"
                                    val sVar = if (args.size >= 3 && args[2] is MathExpr.Symbol) (args[2] as MathExpr.Symbol).name else "s"
                                    MathExpr.LaplaceTransform(args.first(), tVar, sVar)
                                }
                                "ilaplace" -> {
                                    val args = arguments(closing)
                                    val sVar = if (args.size >= 3 && args[1] is MathExpr.Symbol) (args[1] as MathExpr.Symbol).name else "s"
                                    val tVar = if (args.size >= 3 && args[2] is MathExpr.Symbol) (args[2] as MathExpr.Symbol).name else "t"
                                    MathExpr.InverseLaplaceTransform(args.first(), sVar, tVar)
                                }
                                "ztrans" -> {
                                    val args = arguments(closing)
                                    val nVar = if (args.size >= 3 && args[1] is MathExpr.Symbol) (args[1] as MathExpr.Symbol).name else "n"
                                    val zVar = if (args.size >= 3 && args[2] is MathExpr.Symbol) (args[2] as MathExpr.Symbol).name else "z"
                                    MathExpr.ZTransform(args.first(), nVar, zVar)
                                }
                                "iztrans" -> {
                                    val args = arguments(closing)
                                    val zVar = if (args.size >= 3 && args[1] is MathExpr.Symbol) (args[1] as MathExpr.Symbol).name else "z"
                                    val nVar = if (args.size >= 3 && args[2] is MathExpr.Symbol) (args[2] as MathExpr.Symbol).name else "n"
                                    MathExpr.InverseZTransform(args.first(), zVar, nVar)
                                }
                                "taylor", "series" -> {
                                    at--
                                    parseTaylorSeries()
                                }
                                "zc", "Zc", "ZC" -> {
                                    val args = arguments(closing)
                                    expect(args.size == 2, "Zc requires (C, s)")
                                    MathExpr.Binary(MathExpr.Op.DIVIDE, MathExpr.Number("1"), MathExpr.Binary(MathExpr.Op.MULTIPLY, args[1], args[0]))
                                }
                                "zl", "Zl", "ZL" -> {
                                    val args = arguments(closing)
                                    expect(args.size == 2, "Zl requires (L, s)")
                                    MathExpr.Binary(MathExpr.Op.MULTIPLY, args[1], args[0])
                                }
                                else -> MathExpr.Call(name, arguments(closing))
                            }
                        }
                        accept(Kind.LS) -> {
                            val idx = expression()
                            need(Kind.RS)
                            MathExpr.IndexedSequence(name, idx)
                        }
                        name == "e" && name !in boundSymbols && name !in localScope -> MathExpr.Constant.E
                        name == "i" && name !in boundSymbols && name !in localScope -> MathExpr.Constant.I
                        else -> MathExpr.Symbol(name)
                    }
                }
                Kind.OTHERWISE -> MathExpr.Otherwise
                Kind.NABLA -> {
                    if (accept(Kind.STAR)) {
                        val field = expression(25)
                        MathExpr.Divergence(field, listOf("x", "y", "z"))
                    } else if (accept(Kind.CROSS)) {
                        val field = expression(25)
                        MathExpr.Curl(field, listOf("x", "y", "z"))
                    } else {
                        val field = expression(25)
                        MathExpr.Gradient(field, listOf("x", "y", "z"))
                    }
                }
                Kind.PLUS -> MathExpr.Unary(false, expression(30))
                Kind.MINUS -> MathExpr.Unary(true, expression(30))
                Kind.LP -> expression().also { need(Kind.RP) }
                Kind.LB -> {
                    if (accept(Kind.RB)) {
                        MathExpr.DiscreteSequence(emptyList())
                    } else {
                        val first = expression()
                        if (accept(Kind.COMMA)) {
                            val elements = mutableListOf(first)
                            do {
                                elements += expression()
                            } while (accept(Kind.COMMA))
                            need(Kind.RB)
                            MathExpr.DiscreteSequence(elements)
                        } else {
                            need(Kind.RB)
                            first
                        }
                    }
                }
                Kind.LS -> {
                    if (accept(Kind.RS)) {
                        MathExpr.DiscreteSequence(emptyList())
                    } else {
                        val elements = mutableListOf<MathExpr>()
                        do {
                            elements += expression()
                        } while (accept(Kind.COMMA))
                        need(Kind.RS)
                        MathExpr.DiscreteSequence(elements)
                    }
                }
                Kind.BAR -> {
                    val start = at
                    var end = at
                    var depth = 0
                    while (end < tokens.size) {
                        val k = tokens[end].kind
                        if (k == Kind.BAR && depth == 0) break
                        if (k in opens) depth++
                        if (k in closes) depth--
                        end++
                    }
                    expect(end < tokens.size, "Unclosed absolute value")
                    at = end + 1
                    MathExpr.Builtin(MathExpr.Function.ABS, listOf(parse(tokens.subList(start, end), boundSymbols, localScope)))
                }
                Kind.BEGIN -> matrix(token.text)
                Kind.COMMAND -> command(token.text)
                else -> throw MathParseException("Expected expression, got '${token.text}' at ${token.offset}")
            }
        }
        fun identifierTail(base: String): String {
            if (!accept(Kind.UNDER)) return base
            val suffix = if (peek()?.kind == Kind.LB) group().joinToString("") { it.text } else take().text
            expect(suffix.matches(SUBSCRIPT_IDENTIFIER_REGEX), "A subscript identifier must be alphanumeric")
            return "${base}_$suffix"
        }
        fun identifier(): String {
            val token = take()
            expect(token.kind == Kind.NAME, "Expected a one-letter variable")
            return identifierTail(token.text)
        }
        fun group(): List<Token> {
            val open = take().kind
            val close = when (open) { Kind.LB -> Kind.RB; Kind.LP -> Kind.RP; Kind.LS -> Kind.RS; else -> throw MathParseException("Expected a delimited group") }
            val start = at
            var depth = 1
            while (at < tokens.size) {
                val k = take().kind
                if (k == open) depth++
                if (k == close) {
                    depth--
                    if (depth == 0) return tokens.subList(start, at - 1)
                }
            }
            throw MathParseException("Unclosed math group")
        }
        fun arguments(end: Kind): List<MathExpr> {
            val args = mutableListOf<MathExpr>()
            expect(peek()?.kind != end, "Empty function argument list")
            do { args += expression() } while (accept(Kind.COMMA))
            need(end)
            return args
        }
        fun script(): List<Token> = if (peek()?.kind == Kind.LB) group() else listOf(take())
        fun scripts(): Pair<List<Token>?, List<Token>?> {
            var lower: List<Token>? = null
            var upper: List<Token>? = null
            repeat(2) {
                when {
                    accept(Kind.UNDER) -> { expect(lower == null, "Duplicate lower script"); lower = script() }
                    accept(Kind.POWER) -> { expect(upper == null, "Duplicate upper script"); upper = script() }
                }
            }
            return lower to upper
        }
        fun body(): MathExpr = if (peek()?.kind == Kind.LP || peek()?.kind == Kind.LB) prefix() else expression()
        fun parseTaylorSeries(): MathExpr {
            if (!accept(Kind.LP)) return MathExpr.TaylorSeries(prefix(), "x", MathExpr.Number("0"), 5)
            val args = arguments(Kind.RP)
            val body = args.getOrElse(0) { MathExpr.Number("0") }
            val variable = if (args.size >= 2 && args[1] is MathExpr.Symbol) (args[1] as MathExpr.Symbol).name else "x"
            val center = if (args.size >= 3) args[2] else MathExpr.Number("0")
            val order = if (args.size >= 4 && args[3] is MathExpr.Number) (args[3] as MathExpr.Number).value.toIntOrNull() ?: 5
                        else if (args.size == 3 && args[2] is MathExpr.Number) (args[2] as MathExpr.Number).value.toIntOrNull() ?: 5
                        else 5
            return MathExpr.TaylorSeries(body, variable, center, order)
        }
        fun command(name: String): MathExpr = when (name) {
            "pi" -> MathExpr.Constant.PI
            "infty" -> MathExpr.Constant.INFINITY
            "imaginaryi", "imath" -> MathExpr.Constant.I
            "taylor", "series" -> parseTaylorSeries()
            "frac", "dfrac", "tfrac" -> {
                val numerator = group()
                val denominator = group()
                val differential = Parser(denominator, boundSymbols, localScope)
                val isD = numerator.isNotEmpty() && numerator[0].kind == Kind.NAME && numerator[0].text == "d"
                var order = 1
                var inlineBodyTokens: List<Token>? = null

                if (isD) {
                    if (numerator.size == 1) {
                        order = 1
                    } else if (numerator.size >= 3 && numerator[1].kind == Kind.POWER) {
                        val ordExpr = Parser(listOf(numerator[2]), boundSymbols, localScope).expression()
                        if (ordExpr is MathExpr.Number) {
                            order = ordExpr.value.toIntOrNull() ?: 1
                        }
                        if (numerator.size > 3) {
                            inlineBodyTokens = numerator.subList(3, numerator.size)
                        }
                    } else if (numerator.size == 2 && numerator[1].kind == Kind.NUMBER) {
                        order = numerator[1].text.toIntOrNull() ?: 1
                    } else if (numerator.size >= 2) {
                        order = 1
                        inlineBodyTokens = numerator.subList(1, numerator.size)
                    }
                }
                if (isD && denominator.firstOrNull()?.let { it.kind == Kind.NAME && it.text == "d" } == true && denominator.size >= 2) {
                    differential.at = 1
                    val variable = differential.identifier()
                    if (differential.accept(Kind.POWER)) {
                        if (differential.peek()?.kind == Kind.LB) differential.group() else differential.take()
                    }
                    expect(differential.at == denominator.size, "Only derivatives d/dx or d^n/dx^n are supported")
                    val derivBody = if (inlineBodyTokens != null && inlineBodyTokens.isNotEmpty()) {
                        parse(inlineBodyTokens, boundSymbols, localScope)
                    } else {
                        body()
                    }
                    MathExpr.Derivative(derivBody, variable, order)
                } else MathExpr.Binary(MathExpr.Op.DIVIDE, parse(numerator, boundSymbols, localScope), parse(denominator, boundSymbols, localScope))
            }
            "sqrt" -> {
                val root = if (peek()?.kind == Kind.LS) parse(group(), boundSymbols, localScope) else null
                val value = parse(group(), boundSymbols, localScope)
                if (root == null) MathExpr.Builtin(MathExpr.Function.SQRT, listOf(value)) else MathExpr.Binary(MathExpr.Op.POWER, value, MathExpr.Binary(MathExpr.Op.DIVIDE, MathExpr.Number("1"), root))
            }
            "int" -> {
                val (lower, upper) = scripts()
                expect((lower == null) == (upper == null), "An integral needs both bounds or neither")
                val start = at
                var depth = 0
                var nestedIntegrals = 0
                var differentialAt = -1
                var scan = at
                while (scan + 1 < tokens.size) {
                    val t = tokens[scan]
                    if (t.kind in closes && depth == 0) break
                    if (depth == 0 && t.kind == Kind.COMMAND && t.text == "int") nestedIntegrals++
                    if (depth == 0 && t.kind == Kind.NAME && t.text == "d" && tokens[scan + 1].kind == Kind.NAME) {
                        if (nestedIntegrals == 0) { differentialAt = scan; break } else { nestedIntegrals--; scan++ }
                    }
                    if (t.kind in opens) depth++
                    if (t.kind in closes) depth--
                    scan++
                }
                expect(differentialAt >= 0, "Integral must end with a differential such as d x")
                val integrand = parse(tokens.subList(start, differentialAt), boundSymbols, localScope)
                at = differentialAt + 1
                val variable = identifier()
                MathExpr.Integral(integrand, variable, lower?.let { parse(it, boundSymbols, localScope) }, upper?.let { parse(it, boundSymbols, localScope) })
            }
            "sum", "prod" -> {
                val (lower, upper) = scripts()
                expect(lower != null && upper != null, "$name needs lower and upper bounds")
                val parts = splitTop(requireNotNull(lower), Kind.EQUAL)
                expect(parts.size == 2, "$name lower bound must be i=a")
                val variableParser = Parser(parts[0], boundSymbols, localScope)
                val variable = variableParser.identifier()
                expect(variableParser.at == parts[0].size, "Invalid $name variable")
                localScope.add(variable)
                val sBody = body()
                localScope.remove(variable)
                if (name == "prod") {
                    MathExpr.Call("Product", listOf(sBody, MathExpr.Symbol(variable), parse(parts[1], boundSymbols, localScope), parse(requireNotNull(upper), boundSymbols, localScope)))
                } else {
                    MathExpr.Sum(sBody, variable, parse(parts[1], boundSymbols, localScope), parse(requireNotNull(upper), boundSymbols, localScope))
                }
            }
            "zc", "zl" -> {
                val closing = if (peek()?.kind == Kind.LB) { take(); Kind.RB } else { need(Kind.LP); Kind.RP }
                val args = arguments(closing)
                expect(args.size == 2, "$name requires 2 arguments")
                if (name == "zc") MathExpr.Binary(MathExpr.Op.DIVIDE, MathExpr.Number("1"), MathExpr.Binary(MathExpr.Op.MULTIPLY, args[1], args[0]))
                else MathExpr.Binary(MathExpr.Op.MULTIPLY, args[1], args[0])
            }
            "fourier" -> {
                val arg = if (peek()?.kind in setOf(Kind.LP, Kind.LB)) {
                    val closing = if (take().kind == Kind.LB) Kind.RB else Kind.RP
                    arguments(closing).first()
                } else expression(21)
                MathExpr.Call("FourierTransform", listOf(arg, MathExpr.Symbol("t"), MathExpr.Symbol("w")))
            }
            "lim" -> {
                need(Kind.UNDER)
                val parts = splitTop(script(), Kind.ARROW)
                expect(parts.size == 2, "Limit must be written with x \\to a")
                val variableParser = Parser(parts[0], boundSymbols, localScope)
                val variable = variableParser.identifier()
                expect(variableParser.at == parts[0].size, "Invalid limit variable")
                MathExpr.Limit(body(), variable, parse(parts[1], boundSymbols, localScope))
            }
            "vec", "mathbf", "boldsymbol", "mathrm", "text" -> {
                val argTokens = if (peek()?.kind == Kind.LB) group() else listOf(take())
                parse(argTokens, boundSymbols, localScope)
            }
            "laplace" -> {
                if (peek()?.kind in setOf(Kind.LP, Kind.LB)) {
                    val closing = if (take().kind == Kind.LB) Kind.RB else Kind.RP
                    val args = arguments(closing)
                    val tVar = if (args.size >= 3 && args[1] is MathExpr.Symbol) (args[1] as MathExpr.Symbol).name else "t"
                    val sVar = if (args.size >= 3 && args[2] is MathExpr.Symbol) (args[2] as MathExpr.Symbol).name else "s"
                    MathExpr.LaplaceTransform(args.first(), tVar, sVar)
                } else MathExpr.LaplaceTransform(if (peek()?.kind == Kind.LP || peek()?.kind == Kind.LB) prefix() else expression(21), "t", "s")
            }
            "ilaplace" -> {
                if (peek()?.kind in setOf(Kind.LP, Kind.LB)) {
                    val closing = if (take().kind == Kind.LB) Kind.RB else Kind.RP
                    val args = arguments(closing)
                    val sVar = if (args.size >= 3 && args[1] is MathExpr.Symbol) (args[1] as MathExpr.Symbol).name else "s"
                    val tVar = if (args.size >= 3 && args[2] is MathExpr.Symbol) (args[2] as MathExpr.Symbol).name else "t"
                    MathExpr.InverseLaplaceTransform(args.first(), sVar, tVar)
                } else MathExpr.InverseLaplaceTransform(if (peek()?.kind == Kind.LP || peek()?.kind == Kind.LB) prefix() else expression(21), "s", "t")
            }
            "ztrans" -> {
                if (peek()?.kind in setOf(Kind.LP, Kind.LB)) {
                    val closing = if (take().kind == Kind.LB) Kind.RB else Kind.RP
                    val args = arguments(closing)
                    val nVar = if (args.size >= 3 && args[1] is MathExpr.Symbol) (args[1] as MathExpr.Symbol).name else "n"
                    val zVar = if (args.size >= 3 && args[2] is MathExpr.Symbol) (args[2] as MathExpr.Symbol).name else "z"
                    MathExpr.ZTransform(args.first(), nVar, zVar)
                } else MathExpr.ZTransform(if (peek()?.kind == Kind.LP || peek()?.kind == Kind.LB) prefix() else expression(21), "n", "z")
            }
            "iztrans" -> {
                if (peek()?.kind in setOf(Kind.LP, Kind.LB)) {
                    val closing = if (take().kind == Kind.LB) Kind.RB else Kind.RP
                    val args = arguments(closing)
                    val zVar = if (args.size >= 3 && args[1] is MathExpr.Symbol) (args[1] as MathExpr.Symbol).name else "z"
                    val nVar = if (args.size >= 3 && args[2] is MathExpr.Symbol) (args[2] as MathExpr.Symbol).name else "n"
                    MathExpr.InverseZTransform(args.first(), zVar, nVar)
                } else MathExpr.InverseZTransform(if (peek()?.kind == Kind.LP || peek()?.kind == Kind.LB) prefix() else expression(21), "z", "n")
            }
            "grad" -> {
                if (peek()?.kind in setOf(Kind.LP, Kind.LB)) {
                    val closing = if (take().kind == Kind.LB) Kind.RB else Kind.RP
                    val args = arguments(closing)
                    val target = if (args.size > 1) MathExpr.Matrix(listOf(args)) else args.first()
                    MathExpr.Gradient(target, listOf("x", "y", "z"))
                } else MathExpr.Gradient(if (peek()?.kind == Kind.LP || peek()?.kind == Kind.LB) prefix() else expression(21), listOf("x", "y", "z"))
            }
            "divergence" -> {
                if (peek()?.kind in setOf(Kind.LP, Kind.LB)) {
                    val closing = if (take().kind == Kind.LB) Kind.RB else Kind.RP
                    val args = arguments(closing)
                    val target = if (args.size > 1) MathExpr.Matrix(listOf(args)) else args.first()
                    MathExpr.Divergence(target, listOf("x", "y", "z"))
                } else MathExpr.Divergence(if (peek()?.kind == Kind.LP || peek()?.kind == Kind.LB) prefix() else expression(21), listOf("x", "y", "z"))
            }
            "curl" -> {
                if (peek()?.kind in setOf(Kind.LP, Kind.LB)) {
                    val closing = if (take().kind == Kind.LB) Kind.RB else Kind.RP
                    val args = arguments(closing)
                    val target = if (args.size > 1) MathExpr.Matrix(listOf(args)) else args.first()
                    MathExpr.Curl(target, listOf("x", "y", "z"))
                } else MathExpr.Curl(if (peek()?.kind == Kind.LP || peek()?.kind == Kind.LB) prefix() else expression(21), listOf("x", "y", "z"))
            }
            "unitstep", "step" -> MathExpr.Builtin(MathExpr.Function.UNIT_STEP, listOf(if (peek()?.kind == Kind.LP || peek()?.kind == Kind.LB) prefix() else expression(21)))
            "diracdelta", "dirac", "delta" -> MathExpr.Builtin(MathExpr.Function.DIRAC_DELTA, listOf(if (peek()?.kind == Kind.LP || peek()?.kind == Kind.LB) prefix() else expression(21)))
            "theta" -> {
                if (peek()?.kind == Kind.LP || peek()?.kind == Kind.LB) {
                    MathExpr.Builtin(MathExpr.Function.UNIT_STEP, listOf(prefix()))
                } else {
                    MathExpr.Symbol("theta")
                }
            }
            "conv" -> {
                need(Kind.LP)
                val args = arguments(Kind.RP)
                expect(args.size == 2, "conv(x, h) requires 2 arguments")
                MathExpr.DiscreteConvolution(args[0], args[1])
            }
            "cconv" -> {
                need(Kind.LP)
                val args = arguments(Kind.RP)
                expect(args.size in 2..3, "cconv(x, h, [N]) requires 2 or 3 arguments")
                MathExpr.CircularConvolution(args[0], args[1], args.getOrNull(2))
            }
            "dft" -> {
                val arg = if (peek()?.kind == Kind.LP || peek()?.kind == Kind.LB) prefix() else expression(21)
                MathExpr.DiscreteFourierTransform(arg, inverse = false)
            }
            "idft" -> {
                val arg = if (peek()?.kind == Kind.LP || peek()?.kind == Kind.LB) prefix() else expression(21)
                MathExpr.DiscreteFourierTransform(arg, inverse = true)
            }
            "zc", "Zc", "ZC" -> {
                need(Kind.LP)
                val args = arguments(Kind.RP)
                expect(args.size == 2, "Zc requires (C, s)")
                MathExpr.Binary(MathExpr.Op.DIVIDE, MathExpr.Number("1"), MathExpr.Binary(MathExpr.Op.MULTIPLY, args[1], args[0]))
            }
            "zl", "Zl", "ZL" -> {
                need(Kind.LP)
                val args = arguments(Kind.RP)
                expect(args.size == 2, "Zl requires (L, s)")
                MathExpr.Binary(MathExpr.Op.MULTIPLY, args[1], args[0])
            }
            "mean", "stdev", "linreg", "polyreg" -> {
                need(Kind.LP)
                val args = arguments(Kind.RP)
                MathExpr.Call(name, args)
            }
            else -> {
                val function = functions[name] ?: throw MathParseException("Unsupported function $name")
                var powerExpr: MathExpr? = null
                if (accept(Kind.POWER)) {
                    val pToks = if (peek()?.kind == Kind.LB) group() else listOf(take())
                    powerExpr = parse(pToks, boundSymbols, localScope)
                }
                val argument = if (peek()?.kind == Kind.LP || peek()?.kind == Kind.LB) prefix() else expression(21)
                if (powerExpr != null) {
                    val isNegOne = (powerExpr is MathExpr.Unary && powerExpr.negative && powerExpr.value is MathExpr.Number && (powerExpr.value as MathExpr.Number).value == "1") ||
                        (powerExpr is MathExpr.Number && powerExpr.value == "-1")
                    if (isNegOne && (name == "sin" || name == "cos" || name == "tan")) {
                        val inverseFunc = when (name) {
                            "sin" -> MathExpr.Function.ASIN
                            "cos" -> MathExpr.Function.ACOS
                            "tan" -> MathExpr.Function.ATAN
                            else -> function
                        }
                        MathExpr.Builtin(inverseFunc, listOf(argument))
                    } else {
                        MathExpr.Binary(MathExpr.Op.POWER, MathExpr.Builtin(function, listOf(argument)), powerExpr)
                    }
                } else {
                    MathExpr.Builtin(function, listOf(argument))
                }
            }
        }
        fun matrix(environment: String): MathExpr {
            val rows = mutableListOf<List<MathExpr>>()
            val row = mutableListOf<MathExpr>()
            while (peek()?.kind != Kind.END) {
                row += expression()
                when {
                    accept(Kind.AMP) -> Unit
                    accept(Kind.ROW) -> {
                        if (row.isNotEmpty()) {
                            rows += row.toList()
                            row.clear()
                        }
                    }
                    peek()?.kind == Kind.END -> {
                        if (row.isNotEmpty()) {
                            rows += row.toList()
                            row.clear()
                        }
                        break
                    }
                    else -> throw MathParseException("Expected matrix column, row, or end delimiter")
                }
            }
            if (row.isNotEmpty()) {
                rows += row.toList()
                row.clear()
            }
            expect(peek()?.kind == Kind.END, "Expected end delimiter")
            expect(take().text == environment, "Mismatched matrix environment")
            if (environment == "cases") {
                var defaultVal: MathExpr = MathExpr.Number("0")
                val branches = mutableListOf<MathExpr.PiecewiseBranch>()
                for (r in rows) {
                    if (r.isEmpty()) continue
                    val v = r[0]
                    val cond = if (r.size > 1) r[1] else MathExpr.Otherwise
                    if (cond is MathExpr.Otherwise) {
                        defaultVal = v
                    }
                    branches.add(MathExpr.PiecewiseBranch(v, cond))
                }
                return MathExpr.Piecewise(branches, defaultVal)
            }
            val matrix = MathExpr.Matrix(rows)
            return if (environment == "vmatrix") MathExpr.Builtin(MathExpr.Function.DET, listOf(matrix)) else matrix
        }
    }

    private companion object {
        val KMAP_REGEX = Regex("""(?:\\operatorname\s*\{\s*kmap\s*\}|\bkmap\b)""", RegexOption.IGNORE_CASE)
        val SOP_FACTOR_REGEX = Regex("""\\overline\{([a-zA-Z][a-zA-Z0-9]*(?:_[a-zA-Z0-9]+)?)\}|([a-zA-Z][a-zA-Z0-9]*(?:_[a-zA-Z0-9]+)?)""")
        val TEX_FORMATTING_REGEX = Regex("""\\(?:left|right|quad|qquad)(?![A-Za-z])""")
        val PUNCTUATION_SPACING_REGEX = Regex("""\\[,;:!]""")
        val LE_REGEX = Regex("""\\(?:le|leq)(?![a-zA-Z])""")
        val GE_REGEX = Regex("""\\(?:ge|geq)(?![a-zA-Z])""")
        val NEQ_REGEX = Regex("""\\neq(?![a-zA-Z])""")
        val OTHERWISE_REGEX = Regex("""\\text\s*\{\s*otherwise\s*\}|\botherwise\b""")
        val IF_REGEX = Regex("""\\text\s*\{\s*if\s*\}|\bif\b""")
        val PARALLEL_WORD_REGEX = Regex("""\\parallel""")
        val PARALLEL_BARS_REGEX = Regex("""\\\|""")
        val PARTIAL_REGEX = Regex("""\\partial\b""")
        val DELTA_DIFF_QUOTIENT_REGEX = Regex("""\\frac\s*\{\s*\\(?:delta|Delta)\s*([A-Za-z]+)\s*\}\s*\{\s*\\(?:delta|Delta)\s*([A-Za-z]+)\s*\}""")
        val DELTA_DIFF_OPERATOR_REGEX = Regex("""\\frac\s*\{\s*\\(?:delta|Delta)\s*\}\s*\{\s*\\(?:delta|Delta)\s*([A-Za-z]+)\s*\}""")
        val PM_REGEX = Regex("""\\pm\b""")
        val MP_REGEX = Regex("""\\mp\b""")
        val EQUIV_REGEX = Regex("""\\equiv\b""")
        val PROPTO_REGEX = Regex("""\\propto\b""")
        val LEFTARROW_REGEX = Regex("""\\leftarrow\b""")
        val IINT_REGEX = Regex("""\\iint\b""")
        val IIINT_REGEX = Regex("""\\iiint\b""")
        val OINT_REGEX = Regex("""\\oint\b""")
        val FOURIER_REGEX = Regex("""\\mathcal\s*(?:\{\s*F\s*\}|F\b)""")
        val DEG_REGEX = Regex("""\\operatorname\s*\{\s*deg\s*\}\s*\(([^)]+)\)""")
        val RAD_REGEX = Regex("""\\operatorname\s*\{\s*rad\s*\}\s*\(([^)]+)\)""")
        val FONT_WRAPPERS_REGEX = Regex("""\\(?:vec|mathbf|boldsymbol|mathrm|text)\s*\{([^\\}]+)\}""")
        val MATHRM_D_REGEX = Regex("""\\mathrm\s*\{\s*d\s*\}""")
        val ILAPLACE_REGEX = Regex("""\\mathcal\s*(?:\{\s*L\s*\^\s*(?:\{\s*-1\s*\}|-1)\s*\}|(?:\{\s*L\s*\}|L\b)\s*\^\s*(?:\{\s*-1\s*\}|-1))""")
        val IZTRANS_REGEX = Regex("""\\mathcal\s*(?:\{\s*Z\s*\^\s*(?:\{\s*-1\s*\}|-1)\s*\}|(?:\{\s*Z\s*\}|Z\b)\s*\^\s*(?:\{\s*-1\s*\}|-1))""")
        val LAPLACE_REGEX = Regex("""\\mathcal\s*(?:\{\s*L\s*\}|L\b)(?!\s*\^)""")
        val ZTRANS_REGEX = Regex("""\\mathcal\s*(?:\{\s*Z\s*\}|Z\b)(?!\s*\^)""")
        val OPERATOR_NAME_REGEX = Regex("""\\operatorname\s*\{\s*(sin|cos|tan|arcsin|arccos|arctan|asin|acos|atan|sinh|cosh|tanh|ln|log|exp|sqrt|det|transpose|inverse|eigenvalues|eig|eigenvectors|tr|trace|rank|rref|Re|re|Im|im|conj|conjugate|arg|laplace|ilaplace|ztrans|grad|div|curl|step|unitstep|delta|dirac|diracdelta|taylor|series|conv|cconv|dft|idft|linreg|polyreg|mean|stdev)\s*\}""")
        val SUBSCRIPT_IDENTIFIER_REGEX = Regex("[A-Za-z0-9]+")
        val opens = setOf(Kind.LP, Kind.LB, Kind.LS, Kind.BEGIN)
        val closes = setOf(Kind.RP, Kind.RB, Kind.RS, Kind.END)
        val starts = setOf(Kind.NUMBER, Kind.NAME, Kind.COMMAND, Kind.LP, Kind.LB, Kind.LS, Kind.BEGIN, Kind.NABLA, Kind.OTHERWISE)
        val functions = mapOf(
            "sin" to MathExpr.Function.SIN, "cos" to MathExpr.Function.COS, "tan" to MathExpr.Function.TAN,
            "asin" to MathExpr.Function.ASIN, "arcsin" to MathExpr.Function.ASIN,
            "acos" to MathExpr.Function.ACOS, "arccos" to MathExpr.Function.ACOS,
            "atan" to MathExpr.Function.ATAN, "arctan" to MathExpr.Function.ATAN,
            "sinh" to MathExpr.Function.SINH, "cosh" to MathExpr.Function.COSH, "tanh" to MathExpr.Function.TANH,
            "ln" to MathExpr.Function.LOG, "log" to MathExpr.Function.LOG, "exp" to MathExpr.Function.EXP,
            "sqrt" to MathExpr.Function.SQRT, "det" to MathExpr.Function.DET, "abs" to MathExpr.Function.ABS,
            "factorial" to MathExpr.Function.FACTORIAL,
            "transpose" to MathExpr.Function.TRANSPOSE, "inverse" to MathExpr.Function.INVERSE,
            "eigenvalues" to MathExpr.Function.EIGENVALUES, "eig" to MathExpr.Function.EIGENVALUES,
            "eigenvectors" to MathExpr.Function.EIGENVECTORS,
            "tr" to MathExpr.Function.TRACE, "trace" to MathExpr.Function.TRACE,
            "rank" to MathExpr.Function.RANK, "rref" to MathExpr.Function.RREF,
            "Re" to MathExpr.Function.RE, "re" to MathExpr.Function.RE,
            "Im" to MathExpr.Function.IM, "im" to MathExpr.Function.IM,
            "conj" to MathExpr.Function.CONJUGATE, "conjugate" to MathExpr.Function.CONJUGATE,
            "arg" to MathExpr.Function.ARG,
            "unitstep" to MathExpr.Function.UNIT_STEP, "step" to MathExpr.Function.UNIT_STEP,
            "diracdelta" to MathExpr.Function.DIRAC_DELTA, "dirac" to MathExpr.Function.DIRAC_DELTA, "delta" to MathExpr.Function.DIRAC_DELTA
        )
        fun parse(tokens: List<Token>, boundSymbols: Set<String> = emptySet(), localScope: Set<String> = emptySet()): MathExpr =
            Parser(tokens, boundSymbols, localScope.toMutableSet()).all()
        fun expect(condition: Boolean, message: String) { if (!condition) throw MathParseException(message) }
        fun splitTop(tokens: List<Token>, delimiter: Kind): List<List<Token>> {
            val parts = mutableListOf<List<Token>>()
            var depth = 0
            var start = 0
            tokens.forEachIndexed { index, token ->
                if (token.kind == delimiter && depth == 0) { parts += tokens.subList(start, index); start = index + 1 }
                if (token.kind in opens) depth++
                if (token.kind in closes) depth--
            }
            parts += tokens.subList(start, tokens.size)
            return parts
        }
    }
}
