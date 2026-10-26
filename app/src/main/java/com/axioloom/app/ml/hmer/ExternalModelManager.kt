package com.axioloom.app.ml.hmer

import android.content.Context
import java.io.File
import java.io.InputStream

enum class ActiveModelSource {
    BUNDLED,
    CUSTOM
}

data class ExternalModelStatus(
    val hasCustomModel: Boolean,
    val activeSource: ActiveModelSource,
    val customModelPath: String? = null,
    val customVocabCount: Int = 0
)

/**
 * Manages importing and validating external offline HMER ONNX models and vocabulary files
 * directly from local storage, without requiring an internet connection or app reinstallation.
 */
class ExternalModelManager(private val context: Context) {

    private val customDir = File(context.filesDir, "custom_hmer").apply { mkdirs() }
    val customModelFile = File(customDir, "custom_hmer.onnx")
    val customEncoderFile = File(customDir, "custom_encoder.onnx")
    val customDecoderFile = File(customDir, "custom_decoder.onnx")
    val customVocabFile = File(customDir, "custom_vocab.txt")

    fun hasCustomModel(): Boolean {
        val hasVocab = customVocabFile.exists() && customVocabFile.length() > 0
        val hasSingle = customModelFile.exists() && customModelFile.length() > 0
        val hasEncDec = customEncoderFile.exists() && customEncoderFile.length() > 0 &&
                        customDecoderFile.exists() && customDecoderFile.length() > 0
        return hasVocab && (hasSingle || hasEncDec)
    }

    fun isEncoderDecoder(): Boolean {
        return customEncoderFile.exists() && customEncoderFile.length() > 0 &&
               customDecoderFile.exists() && customDecoderFile.length() > 0
    }

    fun getStatus(): ExternalModelStatus {
        val hasCustom = hasCustomModel()
        val vocabCount = if (hasCustom) {
            runCatching { customVocabFile.readLines().size }.getOrDefault(0)
        } else 0

        val modelPath = when {
            isEncoderDecoder() -> "${customEncoderFile.name} + ${customDecoderFile.name}"
            customModelFile.exists() -> customModelFile.absolutePath
            else -> null
        }

        return ExternalModelStatus(
            hasCustomModel = hasCustom,
            activeSource = if (hasCustom) ActiveModelSource.CUSTOM else ActiveModelSource.BUNDLED,
            customModelPath = modelPath,
            customVocabCount = vocabCount
        )
    }

    fun importCustomModel(modelInput: InputStream, vocabInput: InputStream): Result<ExternalModelStatus> {
        return runCatching {
            val tempModel = File(customDir, "temp_model.onnx")
            val tempVocab = File(customDir, "temp_vocab.txt")

            tempModel.outputStream().use { out -> modelInput.copyTo(out) }
            tempVocab.outputStream().use { out -> vocabInput.copyTo(out) }

            require(tempModel.length() > 1024) { "Custom ONNX model file is suspiciously small or empty" }
            val lines = tempVocab.readLines().filter { it.isNotBlank() }
            require(lines.size >= 10) { "Vocabulary must contain at least 10 tokens, found ${lines.size}" }

            if (customModelFile.exists()) customModelFile.delete()
            if (customEncoderFile.exists()) customEncoderFile.delete()
            if (customDecoderFile.exists()) customDecoderFile.delete()
            if (customVocabFile.exists()) customVocabFile.delete()

            check(tempModel.renameTo(customModelFile)) { "Failed to commit custom ONNX model file" }
            check(tempVocab.renameTo(customVocabFile)) { "Failed to commit custom vocabulary file" }

            getStatus()
        }
    }

    fun importCustomEncoderDecoder(
        encoderInput: InputStream,
        decoderInput: InputStream,
        vocabInput: InputStream
    ): Result<ExternalModelStatus> {
        return runCatching {
            val tempEnc = File(customDir, "temp_enc.onnx")
            val tempDec = File(customDir, "temp_dec.onnx")
            val tempVocab = File(customDir, "temp_vocab.txt")

            tempEnc.outputStream().use { out -> encoderInput.copyTo(out) }
            tempDec.outputStream().use { out -> decoderInput.copyTo(out) }
            tempVocab.outputStream().use { out -> vocabInput.copyTo(out) }

            require(tempEnc.length() > 1024 && tempDec.length() > 1024) { "Encoder/Decoder ONNX files are suspiciously small" }
            val lines = tempVocab.readLines().filter { it.isNotBlank() }
            require(lines.size >= 10) { "Vocabulary must contain at least 10 tokens, found ${lines.size}" }

            if (customModelFile.exists()) customModelFile.delete()
            if (customEncoderFile.exists()) customEncoderFile.delete()
            if (customDecoderFile.exists()) customDecoderFile.delete()
            if (customVocabFile.exists()) customVocabFile.delete()

            check(tempEnc.renameTo(customEncoderFile)) { "Failed to commit custom encoder file" }
            check(tempDec.renameTo(customDecoderFile)) { "Failed to commit custom decoder file" }
            check(tempVocab.renameTo(customVocabFile)) { "Failed to commit custom vocabulary file" }

            getStatus()
        }
    }

    fun getValidCustomModelFile(): File? = if (customModelFile.exists() && customModelFile.length() > 0) customModelFile else null

    fun getValidCustomVocabFile(): File? = if (customVocabFile.exists() && customVocabFile.length() > 0) customVocabFile else null

    fun clearCustomModel(): Boolean {
        var success = true
        if (customModelFile.exists()) success = success && customModelFile.delete()
        if (customEncoderFile.exists()) success = success && customEncoderFile.delete()
        if (customDecoderFile.exists()) success = success && customDecoderFile.delete()
        if (customVocabFile.exists()) success = success && customVocabFile.delete()
        return success
    }
}
