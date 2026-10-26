package com.axioloom.app.ml.hmer

import com.axioloom.app.domain.model.BlockRevision
import com.axioloom.app.domain.model.EquationBlock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Recognition provenance is explicit so a manual test result is never mistaken for model output. */
enum class RecognitionSource { ONNX_MODEL, MANUAL_TEST, PROTOTYPE_FALLBACK }

sealed class BlockRecognitionOutcome {
    data class Recognized(
        val latex: String,
        val source: RecognitionSource,
        val tokenIds: List<Int>? = null,
        val reachedEndToken: Boolean? = null,
        val alternatives: List<String> = emptyList()
    ) : BlockRecognitionOutcome()

    data class ModelUnavailable(val reason: String) : BlockRecognitionOutcome()
    data class Failed(val reason: String) : BlockRecognitionOutcome()
}

/**
 * Per-block debounce and stale-result gate for the Phase 2 workspace. Call [submit] whenever a
 * block revision becomes dirty. Completion callbacks run on a background dispatcher; the UI or
 * ViewModel must switch to its state-owning dispatcher before publishing Compose state.
 *
 * When no ONNX assets are bundled, [injectManualLatex] is the deliberate Phase 3 fallback API.
 * Phase 4's long-press inspector can present a LaTeX entry dialog and call this with the current
 * block ticket; stale dialogs are rejected automatically.
 */
class HmerRecognitionCoordinator(
    private val scope: CoroutineScope,
    private val rasterizer: StrokeBitmapRasterizer,
    private val engine: OnnxHmerEngine,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val prototypeClassifier: SymbolPrototypeClassifier? = null,
    private val onResult: (BlockRevision, BlockRecognitionOutcome) -> Unit
) : AutoCloseable {
    var onTiming: ((rasterMs: Double, inferenceMs: Double) -> Unit)? = null
    private val monitor = Any()
    private val jobs = mutableMapOf<UUID, Job>()
    private val latestTickets = mutableMapOf<UUID, BlockRevision>()
    private var closed = false

    fun registerTicket(ticket: BlockRevision) {
        synchronized(monitor) {
            if (!closed) {
                latestTickets[ticket.blockId] = ticket
            }
        }
    }

    init { require(debounceMillis >= 0L) }

    fun submit(block: EquationBlock) {
        val ticket = block.ticket
        synchronized(monitor) {
            if (closed) return
            latestTickets[block.id] = ticket
            jobs.remove(block.id)?.cancel()
            jobs[block.id] = scope.launch(Dispatchers.Default) {
                delay(debounceMillis)
                val outcome = try {
                    val t0 = System.nanoTime()
                    val raster = rasterizer.rasterize(block.strokes)
                    val rasterMs = (System.nanoTime() - t0) / 1_000_000.0

                    val t1 = System.nanoTime()
                    val inference = engine.recognize(raster)
                    val inferenceMs = (System.nanoTime() - t1) / 1_000_000.0
                    onTiming?.invoke(rasterMs, inferenceMs)

                    when (inference) {
                        is HmerInferenceResult.Recognized -> {
                            if (inference.latex.isBlank()) {
                                val fallback = prototypeClassifier?.recognizeExpression(block.strokes)
                                if (fallback != null && fallback.latex.isNotBlank()) {
                                    BlockRecognitionOutcome.Recognized(
                                        latex = fallback.latex,
                                        source = RecognitionSource.PROTOTYPE_FALLBACK,
                                        alternatives = fallback.alternatives
                                    )
                                } else {
                                    BlockRecognitionOutcome.Recognized(
                                        latex = inference.latex,
                                        source = RecognitionSource.ONNX_MODEL,
                                        tokenIds = inference.tokenIds,
                                        reachedEndToken = inference.reachedEndToken,
                                        alternatives = inference.alternatives
                                    )
                                }
                            } else {
                                BlockRecognitionOutcome.Recognized(
                                    latex = inference.latex,
                                    source = RecognitionSource.ONNX_MODEL,
                                    tokenIds = inference.tokenIds,
                                    reachedEndToken = inference.reachedEndToken,
                                    alternatives = inference.alternatives
                                )
                            }
                        }
                        is HmerInferenceResult.Unavailable -> {
                            val fallback = prototypeClassifier?.recognizeExpression(block.strokes)
                            if (fallback != null && fallback.latex.isNotBlank()) {
                                BlockRecognitionOutcome.Recognized(
                                    latex = fallback.latex,
                                    source = RecognitionSource.PROTOTYPE_FALLBACK,
                                    alternatives = fallback.alternatives
                                )
                            } else {
                                BlockRecognitionOutcome.ModelUnavailable(inference.reason)
                            }
                        }
                        is HmerInferenceResult.Failed -> {
                            val fallback = prototypeClassifier?.recognizeExpression(block.strokes)
                            if (fallback != null && fallback.latex.isNotBlank()) {
                                BlockRecognitionOutcome.Recognized(
                                    latex = fallback.latex,
                                    source = RecognitionSource.PROTOTYPE_FALLBACK,
                                    alternatives = fallback.alternatives
                                )
                            } else {
                                BlockRecognitionOutcome.Failed(inference.reason)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    BlockRecognitionOutcome.Failed(
                        "Could not rasterize or recognize block: ${failure.message ?: failure.javaClass.simpleName}"
                    )
                }
                val isCurrent = synchronized(monitor) {
                    val current = !closed && latestTickets[block.id] == ticket
                    if (current) jobs.remove(block.id)
                    current
                }
                if (isCurrent) onResult(ticket, outcome)
            }
        }
    }

    /**
     * Accepts a LaTeX string from a test-input UI only for the block's current revision.
     * Returns false if the block changed, was removed, or the coordinator was closed.
     */
    fun injectManualLatex(ticket: BlockRevision, latex: String, alternatives: List<String> = emptyList()): Boolean {
        val value = latex.trim()
        require(value.isNotEmpty()) { "Manual LaTeX must not be empty" }
        require(value.length <= MAX_MANUAL_LATEX_LENGTH) { "Manual LaTeX is too long" }
        require(value.none { it == '\u0000' }) { "Manual LaTeX contains a NUL character" }
        val accepted = synchronized(monitor) {
            val currentTicket = latestTickets[ticket.blockId]
            if (closed || (currentTicket != null && currentTicket != ticket)) {
                false
            } else {
                latestTickets[ticket.blockId] = ticket
                jobs.remove(ticket.blockId)?.cancel()
                true
            }
        }
        if (accepted) {
            onResult(ticket, BlockRecognitionOutcome.Recognized(value, RecognitionSource.MANUAL_TEST, alternatives = alternatives))
        }
        return accepted
    }

    /** Cancels pending work and invalidates any later result for a removed block. */
    fun forget(blockId: UUID) {
        synchronized(monitor) {
            jobs.remove(blockId)?.cancel()
            latestTickets.remove(blockId)
        }
    }

    override fun close() {
        synchronized(monitor) {
            if (closed) return
            closed = true
            jobs.values.forEach(Job::cancel)
            jobs.clear()
            latestTickets.clear()
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 500L
        const val MAX_MANUAL_LATEX_LENGTH = 4096
    }
}
