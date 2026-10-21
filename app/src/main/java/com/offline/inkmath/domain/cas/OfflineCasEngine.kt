package com.offline.inkmath.domain.cas

import com.offline.inkmath.domain.model.BindingSnapshot
import com.offline.inkmath.domain.model.CasMode
import com.offline.inkmath.domain.model.CasResult
import com.offline.inkmath.domain.model.MathIntent
import com.offline.inkmath.domain.model.SymbolicTransform
import com.offline.inkmath.domain.model.SymjaCompiler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.matheclipse.core.eval.EvalEngine
import org.matheclipse.core.eval.ExprEvaluator
import org.matheclipse.core.eval.TeXUtilities
import java.io.Closeable
import java.io.StringWriter
import java.util.concurrent.Executors

class CasEvaluationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Keeps Symja and its EvalEngine confined to one worker thread. Only AST-compiled commands are
 * accepted; HMER text is never handed to Symja's parser directly.
 */
class OfflineCasEngine : Closeable {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "InkMath-Symja").apply { isDaemon = true }
    }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val evaluator: ExprEvaluator by lazy(LazyThreadSafetyMode.NONE) {
        ExprEvaluator(false, 100.toShort())
    }

    suspend fun warmUp() = withContext(dispatcher) {
        try {
            val engine = evaluator.evalEngine
            EvalEngine.set(engine)
            check(evaluator.eval("1+1").toString() == "2") { "Symja warm-up self-check failed" }
        } catch (failure: Exception) {
            throw CasEvaluationException("Offline CAS initialization failed", failure)
        }
    }

    suspend fun evaluate(
        intent: MathIntent,
        bindings: BindingSnapshot = BindingSnapshot(),
        mode: CasMode = CasMode.SYMBOLIC,
        transform: SymbolicTransform = SymbolicTransform.AUTO
    ): CasResult = withContext(dispatcher) {
        try {
            val compiled = SymjaCompiler.compile(intent, bindings)
            val approximate = intent.isApproximate || mode == CasMode.NUMERIC
            val baseCommand = when (transform) {
                SymbolicTransform.AUTO -> compiled.command
                SymbolicTransform.SIMPLIFY -> "Simplify(${compiled.command})"
                SymbolicTransform.FACTOR -> "Factor(${compiled.command})"
                SymbolicTransform.EXPAND -> "Expand(${compiled.command})"
                SymbolicTransform.PARTIAL_FRACTIONS -> "Apart(${compiled.command})"
                SymbolicTransform.TOGETHER -> "Together(${compiled.command})"
                SymbolicTransform.TRIG_EXPAND -> "TrigExpand(${compiled.command})"
                SymbolicTransform.TRIG_REDUCE -> "TrigReduce(${compiled.command})"
                SymbolicTransform.TAYLOR_SERIES -> "Normal(Series(${compiled.command},{If(Length(Variables(${compiled.command}))>0,Variables(${compiled.command})[[1]],x),0,5}))"
            }
            val command = if (approximate) "N($baseCommand,6)" else baseCommand
            val active = evaluator
            EvalEngine.set(active.evalEngine)
            val result = active.eval(command)
            val writer = StringWriter()
            EvalEngine.set(active.evalEngine)
            val converted = TeXUtilities(active.evalEngine, false).toTeX(result, writer)
            val latexOutput = writer.toString()
            val latex = if (converted && latexOutput.isNotEmpty()) applyLatexAliases(latexOutput, compiled.latexAliases) else result.toString()
            CasResult(result.toString(), latex, approximate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure is CasEvaluationException) throw failure
            throw CasEvaluationException(failure.message ?: "Symja could not evaluate this expression", failure)
        }
    }

    suspend fun evalRaw(command: String): String = withContext(dispatcher) {
        try {
            val active = evaluator
            EvalEngine.set(active.evalEngine)
            active.eval(command).toString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw CasEvaluationException(failure.message ?: "Evaluation failed: $command", failure)
        }
    }

    suspend fun evalCommandToLatex(command: String, aliases: Map<String, String> = emptyMap()): String = withContext(dispatcher) {
        try {
            val active = evaluator
            EvalEngine.set(active.evalEngine)
            val result = active.eval(command)
            val writer = StringWriter()
            EvalEngine.set(active.evalEngine)
            val converted = TeXUtilities(active.evalEngine, false).toTeX(result, writer)
            val latexOutput = writer.toString()
            if (converted && latexOutput.isNotEmpty()) applyLatexAliases(latexOutput, aliases) else result.toString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw CasEvaluationException(failure.message ?: "Evaluation failed", failure)
        }
    }

    private fun applyLatexAliases(latex: String, aliases: Map<String, String>): String {
        var output = latex
        aliases.forEach { (encoded, replacement) -> output = output.replace(encoded, replacement) }
        return output
    }

    override fun close() {
        (dispatcher as? Closeable)?.close()
        executor.shutdown()
    }
}
