package com.axioloom.app.ml.hmer

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

enum class DecodingStrategy {
    GREEDY,
    BEAM_3
}

/**
 * Syntax-guided beam search hypothesis.
 */
data class BeamHypothesis(
    val tokens: List<Int>,
    val logProb: Double,
    val braceDepth: Int = 0,
    val envStack: List<String> = emptyList(),
    val isEnded: Boolean = false,
    val coverage: FloatArray? = null,
    val pendingMandatoryGroups: Int = 0,
    val insideFraction: Boolean = false,
    val fractionPart: Int = 0,
    val numeratorHasD: Boolean = false
) {
    fun lengthPenalizedScore(alpha: Double = 0.6): Double {
        val t = (tokens.size - 1).coerceAtLeast(1) // exclude start token
        val penalty = ((5.0 + t) / 6.0).pow(alpha)
        return logProb / penalty
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BeamHypothesis) return false
        return tokens == other.tokens
    }

    override fun hashCode(): Int = tokens.hashCode()
}

/**
 * Width-3 beam search decoder with syntax-guided prefix constraints for LaTeX HMER generation.
 * Enforces balanced curly braces, environment closure, and non-empty mathematical groups.
 */
class ConstrainedBeamDecoder(
    val vocabulary: HmerVocabulary,
    val beamWidth: Int = 3,
    val lengthPenaltyAlpha: Double = 0.6
) {
    private val openBraceId = vocabulary.exactIdFor("{")
    private val closeBraceId = vocabulary.exactIdFor("}")
    private val endId = vocabulary.endId
    private val dId = vocabulary.exactIdFor("d")
    private val partialId = vocabulary.exactIdFor("\\partial")

    private val nonGroupPredecessors = setOf(
        "\\frac", "\\dfrac", "\\tfrac", "\\sqrt", "_", "^"
    ).mapNotNull { vocabulary.exactIdFor(it) }.toSet()

    /**
     * Converts raw unnormalized logits to log-probabilities using numerically stable max-trick:
     * log P(i) = z_i - max(z) - ln(sum(exp(z_j - max(z))))
     */
    fun logSoftmax(logits: FloatArray, mask: BooleanArray? = null): DoubleArray {
        var maxLogit = Float.NEGATIVE_INFINITY
        for (i in logits.indices) {
            if (mask != null && mask[i]) continue
            if (logits[i] > maxLogit && logits[i].isFinite()) {
                maxLogit = logits[i]
            }
        }

        if (maxLogit == Float.NEGATIVE_INFINITY) {
            return DoubleArray(logits.size) { Double.NEGATIVE_INFINITY }
        }

        var sumExp = 0.0
        for (i in logits.indices) {
            if (mask != null && mask[i]) continue
            if (logits[i].isFinite()) {
                sumExp += exp((logits[i] - maxLogit).toDouble())
            }
        }

        val logSumExp = ln(sumExp)
        val logProbs = DoubleArray(logits.size)
        for (i in logits.indices) {
            if (mask != null && mask[i]) {
                logProbs[i] = Double.NEGATIVE_INFINITY
            } else if (logits[i].isFinite()) {
                logProbs[i] = (logits[i] - maxLogit).toDouble() - logSumExp
            } else {
                logProbs[i] = Double.NEGATIVE_INFINITY
            }
        }
        return logProbs
    }

    /**
     * Builds a boolean mask where true means the token is disallowed under syntax constraints.
     */
    fun computeSyntaxMask(
        hyp: BeamHypothesis,
        stepIndex: Int,
        maxSequenceLength: Int
    ): BooleanArray {
        val mask = BooleanArray(vocabulary.size)

        // 1. Mask closing brace if braceDepth <= 0
        if (hyp.braceDepth <= 0 && closeBraceId != null && closeBraceId < mask.size) {
            mask[closeBraceId] = true
        }

        // 2. Prevent empty groups after open brace
        if (hyp.tokens.isNotEmpty() && closeBraceId != null && closeBraceId < mask.size) {
            val lastToken = hyp.tokens.last()
            if (lastToken == openBraceId) {
                mask[closeBraceId] = true
            }
        }

        // 3. Mask <eos> while braces are open, environments are unclosed, or pending mandatory groups remain
        val forceClose = (stepIndex >= maxSequenceLength - hyp.braceDepth - hyp.envStack.size)
        if (!forceClose) {
            if (hyp.braceDepth > 0 || hyp.envStack.isNotEmpty() || hyp.pendingMandatoryGroups > 0) {
                if (endId < mask.size) mask[endId] = true
            }
        } else {
            // Near sequence cutoff: disallow <eos> until braces and environments are closed
            if (hyp.braceDepth > 0 || hyp.envStack.isNotEmpty()) {
                if (endId < mask.size) mask[endId] = true
            }
        }

        // 4. Validate \end{...} environments against the environment stack
        for (tokenId in 0 until vocabulary.size) {
            val tokenText = vocabulary.tokenFor(tokenId)
            if (tokenText.startsWith("\\end{") && tokenText.endsWith("}")) {
                val envName = tokenText.substring(5, tokenText.length - 1)
                if (hyp.envStack.isEmpty() || hyp.envStack.last() != envName) {
                    mask[tokenId] = true
                }
            }
        }

        return mask
    }

    /**
     * Advances a hypothesis with a new token and computes updated syntax tracking state.
     */
    fun advanceHypothesis(
        hyp: BeamHypothesis,
        tokenId: Int,
        tokenLogProb: Double,
        nextCoverage: FloatArray? = null
    ): BeamHypothesis {
        val isEnded = (tokenId == endId)
        var newBraceDepth = hyp.braceDepth
        var newEnvStack = hyp.envStack
        var newPendingGroups = hyp.pendingMandatoryGroups

        val tokenText = vocabulary.tokenFor(tokenId)
        var newInsideFraction = hyp.insideFraction
        var newFractionPart = hyp.fractionPart
        var newNumHasD = hyp.numeratorHasD

        if (tokenText in setOf("\\frac", "\\dfrac", "\\tfrac")) {
            newPendingGroups += 2
            newInsideFraction = true
            newFractionPart = 0
            newNumHasD = false
        } else if (tokenText in setOf("\\sqrt", "_", "^")) {
            newPendingGroups += 1
        }

        if (tokenId == openBraceId) {
            newBraceDepth++
            if (newPendingGroups > 0) newPendingGroups--
            if (newInsideFraction) {
                if (newFractionPart == 0) {
                    newFractionPart = 1 // Numerator started
                } else if (newFractionPart == 1 && newBraceDepth == 1) {
                    newFractionPart = 2 // Denominator started
                }
            }
        } else if (tokenId == closeBraceId) {
            if (newInsideFraction && newBraceDepth == 1 && newFractionPart == 2) {
                newInsideFraction = false
                newFractionPart = 0
            }
            newBraceDepth = (newBraceDepth - 1).coerceAtLeast(0)
        }

        if (newInsideFraction && newFractionPart == 1 && (tokenText == "d" || tokenText == "\\partial")) {
            newNumHasD = true
        }

        if (tokenText.startsWith("\\begin{") && tokenText.endsWith("}")) {
            val env = tokenText.substring(7, tokenText.length - 1)
            newEnvStack = hyp.envStack + env
        } else if (tokenText.startsWith("\\end{") && tokenText.endsWith("}")) {
            val env = tokenText.substring(5, tokenText.length - 1)
            if (newEnvStack.isNotEmpty() && newEnvStack.last() == env) {
                newEnvStack = newEnvStack.dropLast(1)
            }
        }

        return BeamHypothesis(
            tokens = hyp.tokens + tokenId,
            logProb = hyp.logProb + tokenLogProb,
            braceDepth = newBraceDepth,
            envStack = newEnvStack,
            isEnded = isEnded,
            coverage = nextCoverage ?: hyp.coverage,
            pendingMandatoryGroups = newPendingGroups,
            insideFraction = newInsideFraction,
            fractionPart = newFractionPart,
            numeratorHasD = newNumHasD
        )
    }

    /**
     * Selects top [beamWidth] candidate expansions given raw logits for each active beam hypothesis.
     */
    fun step(
        activeBeams: List<BeamHypothesis>,
        logitsPerBeam: List<FloatArray>,
        stepIndex: Int,
        maxSequenceLength: Int,
        coveragePerBeam: List<FloatArray?> = emptyList()
    ): List<BeamHypothesis> {
        val candidates = mutableListOf<BeamHypothesis>()

        for (bIndex in activeBeams.indices) {
            val hyp = activeBeams[bIndex]
            if (hyp.isEnded) {
                candidates.add(hyp)
                continue
            }

            val logits = logitsPerBeam[bIndex]
            val mask = computeSyntaxMask(hyp, stepIndex, maxSequenceLength)
            val logProbs = logSoftmax(logits, mask)

            // Syntax-guided contextual boost for differentials (dy/dx, \partial y/\partial x)
            if (hyp.insideFraction && hyp.tokens.isNotEmpty() && hyp.tokens.last() == openBraceId) {
                if (hyp.fractionPart == 2 && hyp.numeratorHasD) {
                    // Start of denominator when numerator contained differential 'd' or '\partial'
                    if (dId != null && dId < logProbs.size && logProbs[dId].isFinite()) {
                        logProbs[dId] += 1.5
                    }
                    if (partialId != null && partialId < logProbs.size && logProbs[partialId].isFinite()) {
                        logProbs[partialId] += 1.2
                    }
                } else if (hyp.fractionPart == 1) {
                    // Start of numerator in fraction
                    if (dId != null && dId < logProbs.size && logProbs[dId].isFinite()) {
                        logProbs[dId] += 0.5
                    }
                    if (partialId != null && partialId < logProbs.size && logProbs[partialId].isFinite()) {
                        logProbs[partialId] += 0.5
                    }
                }
            }

            val cov = coveragePerBeam.getOrNull(bIndex)

            // Find top-K tokens for this beam
            val indexed = logProbs.indices
                .filter { logProbs[it].isFinite() }
                .sortedByDescending { logProbs[it] }
                .take(beamWidth)

            if (indexed.isEmpty()) {
                // Fallback: force end if no valid tokens remain
                candidates.add(hyp.copy(isEnded = true))
            } else {
                for (tokenId in indexed) {
                    candidates.add(advanceHypothesis(hyp, tokenId, logProbs[tokenId], cov))
                }
            }
        }

        // Sort all candidates by length-penalized score or cumulative log-prob and keep top beamWidth
        return candidates
            .sortedByDescending { it.lengthPenalizedScore(lengthPenaltyAlpha) }
            .distinctBy { it.tokens }
            .take(beamWidth)
    }

    /**
     * Returns the best hypothesis among completed and active beams.
     */
    fun bestHypothesis(beams: List<BeamHypothesis>): BeamHypothesis {
        require(beams.isNotEmpty()) { "No hypotheses in beam" }
        val ended = beams.filter { it.isEnded }
        return if (ended.isNotEmpty()) {
            ended.maxByOrNull { it.lengthPenalizedScore(lengthPenaltyAlpha) } ?: ended.first()
        } else {
            beams.maxByOrNull { it.lengthPenalizedScore(lengthPenaltyAlpha) } ?: beams.first()
        }
    }

    /**
     * Returns top [topK] ranked hypotheses for UI beam disambiguation.
     */
    fun topHypotheses(beams: List<BeamHypothesis>, topK: Int = 3): List<BeamHypothesis> {
        val ended = beams.filter { it.isEnded }.sortedByDescending { it.lengthPenalizedScore(lengthPenaltyAlpha) }
        val remaining = beams.filter { !it.isEnded }.sortedByDescending { it.lengthPenalizedScore(lengthPenaltyAlpha) }
        return (ended + remaining).distinctBy { it.tokens }.take(topK)
    }
}
