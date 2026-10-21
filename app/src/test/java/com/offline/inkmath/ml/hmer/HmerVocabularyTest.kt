package com.offline.inkmath.ml.hmer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HmerVocabularyTest {
    @Test
    fun vocabularyUsesLineNumbersAndDecodesLatexTokens() {
        val vocabulary = HmerVocabulary.fromLines(
            listOf("<pad>", "<sos>", "<eos>", "<unk>", "\\frac", "{", "1", "}")
        )

        assertEquals(8, vocabulary.size)
        assertEquals(1, vocabulary.startId)
        assertEquals(2, vocabulary.endId)
        assertEquals(6, vocabulary.idFor("1"))
        assertEquals(3, vocabulary.idFor("not-in-vocab"))
        assertEquals("\\frac{1}", vocabulary.decode(listOf(1, 4, 5, 6, 7, 2, 0)))
        assertEquals("\\square", vocabulary.decode(listOf(3)))
    }

    @Test
    fun vocabularyRejectsDuplicateOrMissingControlTokens() {
        assertThrows(IllegalArgumentException::class.java) {
            HmerVocabulary.fromLines(listOf("<sos>", "<sos>", "<eos>"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HmerVocabulary.fromLines(listOf("<pad>", "x"))
        }
    }
}

class OnnxGreedyDecodingTest {
    @Test
    fun selectsMaximumFromFinalSequencePosition() {
        val logits = floatArrayOf(
            100f, 0f, 0f, 0f,
            Float.NaN, 3f, -1f, 2f
        )
        assertEquals(1, selectGreedyToken(logits, longArrayOf(1, 2, 4), vocabularySize = 4))
    }

    @Test
    fun rejectsLogitsWhoseVocabularyDimensionDoesNotMatch() {
        assertThrows(IllegalArgumentException::class.java) {
            selectGreedyToken(floatArrayOf(1f, 2f, 3f), longArrayOf(1, 3), vocabularySize = 4)
        }
    }
}
