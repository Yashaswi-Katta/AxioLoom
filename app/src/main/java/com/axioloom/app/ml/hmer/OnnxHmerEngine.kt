package com.axioloom.app.ml.hmer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Named I/O contract for the model export. The engine does not guess architecture-specific names. */
sealed class HmerModelConfig {
    abstract val maxSequenceLength: Int
    abstract val kind: HmerModelKind

    data class SingleGraph(
        val modelAsset: String = "hmer/hmer.onnx",
        val imageInput: String = "input_image",
        val maskInput: String? = "image_mask",
        val tokenInput: String = "tokens",
        val logitsOutput: String = "logits",
        val imageMaskValue: Float = 0f,
        override val maxSequenceLength: Int = DEFAULT_MAX_SEQUENCE_LENGTH
    ) : HmerModelConfig() {
        override val kind: HmerModelKind = HmerModelKind.SINGLE_GRAPH
        init { require(maxSequenceLength > 0 && imageMaskValue.isFinite()) }
    }

    /**
     * Split-export contract: encoder emits a concrete NCHW feature_map; decoder consumes it, the
     * complete Int64 token prefix, and float coverage [1,1,Hfeature,Wfeature], then emits one-step
     * logits and updated coverage of that same shape. CoMER/CAN exports may need an export adapter.
     */
    data class EncoderDecoder(
        val encoderAsset: String = "hmer/hmer_encoder.onnx",
        val decoderAsset: String = "hmer/hmer_decoder.onnx",
        val imageInput: String = "input_image",
        val encoderMaskInput: String? = "image_mask",
        val featureOutput: String = "feature_map",
        val decoderFeatureInput: String = "feature_map",
        val decoderTokenInput: String = "tokens",
        val decoderCoverageInput: String = "coverage_past",
        val decoderLogitsOutput: String = "logits",
        val decoderCoverageOutput: String = "coverage",
        val imageMaskValue: Float = 0f,
        override val maxSequenceLength: Int = DEFAULT_MAX_SEQUENCE_LENGTH
    ) : HmerModelConfig() {
        override val kind: HmerModelKind = HmerModelKind.ENCODER_DECODER
        init { require(maxSequenceLength > 0 && imageMaskValue.isFinite()) }
    }

    companion object {
        const val DEFAULT_MAX_SEQUENCE_LENGTH = 128

        /** Detect only the documented asset names; a vocabulary is still required separately. */
        fun detect(assets: AssetManager): HmerModelConfig? = when {
            assetExists(assets, "hmer/hmer_encoder.onnx") && assetExists(assets, "hmer/hmer_decoder.onnx") -> EncoderDecoder()
            assetExists(assets, "hmer/hmer.onnx") -> SingleGraph()
            else -> null
        }

        private fun assetExists(assets: AssetManager, path: String): Boolean = try {
            assets.open(path).use { true }
        } catch (_: IOException) {
            false
        }
    }
}

enum class HmerModelKind { SINGLE_GRAPH, ENCODER_DECODER }

sealed class HmerAvailability {
    data class Ready(val kind: HmerModelKind) : HmerAvailability()
    data class Unavailable(val reason: String) : HmerAvailability()
}

sealed class HmerInferenceResult {
    data class Recognized(
        val latex: String,
        val tokenIds: List<Int>,
        val reachedEndToken: Boolean,
        val alternatives: List<String> = emptyList()
    ) : HmerInferenceResult()
    data class Unavailable(val reason: String) : HmerInferenceResult()
    data class Failed(val reason: String) : HmerInferenceResult()
}

/**
 * Offline ONNX Runtime Mobile inference. Model and vocabulary assets are optional at build time;
 * missing or incompatible assets yield an explicit Unavailable/Failed result, never fake OCR.
 * Initialization and inference run on Dispatchers.Default; calls are serialized per engine.
 */
class OnnxHmerEngine(
    context: Context,
    private val vocabularyOverride: HmerVocabulary? = null,
    private val configuredModel: HmerModelConfig? = HmerModelConfig.detect(context.applicationContext.assets),
    private val vocabularyAsset: String = "hmer/hmer_vocab.txt",
    val decodingStrategy: DecodingStrategy = DecodingStrategy.BEAM_3
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()
    @Volatile private var loaded: LoadedModel? = null
    @Volatile private var closed = false

    fun reload(): HmerAvailability {
        synchronized(lock) {
            loaded?.close()
            loaded = null
            closed = false
        }
        return kotlinx.coroutines.runBlocking { open() }
    }

    suspend fun open(): HmerAvailability = withContext(Dispatchers.Default) {
        synchronized(lock) {
            if (closed) return@synchronized HmerAvailability.Unavailable("HMER engine is closed")
            loaded?.let { return@synchronized HmerAvailability.Ready(it.config.kind) }

            val externalManager = ExternalModelManager(appContext)
            if (externalManager.hasCustomModel()) {
                val customVocab = try {
                    externalManager.getValidCustomVocabFile()?.let { HmerVocabulary.fromLines(it.readLines()) }
                } catch (_: Exception) { null }
                if (customVocab != null) {
                    try {
                        val sessionOptions = OrtSession.SessionOptions().apply {
                            setIntraOpNumThreads(INTRA_OP_THREADS)
                            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        }
                        val env = OrtEnvironment.getEnvironment()
                        if (externalManager.isEncoderDecoder()) {
                            val enc = env.createSession(externalManager.customEncoderFile.absolutePath, sessionOptions)
                            val dec = env.createSession(externalManager.customDecoderFile.absolutePath, sessionOptions)
                            val cfg = HmerModelConfig.EncoderDecoder(
                                encoderAsset = externalManager.customEncoderFile.absolutePath,
                                decoderAsset = externalManager.customDecoderFile.absolutePath,
                                imageInput = "input_image",
                                encoderMaskInput = "image_mask",
                                featureOutput = "feature_map",
                                decoderFeatureInput = "feature_map",
                                decoderTokenInput = "tokens",
                                decoderCoverageInput = "coverage_past",
                                decoderLogitsOutput = "logits",
                                decoderCoverageOutput = "coverage",
                                maxSequenceLength = 128
                            )
                            loaded = LoadedModel(cfg, customVocab, encoder = enc, decoder = dec)
                            return@synchronized HmerAvailability.Ready(HmerModelKind.ENCODER_DECODER)
                        } else if (externalManager.customModelFile.exists()) {
                            val single = env.createSession(externalManager.customModelFile.absolutePath, sessionOptions)
                            val cfg = HmerModelConfig.SingleGraph(
                                modelAsset = externalManager.customModelFile.absolutePath,
                                imageInput = "input_image",
                                maskInput = "image_mask",
                                tokenInput = "tokens",
                                logitsOutput = "logits",
                                maxSequenceLength = 128
                            )
                            loaded = LoadedModel(cfg, customVocab, single = single)
                            return@synchronized HmerAvailability.Ready(HmerModelKind.SINGLE_GRAPH)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("OnnxHmerEngine", "Failed to load custom model from disk, falling back to assets", e)
                    }
                }
            }
            val config = configuredModel ?: return@synchronized HmerAvailability.Unavailable(
                "No HMER ONNX asset found. Bundle hmer/hmer.onnx or both encoder/decoder assets."
            )
            val vocabulary = vocabularyOverride ?: try {
                HmerVocabulary.load(appContext, vocabularyAsset)
            } catch (failure: Exception) {
                return@synchronized HmerAvailability.Unavailable(
                    "HMER vocabulary is unavailable or invalid at '$vocabularyAsset': ${failure.message ?: failure.javaClass.simpleName}"
                )
            }
            try {
                loaded = loadModel(config, vocabulary)
                HmerAvailability.Ready(config.kind)
            } catch (failure: Exception) {
                HmerAvailability.Unavailable(
                    "Could not initialize ${config.kind} model: ${failure.message ?: failure.javaClass.simpleName}"
                )
            }
        }
    }

    suspend fun recognize(raster: RasterizedTensor): HmerInferenceResult {
        when (val availability = open()) {
            is HmerAvailability.Unavailable -> return HmerInferenceResult.Unavailable(availability.reason)
            is HmerAvailability.Ready -> Unit
        }
        return withContext(Dispatchers.Default) {
            synchronized(lock) {
                val model = loaded ?: return@synchronized HmerInferenceResult.Unavailable("HMER model is not initialized")
                if (closed) return@synchronized HmerInferenceResult.Unavailable("HMER engine is closed")
                try {
                    val result = when (val config = model.config) {
                        is HmerModelConfig.SingleGraph -> runSingleGraph(model, config, raster)
                        is HmerModelConfig.EncoderDecoder -> runEncoderDecoder(model, config, raster)
                    }
                    if (result.latex.isBlank()) HmerInferenceResult.Failed("The model returned no LaTeX tokens") else result
                } catch (failure: Exception) {
                    HmerInferenceResult.Failed("HMER inference failed: ${failure.message ?: failure.javaClass.simpleName}")
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            loaded?.close()
            loaded = null
        }
    }



    fun isCustomModelActive(): Boolean = synchronized(lock) {
        val loadedEngine = loaded ?: return false
        val asset = when (val cfg = loadedEngine.config) {
            is HmerModelConfig.SingleGraph -> cfg.modelAsset
            is HmerModelConfig.EncoderDecoder -> cfg.encoderAsset
        }
        asset.startsWith("/") || asset.contains("custom_models")
    }

    private fun isSyntheticSession(session: OrtSession): Boolean {
        return runCatching {
            val meta = session.metadata
            meta.producerName == "AxioLoom-Synthetic-Exporter" ||
                meta.customMetadata["synthetic"] == "true"
        }.getOrDefault(false)
    }

    private fun loadModel(config: HmerModelConfig, vocabulary: HmerVocabulary): LoadedModel {
        val sessions = mutableListOf<OrtSession>()
        try {
            return when (config) {
                is HmerModelConfig.SingleGraph -> {
                    val session = createSession(config.modelAsset).also { sessions += it }
                    if (isSyntheticSession(session)) {
                        throw IllegalStateException("Synthetic verification stub ignored in interactive workspace")
                    }
                    requireInputs(session, listOfNotNull(config.imageInput, config.maskInput, config.tokenInput))
                    requireOutputs(session, listOf(config.logitsOutput))
                    LoadedModel(config, vocabulary, single = session)
                }
                is HmerModelConfig.EncoderDecoder -> {
                    val encoder = createSession(config.encoderAsset).also { sessions += it }
                    val decoder = createSession(config.decoderAsset).also { sessions += it }
                    if (isSyntheticSession(encoder) || isSyntheticSession(decoder)) {
                        throw IllegalStateException("Synthetic verification stub ignored in interactive workspace")
                    }
                    requireInputs(encoder, listOfNotNull(config.imageInput, config.encoderMaskInput))
                    requireOutputs(encoder, listOf(config.featureOutput))
                    requireInputs(decoder, listOf(config.decoderFeatureInput, config.decoderTokenInput, config.decoderCoverageInput))
                    requireOutputs(decoder, listOf(config.decoderLogitsOutput, config.decoderCoverageOutput))
                    LoadedModel(config, vocabulary, encoder = encoder, decoder = decoder)
                }
            }
        } catch (failure: Exception) {
            sessions.forEach { runCatching { it.close() } }
            throw failure
        }
    }

    private fun createSession(assetPath: String): OrtSession {
        requireSafeAssetPath(assetPath)
        val modelPath = materializeAsset(assetPath)
        var xnnFailure: Exception? = null
        try {
            return createSession(modelPath, enableXnnpack = true)
        } catch (failure: Exception) {
            xnnFailure = failure
        }
        return try {
            createSession(modelPath, enableXnnpack = false)
        } catch (cpuFailure: Exception) {
            xnnFailure?.let(cpuFailure::addSuppressed)
            throw cpuFailure
        }
    }

    private fun createSession(modelPath: String, enableXnnpack: Boolean): OrtSession {
        val options = OrtSession.SessionOptions()
        try {
            options.setIntraOpNumThreads(INTRA_OP_THREADS)
            if (enableXnnpack) options.addXnnpack(emptyMap<String, String>())
            return OrtEnvironment.getEnvironment().createSession(modelPath, options)
        } finally {
            options.close()
        }
    }

    private fun materializeAsset(assetPath: String): String {
        val appVersionCode = runCatching {
            val pInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(appContext.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            }
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pInfo)
        }.getOrDefault(1L)
        val directory = File(appContext.cacheDir, "axioloom_hmer_models_v$appVersionCode")
        if (!directory.exists()) {
            directory.mkdirs()
            appContext.cacheDir.listFiles()?.filter {
                it.isDirectory && it.name.startsWith("axioloom_hmer_models") && it.name != directory.name
            }?.forEach { it.deleteRecursively() }
        }
        if (!directory.exists()) throw IOException("Cannot create private model cache")
        val destination = File(directory, assetPath.replace('/', '_'))
        val assetFd = runCatching { appContext.assets.openFd(assetPath) }.getOrNull()
        val assetLength = assetFd?.length ?: -1L
        assetFd?.close()
        val needsMaterialize = !destination.isFile || destination.length() == 0L || (assetLength > 0L && destination.length() != assetLength)
        if (needsMaterialize) {
            destination.delete()
            val temporary = File(directory, destination.name + ".tmp")
            try {
                appContext.assets.open(assetPath).use { input -> temporary.outputStream().use(input::copyTo) }
                if (!temporary.renameTo(destination)) throw IOException("Cannot finalize cached model asset '$assetPath'")
            } finally {
                temporary.delete()
            }
        }
        return destination.absolutePath
    }

    private fun requireInputs(session: OrtSession, required: List<String>) {
        val actual = session.inputInfo.keys
        val missing = required.filterNot(actual::contains)
        require(missing.isEmpty()) { "Model inputs do not match configured contract; missing $missing, found $actual" }
    }

    private fun requireOutputs(session: OrtSession, required: List<String>) {
        val actual = session.outputInfo.keys
        val missing = required.filterNot(actual::contains)
        require(missing.isEmpty()) { "Model outputs do not match configured contract; missing $missing, found $actual" }
    }

    private fun runSingleGraph(
        model: LoadedModel,
        config: HmerModelConfig.SingleGraph,
        raster: RasterizedTensor
    ): HmerInferenceResult.Recognized {
        val session = checkNotNull(model.single)
        val image = imageTensor(raster)
        val mask = config.maskInput?.let { floatTensor(raster.buildMaskArray(config.imageMaskValue, 1f), raster.shape()) }
        try {
            if (decodingStrategy == DecodingStrategy.GREEDY) {
                val generated = mutableListOf(model.vocabulary.startId)
                var reachedEnd = false
                var stepIndex = 0
                while (stepIndex < config.maxSequenceLength && !reachedEnd) {
                    val tokenInput = tokenTensor(generated)
                    val step = try {
                        val inputs = HashMap<String, OnnxTensorLike>()
                        inputs[config.imageInput] = image
                        if (mask != null && config.maskInput != null) inputs[config.maskInput] = mask
                        inputs[config.tokenInput] = tokenInput
                        session.run(inputs).use { output ->
                            val logits = readFloatTensor(output.get(config.logitsOutput).orElseThrow {
                                IllegalStateException("Missing output '${config.logitsOutput}'")
                            })
                            selectGreedyToken(logits.first, logits.second, model.vocabulary.size)
                        }
                    } finally {
                        tokenInput.close()
                    }
                    if (step == model.vocabulary.endId) reachedEnd = true else generated += step
                    stepIndex++
                }
                return HmerInferenceResult.Recognized(model.vocabulary.decode(generated), generated.drop(1).toList(), reachedEnd)
            } else {
                val beamDecoder = ConstrainedBeamDecoder(model.vocabulary, beamWidth = 3)
                var beams = listOf(BeamHypothesis(tokens = listOf(model.vocabulary.startId), logProb = 0.0))
                var stepIndex = 0
                while (stepIndex < config.maxSequenceLength && beams.any { !it.isEnded }) {
                    val activeBeams = beams.filter { !it.isEnded }
                    val logitsList = mutableListOf<FloatArray>()
                    for (hyp in activeBeams) {
                        val tokenInput = tokenTensor(hyp.tokens)
                        val lastStepLogits = try {
                            val inputs = HashMap<String, OnnxTensorLike>()
                            inputs[config.imageInput] = image
                            if (mask != null && config.maskInput != null) inputs[config.maskInput] = mask
                            inputs[config.tokenInput] = tokenInput
                            session.run(inputs).use { output ->
                                val logits = readFloatTensor(output.get(config.logitsOutput).orElseThrow {
                                    IllegalStateException("Missing output '${config.logitsOutput}'")
                                })
                                extractFinalStepLogits(logits.first, logits.second, model.vocabulary.size)
                            }
                        } finally {
                            tokenInput.close()
                        }
                        logitsList.add(lastStepLogits)
                    }
                    beams = beamDecoder.step(activeBeams, logitsList, stepIndex, config.maxSequenceLength)
                    beams = beams.sortedByDescending { it.lengthPenalizedScore() }.distinctBy { it.tokens }.take(3)
                    stepIndex++
                }
                val best = beamDecoder.bestHypothesis(beams)
                val bestLatex = model.vocabulary.decode(best.tokens)
                val alternatives = beamDecoder.topHypotheses(beams, 4)
                    .map { model.vocabulary.decode(it.tokens) }
                    .distinct()
                    .filter { it.isNotBlank() && it != bestLatex }
                    .take(3)
                return HmerInferenceResult.Recognized(bestLatex, best.tokens.drop(1), best.isEnded, alternatives)
            }
        } finally {
            image.close()
            mask?.close()
        }
    }

    private fun runEncoderDecoder(
        model: LoadedModel,
        config: HmerModelConfig.EncoderDecoder,
        raster: RasterizedTensor
    ): HmerInferenceResult.Recognized {
        val encoder = checkNotNull(model.encoder)
        val decoder = checkNotNull(model.decoder)
        val image = imageTensor(raster)
        val encoderMask = config.encoderMaskInput?.let {
            floatTensor(raster.buildMaskArray(config.imageMaskValue, 1f), raster.shape())
        }
        try {
            val encoderInputs = HashMap<String, OnnxTensorLike>()
            encoderInputs[config.imageInput] = image
            if (encoderMask != null && config.encoderMaskInput != null) encoderInputs[config.encoderMaskInput] = encoderMask
            encoder.run(encoderInputs).use { encoded ->
                val feature = outputTensor(encoded.get(config.featureOutput).orElseThrow {
                    IllegalStateException("Missing encoder output '${config.featureOutput}'")
                })
                val featureShape = feature.info.shape
                require(featureShape.size == 4 && featureShape.all { it > 0L }) {
                    "Encoder feature_map must have a concrete NCHW shape; got ${featureShape.contentToString()}"
                }
                val coverageShape = longArrayOf(1L, 1L, featureShape[2], featureShape[3])
                val initialCoverage = FloatArray(checkedElementCount(coverageShape))

                if (decodingStrategy == DecodingStrategy.GREEDY) {
                    val generated = mutableListOf(model.vocabulary.startId)
                    var coverage = initialCoverage
                    var reachedEnd = false
                    var stepIndex = 0
                    while (stepIndex < config.maxSequenceLength && !reachedEnd) {
                        val tokenInput = tokenTensor(generated)
                        val coverageInput = floatTensor(coverage, coverageShape)
                        var nextCoverage: FloatArray? = null
                        val step = try {
                            val inputs = HashMap<String, OnnxTensorLike>()
                            inputs[config.decoderFeatureInput] = feature
                            inputs[config.decoderTokenInput] = tokenInput
                            inputs[config.decoderCoverageInput] = coverageInput
                            decoder.run(inputs).use { decoded ->
                                val logits = readFloatTensor(decoded.get(config.decoderLogitsOutput).orElseThrow {
                                    IllegalStateException("Missing decoder output '${config.decoderLogitsOutput}'")
                                })
                                val updatedCoverage = readFloatTensor(decoded.get(config.decoderCoverageOutput).orElseThrow {
                                    IllegalStateException("Missing decoder output '${config.decoderCoverageOutput}'")
                                })
                                require(updatedCoverage.second.contentEquals(coverageShape)) {
                                    "Decoder coverage shape ${updatedCoverage.second.contentToString()} does not match ${coverageShape.contentToString()}"
                                }
                                nextCoverage = updatedCoverage.first
                                selectGreedyToken(logits.first, logits.second, model.vocabulary.size)
                            }
                        } finally {
                            tokenInput.close()
                            coverageInput.close()
                        }
                        coverage = checkNotNull(nextCoverage)
                        if (step == model.vocabulary.endId) reachedEnd = true else generated += step
                        stepIndex++
                    }
                    return HmerInferenceResult.Recognized(model.vocabulary.decode(generated), generated.drop(1).toList(), reachedEnd)
                } else {
                    val beamDecoder = ConstrainedBeamDecoder(model.vocabulary, beamWidth = 3)
                    var beams = listOf(BeamHypothesis(tokens = listOf(model.vocabulary.startId), logProb = 0.0, coverage = initialCoverage))
                    var stepIndex = 0
                    while (stepIndex < config.maxSequenceLength && beams.any { !it.isEnded }) {
                        val activeBeams = beams.filter { !it.isEnded }
                        val logitsList = mutableListOf<FloatArray>()
                        val coverageList = mutableListOf<FloatArray?>()

                        for (hyp in activeBeams) {
                            val tokenInput = tokenTensor(hyp.tokens)
                            val coverageInput = floatTensor(hyp.coverage ?: initialCoverage, coverageShape)
                            var stepLogits: FloatArray? = null
                            var nextCov: FloatArray? = null
                            try {
                                val inputs = HashMap<String, OnnxTensorLike>()
                                inputs[config.decoderFeatureInput] = feature
                                inputs[config.decoderTokenInput] = tokenInput
                                inputs[config.decoderCoverageInput] = coverageInput
                                decoder.run(inputs).use { decoded ->
                                    val logits = readFloatTensor(decoded.get(config.decoderLogitsOutput).orElseThrow {
                                        IllegalStateException("Missing decoder output '${config.decoderLogitsOutput}'")
                                    })
                                    val updatedCoverage = readFloatTensor(decoded.get(config.decoderCoverageOutput).orElseThrow {
                                        IllegalStateException("Missing decoder output '${config.decoderCoverageOutput}'")
                                    })
                                    stepLogits = extractFinalStepLogits(logits.first, logits.second, model.vocabulary.size)
                                    nextCov = updatedCoverage.first
                                }
                            } finally {
                                tokenInput.close()
                                coverageInput.close()
                            }
                            logitsList.add(checkNotNull(stepLogits))
                            coverageList.add(nextCov)
                        }

                        beams = beamDecoder.step(activeBeams, logitsList, stepIndex, config.maxSequenceLength, coverageList)
                        beams = beams.sortedByDescending { it.lengthPenalizedScore() }.distinctBy { it.tokens }.take(3)
                        stepIndex++
                    }
                    val best = beamDecoder.bestHypothesis(beams)
                    val bestLatex = model.vocabulary.decode(best.tokens)
                    val alternatives = beamDecoder.topHypotheses(beams, 4)
                        .map { model.vocabulary.decode(it.tokens) }
                        .distinct()
                        .filter { it.isNotBlank() && it != bestLatex }
                        .take(3)
                    return HmerInferenceResult.Recognized(bestLatex, best.tokens.drop(1), best.isEnded, alternatives)
                }
            }
        } finally {
            image.close()
            encoderMask?.close()
        }
    }

    private fun imageTensor(raster: RasterizedTensor): OnnxTensor =
        OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), raster.asFloatBuffer(), raster.shape())

    private fun tokenTensor(tokens: List<Int>): OnnxTensor {
        val bytes = ByteBuffer.allocateDirect(tokens.size * java.lang.Long.BYTES).order(ByteOrder.nativeOrder())
        val buffer = bytes.asLongBuffer()
        tokens.forEach { buffer.put(it.toLong()) }
        buffer.rewind()
        return OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), buffer, longArrayOf(1L, tokens.size.toLong()))
    }

    private fun floatTensor(values: FloatArray, shape: LongArray): OnnxTensor {
        require(values.size == checkedElementCount(shape))
        val bytes = ByteBuffer.allocateDirect(values.size * java.lang.Float.BYTES).order(ByteOrder.nativeOrder())
        val buffer = bytes.asFloatBuffer()
        buffer.put(values)
        buffer.rewind()
        return OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), buffer, shape)
    }

    private fun pixelCount(raster: RasterizedTensor): Int = checkedElementCount(raster.shape())

    private fun readFloatTensor(value: OnnxValue): Pair<FloatArray, LongArray> {
        val tensor = outputTensor(value)
        val shape = tensor.info.shape
        val buffer: FloatBuffer = tensor.floatBuffer
        buffer.rewind()
        val values = FloatArray(buffer.remaining())
        buffer.get(values)
        return values to shape
    }

    private fun outputTensor(value: OnnxValue): OnnxTensor =
        value as? OnnxTensor ?: throw IllegalArgumentException("Expected a float ONNX tensor output")

    private fun LoadedModel.close() {
        listOfNotNull(single, encoder, decoder).distinct().forEach { runCatching { it.close() } }
    }

    private data class LoadedModel(
        val config: HmerModelConfig,
        val vocabulary: HmerVocabulary,
        val single: OrtSession? = null,
        val encoder: OrtSession? = null,
        val decoder: OrtSession? = null
    )

    private fun requireSafeAssetPath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && path.split('/').none { it == ".." }) {
            "Model asset path must be relative to the APK assets directory"
        }
    }

    private fun checkedElementCount(shape: LongArray): Int {
        require(shape.isNotEmpty() && shape.all { it > 0L }) { "Tensor shape must be concrete and positive" }
        val count = shape.fold(1L) { product, dimension -> Math.multiplyExact(product, dimension) }
        require(count <= MAX_TENSOR_ELEMENTS) { "Tensor exceeds configured memory budget" }
        return count.toInt()
    }

    private companion object {
        const val INTRA_OP_THREADS = 4
        const val MAX_TENSOR_ELEMENTS = 32_000_000L
    }
}

/** Selects the vocabulary index from the last sequence position of [1,V] or [1,T,V] logits. */
internal fun selectGreedyToken(logits: FloatArray, shape: LongArray, vocabularySize: Int): Int {
    require(vocabularySize > 0 && shape.isNotEmpty())
    require(shape.last() == vocabularySize.toLong()) {
        "Logit vocabulary dimension ${shape.last()} does not match vocabulary size $vocabularySize"
    }
    val expected = shape.fold(1L) { product, dimension ->
        require(dimension > 0L) { "Logit tensor has unresolved dimension ${shape.contentToString()}" }
        Math.multiplyExact(product, dimension)
    }
    require(expected == logits.size.toLong() && logits.size % vocabularySize == 0) {
        "Logit data size ${logits.size} does not match shape ${shape.contentToString()}"
    }
    val offset = logits.size - vocabularySize
    var bestIndex = -1
    var bestScore = Float.NEGATIVE_INFINITY
    for (index in 0 until vocabularySize) {
        val score = logits[offset + index]
        if (score.isFinite() && score > bestScore) {
            bestScore = score
            bestIndex = index
        }
    }
    require(bestIndex >= 0) { "Model returned no finite logits" }
    return bestIndex
}

/** Extracts the vocabulary logits from the last sequence position of [1,V] or [1,T,V] logits with shape checks. */
internal fun extractFinalStepLogits(logits: FloatArray, shape: LongArray, vocabularySize: Int): FloatArray {
    require(vocabularySize > 0 && shape.isNotEmpty())
    require(shape.last() == vocabularySize.toLong()) {
        "Logit vocabulary dimension ${shape.last()} does not match vocabulary size $vocabularySize"
    }
    require(logits.size >= vocabularySize) {
        "Logits array size ${logits.size} is smaller than vocabulary size $vocabularySize"
    }
    val offset = logits.size - vocabularySize
    return logits.copyOfRange(offset, logits.size)
}
