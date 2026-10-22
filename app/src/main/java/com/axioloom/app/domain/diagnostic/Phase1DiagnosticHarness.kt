package com.axioloom.app.domain.diagnostic

import com.axioloom.app.domain.cas.OfflineCasEngine
import com.axioloom.app.domain.dag.ReactiveSymbolGraph
import com.axioloom.app.domain.model.BlockStatus
import com.axioloom.app.domain.model.CasResult
import com.axioloom.app.domain.parser.LatexToSymjaTranspiler
import java.util.UUID

/** Executable acceptance diagnostic for the Phase 1 reactive binding requirement. */
data class Phase1DiagnosticResult(
    val assignmentValueBefore: String,
    val integralValueBefore: String,
    val assignmentValueAfter: String,
    val integralValueAfter: String
)

object Phase1DiagnosticHarness {
    suspend fun run(): Phase1DiagnosticResult {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val graph = ReactiveSymbolGraph(cas)
            val parser = LatexToSymjaTranspiler()
            val assignmentId = UUID.randomUUID()
            val integralId = UUID.randomUUID()

            val withFour = graph.upsert(assignmentId, parser.transpile("a = 4").intent)
            val a4 = readyResult(withFour.blocks.getValue(assignmentId).status, withFour.blocks.getValue(assignmentId).result)
            val before = graph.upsert(integralId, parser.transpile("\\int_{0}^{a} 3 x^2 d x =").intent)
            val integral64 = readyResult(before.blocks.getValue(integralId).status, before.blocks.getValue(integralId).result)
            check(integral64.expression == "64") { "Expected integral=64, received ${integral64.expression}" }

            val after = graph.upsert(assignmentId, parser.transpile("a = 2").intent)
            val a2 = readyResult(after.blocks.getValue(assignmentId).status, after.blocks.getValue(assignmentId).result)
            val integral8 = readyResult(after.blocks.getValue(integralId).status, after.blocks.getValue(integralId).result)
            check(integral8.expression == "8") { "Expected propagated integral=8, received ${integral8.expression}" }

            return Phase1DiagnosticResult(a4.expression, integral64.expression, a2.expression, integral8.expression)
        } finally {
            cas.close()
        }
    }

    private fun readyResult(status: BlockStatus, result: CasResult?): CasResult {
        check(status == BlockStatus.Ready) { "Diagnostic block did not reach Ready: $status" }
        return checkNotNull(result) { "Diagnostic block has no CAS result" }
    }
}
