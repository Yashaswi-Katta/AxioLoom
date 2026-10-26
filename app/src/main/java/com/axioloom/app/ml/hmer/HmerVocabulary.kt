package com.axioloom.app.ml.hmer

import android.content.Context
import android.content.res.AssetManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Collections

/**
 * Stable token-id mapping for one exported HMER model. The asset format is one exact token per
 * UTF-8 line; line number is the token id. Keep this file paired with the model checkpoint.
 */
class HmerVocabulary private constructor(tokens: List<String>) {
    private val orderedTokens: List<String> = Collections.unmodifiableList(ArrayList(tokens))
    private val tokenIds: Map<String, Int> = orderedTokens.withIndex().associate { it.value to it.index }

    val size: Int get() = orderedTokens.size
    val startId: Int = tokenIds[START_TOKEN] ?: error("Vocabulary is missing $START_TOKEN")
    val endId: Int = tokenIds[END_TOKEN] ?: error("Vocabulary is missing $END_TOKEN")
    val paddingId: Int? = tokenIds[PADDING_TOKEN]
    val unknownId: Int? = tokenIds[UNKNOWN_TOKEN]

    fun idFor(token: String): Int? = tokenIds[token] ?: unknownId
    fun exactIdFor(token: String): Int? = tokenIds[token]
    fun tokenFor(id: Int): String = orderedTokens.getOrNull(id) ?: UNKNOWN_TOKEN

    /** Decodes model ids to presentation LaTeX, omitting control tokens and rendering unknowns. */
    fun decode(ids: Iterable<Int>): String = buildString {
        var prevToken: String? = null
        ids.forEach { id ->
            val token = when (id) {
                startId, endId -> null
                paddingId -> null
                unknownId -> "\\square"
                else -> orderedTokens.getOrNull(id) ?: "\\square"
            }
            if (token != null) {
                val prev = prevToken
                if (prev != null && prev.startsWith('\\') && prev.last().isLetter() && token.first().isLetter()) {
                    append(' ')
                }
                append(token)
                prevToken = token
            }
        }
    }.trim()

    companion object {
        const val START_TOKEN = "<sos>"
        const val END_TOKEN = "<eos>"
        const val PADDING_TOKEN = "<pad>"
        const val UNKNOWN_TOKEN = "<unk>"

        fun fromLines(lines: Iterable<String>): HmerVocabulary {
            val tokens = lines.mapIndexed { index, raw ->
                val token = if (index == 0) raw.removePrefix("\uFEFF") else raw
                val normalized = token.removeSuffix("\r")
                require(normalized.isNotEmpty()) { "Vocabulary line ${index + 1} is empty; token ids are line based" }
                normalized
            }
            require(tokens.isNotEmpty()) { "Vocabulary is empty" }
            require(tokens.distinct().size == tokens.size) { "Vocabulary contains duplicate tokens" }
            require(START_TOKEN in tokens && END_TOKEN in tokens) {
                "Vocabulary must contain $START_TOKEN and $END_TOKEN"
            }
            return HmerVocabulary(tokens)
        }

        fun load(context: Context, assetPath: String = "hmer/hmer_vocab.txt"): HmerVocabulary =
            load(context.assets, assetPath)

        fun load(assets: AssetManager, assetPath: String = "hmer/hmer_vocab.txt"): HmerVocabulary {
            requireSafeAssetPath(assetPath)
            assets.open(assetPath).use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                    return fromLines(reader.readLines())
                }
            }
        }

        private fun requireSafeAssetPath(path: String) {
            require(path.isNotBlank() && !path.startsWith('/') && path.split('/').none { it == ".." }) {
                "Asset path must be a relative path inside the APK"
            }
        }
    }
}
