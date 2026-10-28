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
            val stagingDir = File(customDir, "staging_${System.currentTimeMillis()}").apply { mkdirs() }
            val tempModel = File(stagingDir, "model.onnx")
            val tempVocab = File(stagingDir, "vocab.txt")

            try {
                tempModel.outputStream().use { out -> modelInput.copyTo(out) }
                tempVocab.outputStream().use { out -> vocabInput.copyTo(out) }

                require(tempModel.length() > 1024) { "Custom ONNX model file is suspiciously small or empty" }
                val parsedVocab = HmerVocabulary.fromLines(tempVocab.readLines())
                require(parsedVocab.size >= 10) { "Vocabulary must contain at least 10 tokens, found ${parsedVocab.size}" }

                val backupDir = File(customDir, "backup_${System.currentTimeMillis()}").apply { mkdirs() }
                try {
                    if (customModelFile.exists()) customModelFile.renameTo(File(backupDir, customModelFile.name))
                    if (customEncoderFile.exists()) customEncoderFile.renameTo(File(backupDir, customEncoderFile.name))
                    if (customDecoderFile.exists()) customDecoderFile.renameTo(File(backupDir, customDecoderFile.name))
                    if (customVocabFile.exists()) customVocabFile.renameTo(File(backupDir, customVocabFile.name))

                    check(tempModel.renameTo(customModelFile)) { "Failed to commit custom ONNX model file" }
                    check(tempVocab.renameTo(customVocabFile)) { "Failed to commit custom vocabulary file" }

                    backupDir.deleteRecursively()
                } catch (t: Throwable) {
                    backupDir.listFiles()?.forEach { it.renameTo(File(customDir, it.name)) }
                    backupDir.deleteRecursively()
                    throw t
                }
            } finally {
                stagingDir.deleteRecursively()
            }

            getStatus()
        }
    }

    fun importCustomEncoderDecoder(
        encoderInput: InputStream,
        decoderInput: InputStream,
        vocabInput: InputStream
    ): Result<ExternalModelStatus> {
        return runCatching {
            val stagingDir = File(customDir, "staging_${System.currentTimeMillis()}").apply { mkdirs() }
            val tempEnc = File(stagingDir, "enc.onnx")
            val tempDec = File(stagingDir, "dec.onnx")
            val tempVocab = File(stagingDir, "vocab.txt")

            try {
                tempEnc.outputStream().use { out -> encoderInput.copyTo(out) }
                tempDec.outputStream().use { out -> decoderInput.copyTo(out) }
                tempVocab.outputStream().use { out -> vocabInput.copyTo(out) }

                require(tempEnc.length() > 1024 && tempDec.length() > 1024) { "Encoder/Decoder ONNX files are suspiciously small" }
                val parsedVocab = HmerVocabulary.fromLines(tempVocab.readLines())
                require(parsedVocab.size >= 10) { "Vocabulary must contain at least 10 tokens, found ${parsedVocab.size}" }

                val backupDir = File(customDir, "backup_${System.currentTimeMillis()}").apply { mkdirs() }
                try {
                    if (customModelFile.exists()) customModelFile.renameTo(File(backupDir, customModelFile.name))
                    if (customEncoderFile.exists()) customEncoderFile.renameTo(File(backupDir, customEncoderFile.name))
                    if (customDecoderFile.exists()) customDecoderFile.renameTo(File(backupDir, customDecoderFile.name))
                    if (customVocabFile.exists()) customVocabFile.renameTo(File(backupDir, customVocabFile.name))

                    check(tempEnc.renameTo(customEncoderFile)) { "Failed to commit custom encoder file" }
                    check(tempDec.renameTo(customDecoderFile)) { "Failed to commit custom decoder file" }
                    check(tempVocab.renameTo(customVocabFile)) { "Failed to commit custom vocabulary file" }

                    backupDir.deleteRecursively()
                } catch (t: Throwable) {
                    backupDir.listFiles()?.forEach { it.renameTo(File(customDir, it.name)) }
                    backupDir.deleteRecursively()
                    throw t
                }
            } finally {
                stagingDir.deleteRecursively()
            }

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
