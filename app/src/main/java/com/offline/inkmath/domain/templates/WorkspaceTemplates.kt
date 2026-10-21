package com.offline.inkmath.domain.templates

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.offline.inkmath.domain.clustering.InkWorkspaceState
import com.offline.inkmath.domain.model.*
import java.util.UUID

data class TemplateBlockSpec(
    val latex: String,
    val bounds: Rect,
    val displayMode: BlockDisplayMode = BlockDisplayMode.TYPESET
)

data class EngineeringTemplate(
    val id: String,
    val title: String,
    val category: String,
    val description: String,
    val blockSpecs: List<TemplateBlockSpec>
)

/**
 * 1-Tap Offline Engineering Starter Templates.
 * Allows users to immediately explore every capability of InkMath without authoring equations from scratch.
 */
object WorkspaceTemplates {

    val CALCULUS_MVT = EngineeringTemplate(
        id = "calc_mvt",
        title = "Calculus & Mean Value Theorem",
        category = "Calculus",
        description = "Definite integration, function assignment, and Lagrange Mean Value Theorem on [0, 2].",
        blockSpecs = listOf(
            TemplateBlockSpec(latex = "a = 2", bounds = Rect(80f, 80f, 240f, 140f)),
            TemplateBlockSpec(latex = "f(x) = x^3 - 3*x", bounds = Rect(80f, 180f, 380f, 250f)),
            TemplateBlockSpec(latex = "\\int_{0}^{a} f(x) d x =", bounds = Rect(80f, 290f, 420f, 370f)),
            TemplateBlockSpec(latex = "f'(c) = 1", bounds = Rect(80f, 410f, 300f, 480f))
        )
    )

    val LINEAR_ALGEBRA = EngineeringTemplate(
        id = "linear_algebra",
        title = "Linear Algebra & Eigenvalues",
        category = "Linear Algebra",
        description = "2x2 Matrix definition, determinant, eigenvalues, and matrix inverse.",
        blockSpecs = listOf(
            TemplateBlockSpec(latex = "A = \\begin{bmatrix} 4 & 1 \\\\ 2 & 3 \\end{bmatrix}", bounds = Rect(80f, 80f, 400f, 200f)),
            TemplateBlockSpec(latex = "\\det(A) =", bounds = Rect(80f, 240f, 280f, 310f)),
            TemplateBlockSpec(latex = "\\operatorname{eigenvalues}(A) =", bounds = Rect(80f, 350f, 440f, 420f)),
            TemplateBlockSpec(latex = "A^{-1} =", bounds = Rect(80f, 460f, 300f, 530f))
        )
    )

    val AC_CIRCUITS = EngineeringTemplate(
        id = "ac_circuits",
        title = "AC Circuit & Phasor Analysis",
        category = "ECE",
        description = "Resistor and complex reactive load in parallel with phasor evaluation.",
        blockSpecs = listOf(
            TemplateBlockSpec(latex = "R_1 = 100", bounds = Rect(80f, 80f, 280f, 150f)),
            TemplateBlockSpec(latex = "Z_L = 50 \\angle 45", bounds = Rect(80f, 190f, 340f, 260f)),
            TemplateBlockSpec(latex = "Z_{eq} = R_1 \\parallel Z_L =", bounds = Rect(80f, 300f, 460f, 380f))
        )
    )

    val CONTROL_SYSTEMS = EngineeringTemplate(
        id = "control_systems",
        title = "Control Systems (s-Domain & Bode)",
        category = "Controls",
        description = "2nd order underdamped transfer function H(s) with Bode and complex s-plane pole-zero map.",
        blockSpecs = listOf(
            TemplateBlockSpec(latex = "H(s) = \\frac{100}{s^2 + 12*s + 100}", bounds = Rect(80f, 80f, 480f, 200f))
        )
    )

    val DIGITAL_LOGIC_DSP = EngineeringTemplate(
        id = "logic_dsp",
        title = "Digital Logic & Discrete DSP",
        category = "DSP & Logic",
        description = "4-variable Karnaugh Map minimization and discrete-time linear convolution.",
        blockSpecs = listOf(
            TemplateBlockSpec(latex = "\\operatorname{kmap}(4, \\sum m(0, 1, 2, 5, 8, 9, 10))", bounds = Rect(80f, 80f, 520f, 180f)),
            TemplateBlockSpec(latex = "\\{1, 2, 1\\} \\ast \\{1, -1\\} =", bounds = Rect(80f, 220f, 450f, 300f))
        )
    )

    val ALL_TEMPLATES = listOf(
        CALCULUS_MVT,
        LINEAR_ALGEBRA,
        AC_CIRCUITS,
        CONTROL_SYSTEMS,
        DIGITAL_LOGIC_DSP
    )

    fun instantiateTemplate(template: EngineeringTemplate): InkWorkspaceState {
        val strokes = mutableListOf<TimedStroke>()
        val blocks = mutableListOf<EquationBlock>()

        template.blockSpecs.forEachIndexed { index, spec ->
            val strokeId = UUID.randomUUID()
            val blockId = UUID.randomUUID()

            // Construct synthetic stroke geometry
            val p1 = InkPoint(spec.bounds.left, spec.bounds.center.y, 0L)
            val p2 = InkPoint(spec.bounds.center.x, spec.bounds.top, 5L)
            val p3 = InkPoint(spec.bounds.right, spec.bounds.center.y, 10L)
            val p4 = InkPoint(spec.bounds.center.x, spec.bounds.bottom, 15L)
            val geom = StrokeGeometry(strokeId, listOf(p1, p2, p3, p4), spec.bounds)
            val stroke = TimedStroke.forTesting(geom, finishedAtMillis = (index * 100).toLong())

            strokes += stroke

            val block = EquationBlock(
                id = blockId,
                strokes = listOf(stroke),
                bounds = spec.bounds,
                medianStrokeHeight = 24f,
                resultAnchorPoint = Offset(spec.bounds.right + 20f, spec.bounds.center.y),
                revision = 0L,
                recognizedLatex = spec.latex,
                displayMode = spec.displayMode,
                status = BlockStatus.Ready
            )
            blocks += block
        }

        return InkWorkspaceState(strokes = strokes, blocks = blocks, version = 1L)
    }
}
