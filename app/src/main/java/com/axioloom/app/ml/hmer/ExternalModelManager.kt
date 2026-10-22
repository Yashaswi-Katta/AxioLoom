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
    private val customModelFile = File(customDir, "custom_hmer.onnx")
    private val customVocabFile = File(customDir, "custom_vocab.txt")

    fun hasCustomModel(): Boolean {
        return customModelFile.exists() && customModelFile.length() > 0 &&
               customVocabFile.exists() && customVocabFile.length() > 0
    }

    fun getStatus(): ExternalModelStatus {
        val hasCustom = hasCustomModel()
        val vocabCount = if (hasCustom) {
            runCatching { customVocabFile.readLines().size }.getOrDefault(0)
        } else 0

        return ExternalModelStatus(
            hasCustomModel = hasCustom,
            activeSource = if (hasCustom) ActiveModelSource.CUSTOM else ActiveModelSource.BUNDLED,
            customModelPath = if (hasCustom) customModelFile.absolutePath else null,
            customVocabCount = vocabCount
        )
    }

    /**
     * Imports a custom ONNX model and vocabulary stream, validating their integrity.
     */
    fun importCustomModel(modelInput: InputStream, vocabInput: InputStream): Result<ExternalModelStatus> {
        return runCatching {
            val tempModel = File(customDir, "temp_model.onnx")
            val tempVocab = File(customDir, "temp_vocab.txt")

            tempModel.outputStream().use { out -> modelInput.copyTo(out) }
            tempVocab.outputStream().use { out -> vocabInput.copyTo(out) }

            // Validate non-empty model (> 1 KB)
            require(tempModel.length() > 1024) { "Custom ONNX model file is suspiciously small or empty" }

            // Validate vocabulary has meaningful lines (> 10 tokens)
            val lines = tempVocab.readLines().filter { it.isNotBlank() }
            require(lines.size >= 10) { "Vocabulary must contain at least 10 tokens, found ${lines.size}" }

            // Atomic rename
            if (customModelFile.exists()) customModelFile.delete()
            if (customVocabFile.exists()) customVocabFile.delete()

            check(tempModel.renameTo(customModelFile)) { "Failed to commit custom ONNX model file" }
            check(tempVocab.renameTo(customVocabFile)) { "Failed to commit custom vocabulary file" }

            getStatus()
        }
    }

    fun getCustomModelFile(): File? = if (hasCustomModel()) customModelFile else null

    fun getCustomVocabFile(): File? = if (hasCustomModel()) customVocabFile else null

    fun clearCustomModel(): Boolean {
        var success = true
        if (customModelFile.exists()) success = success && customModelFile.delete()
        if (customVocabFile.exists()) success = success && customVocabFile.delete()
        return success
    }
}
