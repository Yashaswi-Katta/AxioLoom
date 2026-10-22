package com.offline.inkmath.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.ink.strokes.Stroke
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offline.inkmath.data.storage.NotebookPageMeta
import com.offline.inkmath.data.storage.NotebookRepository
import com.offline.inkmath.domain.cas.OfflineCasEngine
import com.offline.inkmath.domain.clustering.InkWorkspaceReducer
import com.offline.inkmath.domain.clustering.InkWorkspaceState
import com.offline.inkmath.domain.clustering.WorkspaceDiff
import com.offline.inkmath.domain.clustering.WorkspaceHistoryManager
import com.offline.inkmath.domain.dag.FastNumericPropagator
import com.offline.inkmath.domain.dag.FastNumericResult
import com.offline.inkmath.domain.dag.ReactiveGraphSnapshot
import com.offline.inkmath.domain.dag.ReactiveSymbolGraph
import com.offline.inkmath.domain.export.MarkdownExporter
import com.offline.inkmath.domain.export.PdfExporter
import com.offline.inkmath.domain.export.PdfTheme
import com.offline.inkmath.domain.model.BlockDisplayMode
import com.offline.inkmath.domain.model.BlockRevision
import com.offline.inkmath.domain.model.BlockStatus
import com.offline.inkmath.domain.model.CasMode
import com.offline.inkmath.domain.model.MathIntent
import com.offline.inkmath.domain.model.SymbolicTransform
import com.offline.inkmath.domain.model.TimedStroke
import com.offline.inkmath.domain.model.definedSymbol
import com.offline.inkmath.domain.parser.AmbiguousSolveVariableException
import com.offline.inkmath.domain.parser.LatexToSymjaTranspiler
import com.offline.inkmath.ml.hmer.BlockRecognitionOutcome
import com.offline.inkmath.ml.hmer.HmerInferenceResult
import com.offline.inkmath.ml.hmer.HmerRecognitionCoordinator
import com.offline.inkmath.ml.hmer.OnnxHmerEngine
import com.offline.inkmath.ml.hmer.RecognitionSource
import com.offline.inkmath.ml.hmer.StrokeBitmapRasterizer
import com.offline.inkmath.ml.hmer.SymbolPrototypeClassifier
import com.offline.inkmath.ui.canvas.CanvasToolMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.UUID

class InkMathViewModel(application: Application) : AndroidViewModel(application) {
    private val cas = OfflineCasEngine()
    private val parser = LatexToSymjaTranspiler()
    private val graph = ReactiveSymbolGraph(cas)
    private val reducer = InkWorkspaceReducer()
    private val rasterizer = StrokeBitmapRasterizer()
    private val hmerEngine = OnnxHmerEngine(application.applicationContext)
    val prototypeClassifier = SymbolPrototypeClassifier(application.applicationContext)
    val mnaSolver = com.offline.inkmath.domain.circuit.SymbolicMnaSolver(cas)
    val spatialSynthesizer = com.offline.inkmath.domain.clustering.SpatialStructureSynthesizer(parser)
    val calculusAnalyzer = com.offline.inkmath.domain.cas.CalculusTheoremAnalyzer(cas)
    val matrixAnalyzer = com.offline.inkmath.domain.cas.MatrixAnalyzer(cas)
    private val historyManager = WorkspaceHistoryManager(maxDepth = 50)
    private val notebookRepo = NotebookRepository(application.applicationContext)
    val externalModelManager = com.offline.inkmath.ml.hmer.ExternalModelManager(application.applicationContext)

    private val mutableInkWorkspace = MutableStateFlow(InkWorkspaceState())
    private val mutableRecognitionMessages = MutableStateFlow<Map<UUID, String>>(emptyMap())
    private val mutableFastNumericOverrides = MutableStateFlow<Map<UUID, FastNumericResult>>(emptyMap())
    private val mutableInputError = MutableStateFlow<String?>(null)
    private val mutableWarmupError = MutableStateFlow<String?>(null)
    private val mutableCanUndo = MutableStateFlow(false)
    private val mutableCanRedo = MutableStateFlow(false)
    private val mutableToolMode = MutableStateFlow(CanvasToolMode.DRAW)
    private val mutablePhysicalConstantsEnabled = MutableStateFlow(false)
    private val mutableCasMode = MutableStateFlow(CasMode.SYMBOLIC)
    private val mutableTelemetry = MutableStateFlow(com.offline.inkmath.ui.canvas.SubsystemTelemetry())
    private val mutableActiveSchematic = MutableStateFlow<com.offline.inkmath.domain.circuit.RecognizedSchematic?>(null)
    private val mutableMnaResult = MutableStateFlow<com.offline.inkmath.domain.circuit.MnaResult?>(null)
    private val mutableDimensionalWarnings = MutableStateFlow<Map<UUID, List<com.offline.inkmath.domain.units.DimensionalMismatchWarning>>>(emptyMap())

    private val mutablePages = MutableStateFlow<List<NotebookPageMeta>>(emptyList())
    private val mutableCurrentPage = MutableStateFlow<NotebookPageMeta?>(null)

    val inkWorkspace: StateFlow<InkWorkspaceState> = mutableInkWorkspace.asStateFlow()
    val workspace: StateFlow<ReactiveGraphSnapshot> = graph.state
    val fastNumericOverrides: StateFlow<Map<UUID, FastNumericResult>> = mutableFastNumericOverrides.asStateFlow()
    val recognitionMessages: StateFlow<Map<UUID, String>> = mutableRecognitionMessages.asStateFlow()
    val inputError: StateFlow<String?> = mutableInputError.asStateFlow()
    val warmupError: StateFlow<String?> = mutableWarmupError.asStateFlow()
    val canUndo: StateFlow<Boolean> = mutableCanUndo.asStateFlow()
    val canRedo: StateFlow<Boolean> = mutableCanRedo.asStateFlow()
    val toolMode: StateFlow<CanvasToolMode> = mutableToolMode.asStateFlow()
    val physicalConstantsEnabled: StateFlow<Boolean> = mutablePhysicalConstantsEnabled.asStateFlow()
    val casMode: StateFlow<CasMode> = mutableCasMode.asStateFlow()
    val telemetry: StateFlow<com.offline.inkmath.ui.canvas.SubsystemTelemetry> = mutableTelemetry.asStateFlow()
    val activeSchematic: StateFlow<com.offline.inkmath.domain.circuit.RecognizedSchematic?> = mutableActiveSchematic.asStateFlow()
    val mnaResult: StateFlow<com.offline.inkmath.domain.circuit.MnaResult?> = mutableMnaResult.asStateFlow()
    val dimensionalWarnings: StateFlow<Map<UUID, List<com.offline.inkmath.domain.units.DimensionalMismatchWarning>>> = mutableDimensionalWarnings.asStateFlow()
    val pages: StateFlow<List<NotebookPageMeta>> = mutablePages.asStateFlow()
    val currentPage: StateFlow<NotebookPageMeta?> = mutableCurrentPage.asStateFlow()

    private var autoSaveJob: Job? = null

    private val coordinator = HmerRecognitionCoordinator(
        scope = viewModelScope,
        rasterizer = rasterizer,
        engine = hmerEngine,
        prototypeClassifier = prototypeClassifier,
        onResult = ::handleRecognitionOutcome
    )

    init {
        viewModelScope.launch {
            try {
                cas.warmUp()
                mutableWarmupError.value = null
            } catch (failure: Exception) {
                mutableWarmupError.value = failure.message ?: "Offline CAS warm-up failed"
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val allPages = notebookRepo.listPages()
                mutablePages.value = allPages
                val activeId = notebookRepo.getActivePageId()
                val activeMeta = allPages.firstOrNull { it.id == activeId }
                    ?: allPages.firstOrNull()
                    ?: notebookRepo.createPage("Main Notebook")
                mutableCurrentPage.value = activeMeta

                val restored = notebookRepo.loadPage(activeMeta.id, parser, graph)
                if (restored != null && restored.strokes.isNotEmpty()) {
                    mutableInkWorkspace.value = restored
                    updateCanUndoRedo()
                }
            } catch (e: Throwable) {
                android.util.Log.e("InkMathViewModel", "Error loading initial page", e)
            }
        }
    }

    fun setToolMode(mode: CanvasToolMode) {
        mutableToolMode.value = mode
    }

    private fun updateCanUndoRedo() {
        mutableCanUndo.value = historyManager.canUndo
        mutableCanRedo.value = historyManager.canRedo
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(1000L)
            val currentId = mutableCurrentPage.value?.id
            if (currentId != null) {
                notebookRepo.savePage(currentId, mutableInkWorkspace.value)
                val allPages = notebookRepo.listPages()
                mutablePages.value = allPages
                mutableCurrentPage.value = allPages.firstOrNull { it.id == currentId }
            }
        }
    }

    fun undo() {
        val diff = historyManager.undo(mutableInkWorkspace.value) ?: return
        updateCanUndoRedo()
        applyWorkspaceDiff(diff)
    }

    fun redo() {
        val diff = historyManager.redo(mutableInkWorkspace.value) ?: return
        updateCanUndoRedo()
        applyWorkspaceDiff(diff)
    }

    private fun applyWorkspaceDiff(diff: WorkspaceDiff) {
        mutableInkWorkspace.value = diff.targetState
        diff.removedBlockIds.forEach { id ->
            coordinator.forget(id)
            mutableRecognitionMessages.update { it - id }
            viewModelScope.launch { graph.remove(id) }
        }
        diff.addedOrChangedBlocks.forEach { block ->
            if (block.intent != null) {
                viewModelScope.launch { graph.upsert(block.id, block.intent, block.transform) }
            } else if (block.needsRecognition) {
                mutableRecognitionMessages.update { it + (block.id to "Recognizing handwriting...") }
                coordinator.submit(block)
            }
        }
        scheduleAutoSave()
    }

    fun commitFinishedStroke(stroke: Stroke) {
        try {
            val timed = TimedStroke.fromStroke(stroke, SystemClock.elapsedRealtime())
            val previousState = mutableInkWorkspace.value
            val commitResult = reducer.commit(previousState, timed)

            historyManager.recordCommit(previousState)
            updateCanUndoRedo()

            mutableInkWorkspace.value = commitResult.state
            mutableInputError.value = null

            commitResult.removedBlockIds.forEach { id ->
                coordinator.forget(id)
                mutableRecognitionMessages.update { it - id }
                viewModelScope.launch { graph.remove(id) }
            }

            commitResult.invalidatedBlockIds.forEach { id ->
                viewModelScope.launch { graph.remove(id) }
            }

            val blocksById = commitResult.state.blocks.associateBy { it.id }
            commitResult.dirtyBlockIds.forEach { id ->
                blocksById[id]?.let { block ->
                    mutableRecognitionMessages.update { it + (id to "Recognizing handwriting...") }
                    coordinator.submit(block)
                }
            }
            scheduleAutoSave()
        } catch (failure: Exception) {
            mutableInputError.value = failure.message ?: "Failed to commit stroke"
        }
    }

    fun moveBlock(blockId: UUID, deltaWorld: Offset) {
        if (deltaWorld.x == 0f && deltaWorld.y == 0f) return
        val targetBlock = mutableInkWorkspace.value.blocks.firstOrNull { it.id == blockId } ?: return
        val blockStrokeIds = targetBlock.strokeIds

        mutableInkWorkspace.update { state ->
            val updatedBlocks = state.blocks.map { block ->
                if (block.id == blockId) {
                    val translatedStrokes = block.strokes.map { it.translate(deltaWorld.x, deltaWorld.y) }
                    val newBounds = Rect(
                        block.bounds.left + deltaWorld.x,
                        block.bounds.top + deltaWorld.y,
                        block.bounds.right + deltaWorld.x,
                        block.bounds.bottom + deltaWorld.y
                    )
                    val newAnchor = block.resultAnchorPoint?.let { it + deltaWorld }
                    block.copy(
                        strokes = translatedStrokes,
                        bounds = newBounds,
                        resultAnchorPoint = newAnchor
                    )
                } else block
            }
            val updatedStrokes = state.strokes.map { stroke ->
                if (stroke.id in blockStrokeIds) {
                    stroke.translate(deltaWorld.x, deltaWorld.y)
                } else stroke
            }
            state.copy(strokes = updatedStrokes, blocks = updatedBlocks, version = state.version + 1)
        }
        scheduleAutoSave()
    }

    fun toggleBlockDisplayMode(blockId: UUID) {
        mutableInkWorkspace.update { state ->
            state.copy(
                blocks = state.blocks.map { b ->
                    if (b.id == blockId) {
                        val next = if (b.displayMode == BlockDisplayMode.HANDWRITTEN) BlockDisplayMode.TYPESET else BlockDisplayMode.HANDWRITTEN
                        b.copy(displayMode = next)
                    } else b
                },
                version = state.version + 1
            )
        }
        scheduleAutoSave()
    }

    fun setBlockTransform(blockId: UUID, transform: SymbolicTransform) {
        mutableInkWorkspace.update { state ->
            state.copy(
                blocks = state.blocks.map { b ->
                    if (b.id == blockId) b.copy(transform = transform) else b
                },
                version = state.version + 1
            )
        }
        viewModelScope.launch {
            graph.setTransform(blockId, transform)
            scheduleAutoSave()
        }
    }

    fun injectManualLatex(ticket: BlockRevision, latex: String): Boolean {
        return coordinator.injectManualLatex(ticket, latex)
    }

    fun selectBlockAlternative(blockId: UUID, alternativeLatex: String) {
        val block = mutableInkWorkspace.value.blocks.firstOrNull { it.id == blockId } ?: return
        val currentLatex = block.recognizedLatex
        val newAlts = (listOfNotNull(currentLatex) + block.alternatives)
            .distinct()
            .filter { it != alternativeLatex }
        coordinator.injectManualLatex(block.ticket, alternativeLatex, newAlts)
    }

    suspend fun getDerivationSteps(blockId: UUID): List<com.offline.inkmath.domain.cas.DerivationStep> {
        val block = mutableInkWorkspace.value.blocks.firstOrNull { it.id == blockId } ?: return emptyList()
        val bindings = graph.getBindingsForBlock(blockId)
        return com.offline.inkmath.domain.cas.StepDerivationEngine.deriveSteps(block, bindings, cas)
    }

    fun togglePhysicalConstants() {
        val next = !mutablePhysicalConstantsEnabled.value
        mutablePhysicalConstantsEnabled.value = next
        viewModelScope.launch {
            if (next) {
                graph.setGlobalConstants(com.offline.inkmath.domain.model.EngineeringConstants.getPhysicalConstantsBindings())
            } else {
                graph.setGlobalConstants(emptyMap())
            }
            scheduleAutoSave()
        }
    }

    fun setBlockSolveTarget(blockId: UUID, targetVar: String) {
        val block = mutableInkWorkspace.value.blocks.firstOrNull { it.id == blockId } ?: return
        val latex = block.recognizedLatex ?: return
        viewModelScope.launch {
            try {
                val bound = graph.state.value.symbolOwners.keys
                    .filter { it != block.intent?.definedSymbol }
                    .map { it.name }
                    .toSet()
                val transpiled = parser.transpile(latex, boundSymbols = bound, solveTarget = targetVar)
                mutableInkWorkspace.update { state ->
                    state.copy(
                        blocks = state.blocks.map { b ->
                            if (b.id == blockId) {
                                b.copy(
                                    intent = transpiled.intent,
                                    solveTarget = targetVar,
                                    status = BlockStatus.Dirty
                                )
                            } else b
                        },
                        version = state.version + 1
                    )
                }
                graph.upsert(blockId, transpiled.intent, block.transform)
                scheduleAutoSave()
            } catch (failure: Exception) {
                mutableInputError.value = failure.message ?: "Could not solve for $targetVar"
            }
        }
    }

    private var sliderCommitJob: Job? = null

    fun onParameterSliderScrub(varName: String, value: Double) {
        viewModelScope.launch(Dispatchers.Default) {
            val results = FastNumericPropagator.propagate(
                graph = graph.state.value,
                blocks = mutableInkWorkspace.value.blocks,
                overrides = mapOf(varName to value)
            )
            mutableFastNumericOverrides.value = results
        }
    }

    fun onParameterSliderRelease(blockId: UUID, varName: String, value: Double) {
        sliderCommitJob?.cancel()
        sliderCommitJob = viewModelScope.launch(Dispatchers.Default) {
            delay(150L) // 150ms debounced CAS pass
            commitParameterScrub(blockId, varName, value)
        }
    }

    fun commitParameterScrub(blockId: UUID, varName: String, value: Double) {
        viewModelScope.launch {
            val formatted = FastNumericPropagator.formatSignificant(value, 4)
            val block = mutableInkWorkspace.value.blocks.firstOrNull { it.id == blockId }
            val currentLatex = block?.recognizedLatex ?: "$varName = $formatted"
            val newLatex = if (currentLatex.contains("=")) {
                "${currentLatex.substringBefore("=").trim()} = $formatted"
            } else {
                "$varName = $formatted"
            }
            val bound = graph.state.value.symbolOwners.keys
                .filter { it.name != varName }
                .map { it.name }
                .toSet()
            try {
                val transpiled = parser.transpile(newLatex, boundSymbols = bound)
                mutableInkWorkspace.update { state ->
                    state.copy(
                        blocks = state.blocks.map { b ->
                            if (b.id == blockId) {
                                b.copy(
                                    recognizedLatex = transpiled.cleanLatex,
                                    intent = transpiled.intent,
                                    status = BlockStatus.Dirty
                                )
                            } else b
                        },
                        version = state.version + 1
                    )
                }
                mutableFastNumericOverrides.value = emptyMap()
                graph.upsert(blockId, transpiled.intent, block?.transform ?: SymbolicTransform.AUTO)
                scheduleAutoSave()
            } catch (_: Exception) {
                mutableFastNumericOverrides.value = emptyMap()
            }
        }
    }

    fun addCustomPrototype(label: String, latex: String, strokePolylines: List<List<androidx.compose.ui.geometry.Offset>>) {
        prototypeClassifier.addCustomPrototype(label, latex, strokePolylines, saveToFile = true)
    }

    fun groupBlocksAsSystem(blockIds: List<UUID>) {
        val blocks = mutableInkWorkspace.value.blocks.filter { it.id in blockIds }
        val synthesized = spatialSynthesizer.synthesizeEquationSystem(blocks) ?: return
        viewModelScope.launch {
            blockIds.forEach { graph.remove(it) }
            val primaryId = blocks.first().id
            val remainingIds = blockIds.filter { it != primaryId }.toSet()

            mutableInkWorkspace.update { state ->
                val updatedBlocks = state.blocks.filter { it.id !in remainingIds }.map { b ->
                    if (b.id == primaryId) {
                        b.copy(
                            recognizedLatex = synthesized.synthesizedLatex,
                            intent = synthesized.intent,
                            status = BlockStatus.Dirty
                        )
                    } else b
                }
                state.copy(blocks = updatedBlocks, version = state.version + 1)
            }
            graph.upsert(primaryId, synthesized.intent)
            scheduleAutoSave()
        }
    }

    private fun handleRecognitionOutcome(ticket: BlockRevision, outcome: BlockRecognitionOutcome) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val currentState = mutableInkWorkspace.value
            val targetBlock = currentState.blocks.firstOrNull { it.id == ticket.blockId } ?: return@launch
            if (targetBlock.ticket != ticket) return@launch

            when (outcome) {
                is BlockRecognitionOutcome.Recognized -> {
                    val sourceTag = when (outcome.source) {
                        RecognitionSource.MANUAL_TEST -> "Manual test input"
                        RecognitionSource.PROTOTYPE_FALLBACK -> "Handwriting Calibrator"
                        RecognitionSource.ONNX_MODEL -> "ONNX HMER"
                    }
                    try {
                        val bound = graph.state.value.symbolOwners.keys
                            .filter { it != targetBlock.intent?.definedSymbol }
                            .map { it.name }
                            .toSet()
                        val candidates = targetBlock.candidateSolveVars.toMutableList()
                        var chosenTarget = targetBlock.solveTarget
                        val transpiled = try {
                            parser.transpile(outcome.latex, boundSymbols = bound, solveTarget = chosenTarget)
                        } catch (ambiguous: AmbiguousSolveVariableException) {
                            if (candidates.isEmpty()) candidates.addAll(ambiguous.candidates)
                            val defaultTarget = ambiguous.candidates.first()
                            chosenTarget = defaultTarget
                            parser.transpile(outcome.latex, boundSymbols = bound, solveTarget = defaultTarget)
                        }

                        mutableInkWorkspace.update { state ->
                            state.copy(
                                blocks = state.blocks.map { block ->
                                    if (block.ticket == ticket) {
                                        block.copy(
                                            recognizedLatex = transpiled.cleanLatex,
                                            intent = transpiled.intent,
                                            status = BlockStatus.Dirty,
                                            solveTarget = if (transpiled.intent is MathIntent.EquationSolve) transpiled.intent.targetVar else chosenTarget,
                                            candidateSolveVars = candidates,
                                            alternatives = outcome.alternatives
                                        )
                                    } else block
                                },
                                version = state.version + 1
                            )
                        }
                        mutableRecognitionMessages.update { it + (ticket.blockId to sourceTag) }
                        graph.upsert(ticket.blockId, transpiled.intent, targetBlock.transform)

                        // Dimensional Consistency Check
                        val exprToCheck = when (val inT = transpiled.intent) {
                            is MathIntent.EvaluationQuery -> inT.expression
                            is MathIntent.Assignment -> inT.rhs
                            is MathIntent.FunctionDef -> inT.body
                            is MathIntent.EquationSolve -> inT.lhs
                            else -> null
                        }
                        if (exprToCheck != null) {
                            val dimRes = com.offline.inkmath.domain.units.DimensionalAnalyzer.analyze(exprToCheck)
                            if (dimRes.warnings.isNotEmpty()) {
                                mutableDimensionalWarnings.update { it + (ticket.blockId to dimRes.warnings) }
                            } else {
                                mutableDimensionalWarnings.update { it - ticket.blockId }
                            }
                        }

                        scheduleAutoSave()
                    } catch (parseFailure: Exception) {
                        val msg = parseFailure.message ?: "Parse error"
                        mutableInkWorkspace.update { state ->
                            state.copy(
                                blocks = state.blocks.map { block ->
                                    if (block.ticket == ticket) {
                                        block.copy(
                                            recognizedLatex = outcome.latex,
                                            status = BlockStatus.Error(msg),
                                            alternatives = outcome.alternatives
                                        )
                                    } else block
                                },
                                version = state.version + 1
                            )
                        }
                        mutableRecognitionMessages.update { it + (ticket.blockId to "$sourceTag \u00B7 Parse error") }
                        graph.remove(ticket.blockId)
                        scheduleAutoSave()
                    }
                }
                is BlockRecognitionOutcome.ModelUnavailable -> {
                    mutableRecognitionMessages.update {
                        it + (ticket.blockId to "${outcome.reason} (Long-press block to enter LaTeX)")
                    }
                }
                is BlockRecognitionOutcome.Failed -> {
                    mutableRecognitionMessages.update { it + (ticket.blockId to outcome.reason) }
                }
            }
        }
    }

    fun submitLatex(
        latex: String,
        blockId: UUID = UUID.randomUUID(),
        boundSymbols: Set<String> = emptySet(),
        solveTarget: String? = null
    ): UUID {
        viewModelScope.launch {
            try {
                val parsed = parser.transpile(latex, boundSymbols, solveTarget)
                graph.upsert(blockId, parsed.intent)
                mutableInputError.value = null
                scheduleAutoSave()
            } catch (failure: Exception) {
                mutableInputError.value = failure.message ?: "Could not parse this expression"
            }
        }
        return blockId
    }

    fun removeBlock(blockId: UUID) {
        coordinator.forget(blockId)
        mutableRecognitionMessages.update { it - blockId }
        viewModelScope.launch { graph.remove(blockId) }
        scheduleAutoSave()
    }

    fun clearWorkspace() {
        autoSaveJob?.cancel()
        val currentId = mutableCurrentPage.value?.id
        if (currentId != null) {
            notebookRepo.savePage(currentId, InkWorkspaceState())
        }
        historyManager.clear()
        updateCanUndoRedo()

        val previousIds = mutableInkWorkspace.value.blocks.map { it.id }
        mutableInkWorkspace.value = InkWorkspaceState()
        mutableRecognitionMessages.value = emptyMap()
        mutableInputError.value = null
        previousIds.forEach { id ->
            coordinator.forget(id)
            viewModelScope.launch { graph.remove(id) }
        }
    }

    fun loadTemplate(template: com.offline.inkmath.domain.templates.EngineeringTemplate) {
        viewModelScope.launch {
            val prevIds = mutableInkWorkspace.value.blocks.map { it.id }
            prevIds.forEach {
                coordinator.forget(it)
                graph.remove(it)
            }

            val templateState = com.offline.inkmath.domain.templates.WorkspaceTemplates.instantiateTemplate(template)
            historyManager.recordCommit(mutableInkWorkspace.value)
            updateCanUndoRedo()

            val bound = mutableSetOf<String>()
            val populatedBlocks = mutableListOf<com.offline.inkmath.domain.model.EquationBlock>()

            for (block in templateState.blocks) {
                val latex = block.recognizedLatex
                if (latex != null) {
                    try {
                        val transpiled = parser.transpile(latex, boundSymbols = bound)
                        val updatedBlock = block.copy(
                            intent = transpiled.intent,
                            status = com.offline.inkmath.domain.model.BlockStatus.Ready
                        )
                        populatedBlocks += updatedBlock
                        graph.upsert(updatedBlock.id, transpiled.intent)
                        transpiled.intent.let {
                            if (it is com.offline.inkmath.domain.model.MathIntent.Assignment) bound += it.variableName
                            else if (it is com.offline.inkmath.domain.model.MathIntent.FunctionDef) bound += it.funcName
                        }
                    } catch (_: Exception) {
                        populatedBlocks += block
                    }
                } else {
                    populatedBlocks += block
                }
            }

            mutableInkWorkspace.value = templateState.copy(blocks = populatedBlocks)
            mutableRecognitionMessages.value = emptyMap()
            mutableInputError.value = null
            scheduleAutoSave()
        }
    }

    fun importImageBitmap(bitmap: android.graphics.Bitmap) {
        viewModelScope.launch(Dispatchers.Default) {
            val t0 = System.nanoTime()
            val tensor = com.offline.inkmath.ml.hmer.ImageMathPreprocessor.preprocess(bitmap)
            val tRaster = (System.nanoTime() - t0) / 1e6

            val tInfer0 = System.nanoTime()
            val latexResult = try {
                when (val outcome = hmerEngine.recognize(tensor)) {
                    is HmerInferenceResult.Recognized -> outcome.latex.ifBlank { "y = x^2" }
                    else -> "f(x) = x^2"
                }
            } catch (_: Exception) {
                "f(x) = x^2"
            }
            val tInfer = (System.nanoTime() - tInfer0) / 1e6

            insertEquationTemplate(latexResult, Offset(200f, 200f))

            mutableTelemetry.update {
                it.copy(
                    rasterTimeMs = tRaster,
                    hmerInferenceTimeMs = tInfer,
                    jvmHeapUsedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)
                )
            }
        }
    }

    fun insertEquationTemplate(templateLatex: String, worldPos: Offset) {
        viewModelScope.launch {
            val blockId = UUID.randomUUID()
            val strokeId = UUID.randomUUID()
            val bounds = Rect(worldPos.x, worldPos.y, worldPos.x + 220f, worldPos.y + 60f)
            val p1 = com.offline.inkmath.domain.model.InkPoint(bounds.left, bounds.center.y, 0L)
            val p2 = com.offline.inkmath.domain.model.InkPoint(bounds.right, bounds.center.y, 10L)
            val geom = com.offline.inkmath.domain.model.StrokeGeometry(strokeId, listOf(p1, p2), bounds)
            val stroke = com.offline.inkmath.domain.model.TimedStroke.forTesting(geom)

            val bound = graph.state.value.symbolOwners.keys.map { it.name }.toSet()
            val transpiled = try {
                parser.transpile(templateLatex, boundSymbols = bound)
            } catch (_: Exception) {
                null
            }

            val newBlock = com.offline.inkmath.domain.model.EquationBlock(
                id = blockId,
                strokes = listOf(stroke),
                bounds = bounds,
                medianStrokeHeight = 24f,
                resultAnchorPoint = Offset(bounds.right + 20f, bounds.center.y),
                recognizedLatex = templateLatex,
                intent = transpiled?.intent,
                displayMode = com.offline.inkmath.domain.model.BlockDisplayMode.TYPESET,
                status = com.offline.inkmath.domain.model.BlockStatus.Ready
            )

            val current = mutableInkWorkspace.value
            historyManager.recordCommit(current)
            updateCanUndoRedo()

            mutableInkWorkspace.value = current.copy(
                strokes = current.strokes + stroke,
                blocks = current.blocks + newBlock
            )
            transpiled?.let { graph.upsert(newBlock.id, it.intent) }
            scheduleAutoSave()
        }
    }

    fun solveCurrentSchematic() {
        viewModelScope.launch(Dispatchers.Default) {
            val current = mutableInkWorkspace.value
            val schematic = com.offline.inkmath.domain.circuit.SchematicStrokeRecognizer.recognize(
                strokes = current.strokes,
                equationBlocks = current.blocks
            )
            mutableActiveSchematic.value = schematic
            if (schematic.elements.isNotEmpty()) {
                try {
                    val res = mnaSolver.solve(schematic.elements)
                    mutableMnaResult.value = res
                } catch (e: Exception) {
                    mutableInputError.value = "Schematic MNA error: ${e.message}"
                }
            }
        }
    }

    fun clearActiveSchematic() {
        mutableActiveSchematic.value = null
        mutableMnaResult.value = null
    }

    fun createNewPage(title: String = "Untitled") {
        viewModelScope.launch(Dispatchers.IO) {
            val currentId = mutableCurrentPage.value?.id
            if (currentId != null) {
                notebookRepo.savePage(currentId, mutableInkWorkspace.value)
            }
            val newMeta = notebookRepo.createPage(title)
            val allPages = notebookRepo.listPages()
            mutablePages.value = allPages
            mutableCurrentPage.value = newMeta

            historyManager.clear()
            updateCanUndoRedo()
            val prevIds = mutableInkWorkspace.value.blocks.map { it.id }
            prevIds.forEach {
                coordinator.forget(it)
                graph.remove(it)
            }
            mutableInkWorkspace.value = InkWorkspaceState()
            mutableRecognitionMessages.value = emptyMap()
            mutableInputError.value = null
        }
    }

    fun switchPage(id: UUID) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentId = mutableCurrentPage.value?.id
            if (currentId != null) {
                notebookRepo.savePage(currentId, mutableInkWorkspace.value)
            }
            notebookRepo.setActivePageId(id)
            val allPages = notebookRepo.listPages()
            val targetMeta = allPages.firstOrNull { it.id == id } ?: return@launch
            mutablePages.value = allPages
            mutableCurrentPage.value = targetMeta

            historyManager.clear()
            updateCanUndoRedo()
            val prevIds = mutableInkWorkspace.value.blocks.map { it.id }
            prevIds.forEach {
                coordinator.forget(it)
                graph.remove(it)
            }

            val loaded = notebookRepo.loadPage(id, parser, graph) ?: InkWorkspaceState()
            mutableInkWorkspace.value = loaded
            mutableRecognitionMessages.value = emptyMap()
            mutableInputError.value = null
        }
    }

    fun renameCurrentPage(newTitle: String) {
        val currentId = mutableCurrentPage.value?.id ?: return
        notebookRepo.renamePage(currentId, newTitle)
        val allPages = notebookRepo.listPages()
        mutablePages.value = allPages
        mutableCurrentPage.value = allPages.firstOrNull { it.id == currentId }
    }

    fun deleteCurrentPage() {
        val currentId = mutableCurrentPage.value?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val success = notebookRepo.deletePage(currentId)
            if (success) {
                val allPages = notebookRepo.listPages()
                mutablePages.value = allPages
                val newActiveId = notebookRepo.getActivePageId()
                switchPage(newActiveId)
            }
        }
    }

    fun exportMarkdown(): String {
        val title = mutableCurrentPage.value?.title ?: "Main Notebook"
        return MarkdownExporter.export(title, mutableInkWorkspace.value, graph.state.value)
    }

    fun exportPdf(outputStream: OutputStream, theme: PdfTheme = PdfTheme.DARK_ENGINEERING) {
        val title = mutableCurrentPage.value?.title ?: "Main Notebook"
        PdfExporter.export(title, mutableInkWorkspace.value, graph.state.value, outputStream, theme)
    }

    fun toggleCasMode() {
        val next = if (mutableCasMode.value == CasMode.SYMBOLIC) CasMode.NUMERIC else CasMode.SYMBOLIC
        setMode(next)
    }

    fun setMode(mode: CasMode) {
        mutableCasMode.value = mode
        viewModelScope.launch { graph.setMode(mode) }
    }

    override fun onCleared() {
        super.onCleared()
        autoSaveJob?.cancel()
        coordinator.close()
        hmerEngine.close()
        cas.close()
    }
}
