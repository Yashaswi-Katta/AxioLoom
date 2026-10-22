package com.axioloom.app.ml.hmer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstrainedBeamDecoderTest {

    private val sampleVocabTokens = listOf(
        "<sos>", "<eos>", "<pad>", "<unk>",
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
        "+", "-", "*", "/", "=",
        "x", "y", "z", "s",
        "(", ")", "{", "}",
        "^", "_",
        "\\frac", "\\sqrt",
        "\\sin", "\\cos", "\\tan",
        "\\begin{matrix}", "\\end{matrix}"
    )

    private val vocabulary = HmerVocabulary.fromLines(sampleVocabTokens)
    private val decoder = ConstrainedBeamDecoder(vocabulary, beamWidth = 3, lengthPenaltyAlpha = 0.6)

    @Test
    fun closingBraceIsMaskedWhenBraceDepthIsZero() {
        val initialHyp = BeamHypothesis(
            tokens = listOf(vocabulary.startId),
            logProb = 0.0,
            braceDepth = 0
        )

        val mask = decoder.computeSyntaxMask(initialHyp, stepIndex = 0, maxSequenceLength = 64)
        val closeBraceId = vocabulary.idFor("}")!!
        assertTrue("Closing brace '}' must be masked out when braceDepth is 0", mask[closeBraceId])
    }

    @Test
    fun closingBraceIsAllowedWhenBraceDepthIsPositive() {
        val openBraceId = vocabulary.idFor("{")!!
        val hypWithOpenBrace = BeamHypothesis(
            tokens = listOf(vocabulary.startId, openBraceId),
            logProb = 0.0,
            braceDepth = 1
        )

        val mask = decoder.computeSyntaxMask(hypWithOpenBrace, stepIndex = 1, maxSequenceLength = 64)
        val closeBraceId = vocabulary.idFor("}")!!
        assertFalse("Closing brace '}' must be allowed when braceDepth > 0", mask[closeBraceId])
    }

    @Test
    fun endOfSequenceIsMaskedWhenBracesAreUnclosed() {
        val openBraceId = vocabulary.idFor("{")!!
        val hyp = BeamHypothesis(
            tokens = listOf(vocabulary.startId, openBraceId),
            logProb = 0.0,
            braceDepth = 1
        )

        val mask = decoder.computeSyntaxMask(hyp, stepIndex = 5, maxSequenceLength = 64)
        assertTrue("<eos> must be masked out while braces remain open", mask[vocabulary.endId])
    }

    @Test
    fun emptyGroupIsMaskedAfterFrac() {
        val fracId = vocabulary.idFor("\\frac")!!
        val openBraceId = vocabulary.idFor("{")!!
        val closeBraceId = vocabulary.idFor("}")!!

        val hyp = BeamHypothesis(
            tokens = listOf(vocabulary.startId, fracId, openBraceId),
            logProb = 0.0,
            braceDepth = 1
        )

        val mask = decoder.computeSyntaxMask(hyp, stepIndex = 2, maxSequenceLength = 64)
        assertTrue("Immediate empty group {} must be masked out after \\frac", mask[closeBraceId])
    }

    @Test
    fun logSoftmaxProducesStableLogProbabilitiesSummingToOne() {
        val rawLogits = FloatArray(vocabulary.size) { (it * 0.5f) - 10f }
        val logProbs = decoder.logSoftmax(rawLogits)

        var totalProb = 0.0
        for (lp in logProbs) {
            assertTrue(lp <= 0.0)
            totalProb += Math.exp(lp)
        }
        assertEquals(1.0, totalProb, 1e-5)
    }

    @Test
    fun beamSearchOutperformsGreedyWhenGreedyLeadsToSuboptimalPath() {
        // Step 1: Greedy chooses token A (score 10) vs token B (score 8)
        // Step 2: Token A has subsequent score 1, token B has subsequent score 20
        // Beam 3 must choose prefix [B, ...] over [A, ...]
        val tokenA = vocabulary.idFor("x")!!
        val tokenB = vocabulary.idFor("y")!!
        val endId = vocabulary.endId

        val initialBeam = listOf(BeamHypothesis(listOf(vocabulary.startId), 0.0))

        val step1Logits = FloatArray(vocabulary.size) { 0f }.apply {
            this[tokenA] = 10f
            this[tokenB] = 8f
        }

        val beamAfterStep1 = decoder.step(
            initialBeam,
            listOf(step1Logits),
            stepIndex = 1,
            maxSequenceLength = 10
        )

        val activeHypotheses = beamAfterStep1.filter { !it.isEnded }
        val step2LogitsList = activeHypotheses.map { hyp ->
            FloatArray(vocabulary.size) { 0f }.apply {
                if (hyp.tokens.last() == tokenA) {
                    this[endId] = 1f
                } else if (hyp.tokens.last() == tokenB) {
                    this[endId] = 20f
                }
            }
        }

        val beamAfterStep2 = decoder.step(
            activeHypotheses,
            step2LogitsList,
            stepIndex = 2,
            maxSequenceLength = 10
        )

        val best = decoder.bestHypothesis(beamAfterStep2)
        assertEquals(listOf(vocabulary.startId, tokenB, endId), best.tokens)
    }
}
