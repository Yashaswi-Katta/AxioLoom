package com.axioloom.app.domain.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.axioloom.app.domain.clustering.InkWorkspaceState
import com.axioloom.app.domain.dag.ReactiveGraphSnapshot
import com.axioloom.app.domain.model.EquationBlock
import com.axioloom.app.domain.model.MathIntent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 100% offline Obsidian Markdown (.md) exporter.
 * Sorts equation blocks by spatial reading order (top-to-bottom row bands, then left-to-right),
 * formats symbol tables, and emits standard Obsidian math blocks ($$...$$) and LaTeX implications.
 */
object MarkdownExporter {

    /**
     * Topologically sorts spatial equation blocks by visual reading bands.
     * Blocks whose vertical bounds overlap significantly are grouped in the same row band,
     * then ordered left-to-right.
     */
    fun sortByReadingOrder(blocks: List<EquationBlock>): List<EquationBlock> {
        if (blocks.size <= 1) return blocks

        // Band threshold based on median stroke height or minimum line height
        val avgHeight = (blocks.map { it.bounds.height }.average().toFloat()).coerceIn(24f, 80f)
        val bandHeight = avgHeight * 1.5f

        return blocks.sortedWith { a, b ->
            val aCenterY = (a.bounds.top + a.bounds.bottom) / 2f
            val bCenterY = (b.bounds.top + b.bounds.bottom) / 2f

            val aBand = (aCenterY / bandHeight).toInt()
            val bBand = (bCenterY / bandHeight).toInt()

            if (aBand != bBand) {
                aBand.compareTo(bBand)
            } else {
                a.bounds.left.compareTo(b.bounds.left)
            }
        }
    }

    /**
     * Emits a clean, self-contained Obsidian Markdown document.
     */
    fun export(
        title: String,
        state: InkWorkspaceState,
        graph: ReactiveGraphSnapshot,
        timestampMillis: Long = System.currentTimeMillis()
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val isoDate = dateFormat.format(Date(timestampMillis))

        val sortedBlocks = sortByReadingOrder(state.blocks)

        val sb = StringBuilder()
        // 1. YAML Frontmatter
        sb.appendLine("---")
        sb.appendLine("title: \"$title\"")
        sb.appendLine("created: \"$isoDate\"")
        sb.appendLine("mode: \"${graph.mode}\"")
        sb.appendLine("tags: [axioloom, engineering-notes]")
        sb.appendLine("---")
        sb.appendLine()

        sb.appendLine("# $title")
        sb.appendLine()

        // 2. Symbol Table
        val definedSymbols = mutableListOf<Triple<String, String, String>>()
        for (block in sortedBlocks) {
            val intent = block.intent
            val result = graph.blocks[block.id]?.result ?: block.result
            val resStr = result?.expression ?: "-"
            when (intent) {
                is MathIntent.Assignment -> {
                    definedSymbols.add(Triple(intent.variableName, intent.rhsExpression, resStr))
                }
                is MathIntent.FunctionDef -> {
                    definedSymbols.add(Triple("${intent.funcName}(${intent.params.joinToString()})", intent.bodyExpression, resStr))
                }
                else -> Unit
            }
        }

        if (definedSymbols.isNotEmpty()) {
            sb.appendLine("## Symbol Table")
            sb.appendLine()
            sb.appendLine("| Symbol | Definition | Evaluated Value |")
            sb.appendLine("| :--- | :--- | :--- |")
            for ((sym, defn, eval) in definedSymbols) {
                sb.appendLine("| `$sym` | `$defn` | `$eval` |")
            }
            sb.appendLine()
        }

        // 3. Workspace Equations
        sb.appendLine("## Workspace Equations")
        sb.appendLine()

        if (sortedBlocks.isEmpty()) {
            sb.appendLine("_No equations in workspace._")
        } else {
            for (block in sortedBlocks) {
                val latex = block.recognizedLatex ?: continue
                val result = graph.blocks[block.id]?.result ?: block.result

                sb.appendLine("$$")
                sb.appendLine(latex)
                sb.appendLine("$$")
                if (result != null) {
                    val resultText = if (result.latex.isNotBlank()) result.latex else result.expression
                    sb.appendLine("$\\implies $resultText$")
                }
                sb.appendLine()
            }
        }

        return sb.toString().trimEnd() + "\n"
    }

    /**
     * Copies the generated markdown to Android system Clipboard.
     */
    fun copyToClipboard(context: Context, label: String, content: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            val clip = ClipData.newPlainText(label, content)
            clipboard.setPrimaryClip(clip)
        }
    }
}
