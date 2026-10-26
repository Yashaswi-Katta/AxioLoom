package com.axioloom.app.domain.cas

import com.axioloom.app.domain.model.BindingSnapshot
import com.axioloom.app.domain.model.CasMode
import com.axioloom.app.domain.model.CasResult
import com.axioloom.app.domain.model.MathIntent
import com.axioloom.app.domain.model.SymbolKey
import com.axioloom.app.domain.model.SymbolicTransform
import com.axioloom.app.domain.model.SymjaCompiler
import com.axioloom.app.domain.model.referencedSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.matheclipse.core.basic.Config
import org.matheclipse.core.eval.EvalEngine
import org.matheclipse.core.eval.ExprEvaluator
import org.matheclipse.core.eval.TeXUtilities
import org.matheclipse.core.expression.F
import java.io.Closeable
import java.io.StringWriter
import java.util.concurrent.Executors

class CasEvaluationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Keeps Symja and its EvalEngine confined to one worker thread. Only AST-compiled commands are
 * accepted; HMER text is never handed to Symja's parser directly.
 */
class OfflineCasEngine : Closeable {
    companion object {
        private var configured = false

        @Synchronized
        fun configureSymjaForAndroid() {
            if (configured) return
            runCatching {
                Config.JAS_NO_THREADS = true
                Config.JAVA_AWT_DESKTOP_AVAILABLE = false
                Config.FILESYSTEM_ENABLED = false
                Config.SERVER_MODE = true
                Config.FUZZY_PARSER = false
                F.initSymbols()
                configured = true
            }.onFailure { error ->
                android.util.Log.w("OfflineCasEngine", "Failed during Symja Android static pre-configuration", error)
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(null, task, "AxioLoom-Symja", 8L * 1024L * 1024L).apply {
            isDaemon = true
        }
    }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    @Volatile
    private var evaluatorInstance: ExprEvaluator? = null

    private fun getEvaluator(): ExprEvaluator {
        evaluatorInstance?.let { return it }
        synchronized(this) {
            evaluatorInstance?.let { return it }
            configureSymjaForAndroid()
            return try {
                ExprEvaluator(false, 100.toShort()).also { evaluatorInstance = it }
            } catch (t: Throwable) {
                android.util.Log.e("OfflineCasEngine", "Failed to initialize ExprEvaluator", t)
                val detail = t.cause?.message ?: t.message ?: t.javaClass.simpleName
                throw CasEvaluationException("Failed to initialize offline CAS engine: $detail", t)
            }
        }
    }

    suspend fun warmUp(): Unit = withContext(dispatcher) {
        try {
            val active = getEvaluator()
            val engine = active.evalEngine
            EvalEngine.set(engine)
            val res = active.eval("1+1").toString()
            check(res == "2") { "Symja warm-up check expected '2', got '$res'" }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            android.util.Log.e("OfflineCasEngine", "Offline CAS warmUp failed", failure)
            val detail = failure.cause?.message ?: failure.message ?: failure.javaClass.simpleName
            throw CasEvaluationException("Offline CAS initialization failed: $detail", failure)
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
                SymbolicTransform.TAYLOR_SERIES -> {
                    val primarySymbol = intent.referencedSymbols.firstOrNull { it.kind == SymbolKey.Kind.VALUE }?.name ?: "x"
                    val seriesVar = SymjaCompiler.encode(primarySymbol)
                    "Normal(Series(${compiled.command},{$seriesVar,0,5}))"
                }
            }
            val command = if (approximate) "N($baseCommand,6)" else baseCommand
            val active = getEvaluator()
            EvalEngine.set(active.evalEngine)
            val result = active.eval(command)
            val writer = StringWriter()
            EvalEngine.set(active.evalEngine)
            val converted = TeXUtilities(active.evalEngine, false).toTeX(result, writer)
            val latexOutput = writer.toString()
            val latex = if (converted && latexOutput.isNotEmpty()) applyLatexAliases(latexOutput, compiled.latexAliases) else SymjaCompiler.decode(result.toString())
            CasResult(SymjaCompiler.decode(result.toString()), latex, approximate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (failure is CasEvaluationException) throw failure
            throw CasEvaluationException(failure.message ?: "Symja could not evaluate this expression", failure)
        }
    }

    suspend fun evalRaw(command: String): String = withContext(dispatcher) {
        try {
            val active = getEvaluator()
            EvalEngine.set(active.evalEngine)
            active.eval(command).toString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            throw CasEvaluationException(failure.message ?: "Evaluation failed: $command", failure)
        }
    }

    suspend fun evalCommandToLatex(command: String, aliases: Map<String, String> = emptyMap()): String = withContext(dispatcher) {
        try {
            val active = getEvaluator()
            EvalEngine.set(active.evalEngine)
            val result = active.eval(command)
            val writer = StringWriter()
            EvalEngine.set(active.evalEngine)
            val converted = TeXUtilities(active.evalEngine, false).toTeX(result, writer)
            val latexOutput = writer.toString()
            if (converted && latexOutput.isNotEmpty()) applyLatexAliases(latexOutput, aliases) else SymjaCompiler.decode(result.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            throw CasEvaluationException(failure.message ?: "Evaluation failed", failure)
        }
    }

    private fun applyLatexAliases(latex: String, aliases: Map<String, String>): String {
        var output = latex
        aliases.entries.sortedByDescending { it.key.length }.forEach { (encoded, replacement) ->
            output = output.replace(encoded, replacement)
        }
        return output
    }

    override fun close() {
        (dispatcher as? Closeable)?.close()
        executor.shutdown()
    }
}
