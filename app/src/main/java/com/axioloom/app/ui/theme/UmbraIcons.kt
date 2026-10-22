package com.axioloom.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Geometric, instrument-grade vector icons styled according to the Umbra design specification.
 * Consistent 1.25dp stroke width, zero decorative drop-shadows, sharp technical geometry.
 */

@Composable
fun AxioLoomLogoIcon(
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    tint: Color = UmbraColors.AccentAmber
) {
    Canvas(modifier = modifier.size(size)) {
        val strokeWidth = 1.6f * density
        val w = this.size.width
        val h = this.size.height

        // Clean geometric "A" outer apex
        val apex = Offset(w * 0.5f, h * 0.12f)
        val leftFoot = Offset(w * 0.18f, h * 0.88f)
        val rightFoot = Offset(w * 0.82f, h * 0.88f)

        val path = Path().apply {
            moveTo(leftFoot.x, leftFoot.y)
            lineTo(apex.x, apex.y)
            lineTo(rightFoot.x, rightFoot.y)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Mathematical infinity crossbar
        val midY = h * 0.58f
        val leftNode = Offset(w * 0.32f, midY)
        val rightNode = Offset(w * 0.68f, midY)
        val centerNode = Offset(w * 0.5f, midY)

        val infPath = Path().apply {
            moveTo(leftNode.x, leftNode.y)
            cubicTo(w * 0.22f, midY - h * 0.14f, w * 0.40f, midY - h * 0.14f, centerNode.x, midY)
            cubicTo(w * 0.60f, midY + h * 0.14f, w * 0.78f, midY + h * 0.14f, rightNode.x, midY)
            cubicTo(w * 0.78f, midY - h * 0.14f, w * 0.60f, midY - h * 0.14f, centerNode.x, midY)
            cubicTo(w * 0.40f, midY + h * 0.14f, w * 0.22f, midY + h * 0.14f, leftNode.x, midY)
            close()
        }
        drawPath(
            path = infPath,
            color = tint,
            style = Stroke(width = strokeWidth * 0.85f, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun InspectorIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.35f * density
        val w = this.size.width
        val h = this.size.height

        // Outer inspection reticle corners
        val m = 1.5f * density
        val b = 4.0f * density
        // Top-left corner
        drawLine(tint, Offset(m, m + b), Offset(m, m), s, StrokeCap.Square)
        drawLine(tint, Offset(m, m), Offset(m + b, m), s, StrokeCap.Square)
        // Top-right corner
        drawLine(tint, Offset(w - m - b, m), Offset(w - m, m), s, StrokeCap.Square)
        drawLine(tint, Offset(w - m, m), Offset(w - m, m + b), s, StrokeCap.Square)
        // Bottom-left corner
        drawLine(tint, Offset(m, h - m - b), Offset(m, h - m), s, StrokeCap.Square)
        drawLine(tint, Offset(m, h - m), Offset(m + b, h - m), s, StrokeCap.Square)
        // Bottom-right corner
        drawLine(tint, Offset(w - m - b, h - m), Offset(w - m, h - m), s, StrokeCap.Square)
        drawLine(tint, Offset(w - m, h - m), Offset(w - m, h - m - b), s, StrokeCap.Square)

        // Center lens circle
        val center = Offset(w * 0.46f, h * 0.46f)
        val radius = w * 0.22f
        drawCircle(tint, radius, center, style = Stroke(width = s))
        // Lens handle
        drawLine(
            tint,
            Offset(center.x + radius * 0.7f, center.y + radius * 0.7f),
            Offset(w * 0.82f, h * 0.82f),
            strokeWidth = s * 1.2f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun StepsIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.35f * density
        val w = this.size.width
        val h = this.size.height

        // Ascending step proof ladder
        val stepPath = Path().apply {
            moveTo(w * 0.16f, h * 0.82f)
            lineTo(w * 0.42f, h * 0.82f)
            lineTo(w * 0.42f, h * 0.52f)
            lineTo(w * 0.68f, h * 0.52f)
            lineTo(w * 0.68f, h * 0.22f)
            lineTo(w * 0.88f, h * 0.22f)
        }
        drawPath(stepPath, tint, style = Stroke(width = s, cap = StrokeCap.Square, join = StrokeJoin.Miter))

        // Proof marker dots
        drawCircle(tint, radius = s * 0.9f, center = Offset(w * 0.16f, h * 0.82f))
        drawCircle(tint, radius = s * 0.9f, center = Offset(w * 0.88f, h * 0.22f))
    }
}

@Composable
fun PlotIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.35f * density
        val w = this.size.width
        val h = this.size.height

        // Cartesian L-axes
        drawLine(tint, Offset(w * 0.18f, h * 0.16f), Offset(w * 0.18f, h * 0.84f), s, StrokeCap.Square)
        drawLine(tint, Offset(w * 0.18f, h * 0.84f), Offset(w * 0.86f, h * 0.84f), s, StrokeCap.Square)

        // Wave / Curve plot line
        val curve = Path().apply {
            moveTo(w * 0.20f, h * 0.74f)
            cubicTo(w * 0.36f, h * 0.26f, w * 0.54f, h * 0.96f, w * 0.82f, h * 0.34f)
        }
        drawPath(curve, tint, style = Stroke(width = s, cap = StrokeCap.Round))
    }
}

@Composable
fun ControlIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.35f * density
        val w = this.size.width
        val h = this.size.height

        // Transfer function block [ G(s) ]
        val rect = Rect(w * 0.26f, h * 0.24f, w * 0.74f, h * 0.76f)
        drawRect(tint, rect.topLeft, rect.size, style = Stroke(width = s))

        // Input wire
        drawLine(tint, Offset(w * 0.08f, h * 0.50f), Offset(w * 0.26f, h * 0.50f), s)
        // Output wire
        drawLine(tint, Offset(w * 0.74f, h * 0.50f), Offset(w * 0.92f, h * 0.50f), s)

        // Internal S-plane cross
        drawLine(tint, Offset(w * 0.50f, h * 0.36f), Offset(w * 0.50f, h * 0.64f), s * 0.8f)
        drawLine(tint, Offset(w * 0.38f, h * 0.50f), Offset(w * 0.62f, h * 0.50f), s * 0.8f)
    }
}

@Composable
fun CircuitIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.35f * density
        val w = this.size.width
        val h = this.size.height

        // Schematic Resistor Zig-Zag
        val path = Path().apply {
            moveTo(w * 0.12f, h * 0.50f)
            lineTo(w * 0.28f, h * 0.50f)
            lineTo(w * 0.36f, h * 0.24f)
            lineTo(w * 0.48f, h * 0.76f)
            lineTo(w * 0.60f, h * 0.24f)
            lineTo(w * 0.72f, h * 0.76f)
            lineTo(w * 0.78f, h * 0.50f)
            lineTo(w * 0.88f, h * 0.50f)
        }
        drawPath(path, tint, style = Stroke(width = s, cap = StrokeCap.Round, join = StrokeJoin.Miter))
    }
}

@Composable
fun RfSmithIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.35f * density
        val w = this.size.width
        val h = this.size.height

        // Unit circle
        val center = Offset(w * 0.50f, h * 0.50f)
        val r = w * 0.38f
        drawCircle(tint, r, center, style = Stroke(width = s))

        // Constant resistance circle tangent to right edge
        val rInner = r * 0.50f
        val centerInner = Offset(center.x + (r - rInner), center.y)
        drawCircle(tint, rInner, centerInner, style = Stroke(width = s * 0.85f))

        // Center line
        drawLine(tint, Offset(center.x - r, center.y), Offset(center.x + r, center.y), s * 0.85f)
    }
}

@Composable
fun KMapIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.35f * density
        val w = this.size.width
        val h = this.size.height

        // 2x2 Karnaugh Grid
        val m = w * 0.16f
        val right = w * 0.84f
        val bot = h * 0.84f
        val midX = w * 0.50f
        val midY = h * 0.50f

        drawRect(tint, Offset(m, m), androidx.compose.ui.geometry.Size(right - m, bot - m), style = Stroke(width = s))
        drawLine(tint, Offset(midX, m), Offset(midX, bot), s)
        drawLine(tint, Offset(m, midY), Offset(right, midY), s)

        // Logic grouping bubble in top cells
        val bubblePath = Path().apply {
            moveTo(m + 4f, midY - 3f)
            lineTo(right - 4f, midY - 3f)
        }
        drawPath(bubblePath, UmbraColors.AccentAmber, style = Stroke(width = s * 1.4f, cap = StrokeCap.Round))
    }
}

@Composable
fun CodeIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.35f * density
        val w = this.size.width
        val h = this.size.height

        // Left angle bracket <
        val leftPath = Path().apply {
            moveTo(w * 0.34f, h * 0.28f)
            lineTo(w * 0.16f, h * 0.50f)
            lineTo(w * 0.34f, h * 0.72f)
        }
        drawPath(leftPath, tint, style = Stroke(width = s, cap = StrokeCap.Round, join = StrokeJoin.Miter))

        // Right angle bracket >
        val rightPath = Path().apply {
            moveTo(w * 0.66f, h * 0.28f)
            lineTo(w * 0.84f, h * 0.50f)
            lineTo(w * 0.66f, h * 0.72f)
        }
        drawPath(rightPath, tint, style = Stroke(width = s, cap = StrokeCap.Round, join = StrokeJoin.Miter))

        // Slash /
        drawLine(tint, Offset(w * 0.58f, h * 0.22f), Offset(w * 0.42f, h * 0.78f), s, StrokeCap.Round)
    }
}

@Composable
fun EraserIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.35f * density
        val w = this.size.width
        val h = this.size.height

        // Angled parallelogram eraser block
        val path = Path().apply {
            moveTo(w * 0.32f, h * 0.16f)
            lineTo(w * 0.84f, h * 0.34f)
            lineTo(w * 0.68f, h * 0.84f)
            lineTo(w * 0.16f, h * 0.66f)
            close()
        }
        drawPath(path, tint, style = Stroke(width = s, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Rubber bevel seam line
        drawLine(
            tint,
            Offset(w * 0.50f, h * 0.22f),
            Offset(w * 0.34f, h * 0.72f),
            strokeWidth = s,
            cap = StrokeCap.Square
        )
    }
}

@Composable
fun SchematicIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.35f * density
        val w = this.size.width
        val h = this.size.height

        // OP-AMP Triangle Symbol
        val path = Path().apply {
            moveTo(w * 0.24f, h * 0.20f)
            lineTo(w * 0.78f, h * 0.50f)
            lineTo(w * 0.24f, h * 0.80f)
            close()
        }
        drawPath(path, tint, style = Stroke(width = s, cap = StrokeCap.Round, join = StrokeJoin.Miter))

        // Inverting / Non-inverting input stubs
        drawLine(tint, Offset(w * 0.10f, h * 0.32f), Offset(w * 0.24f, h * 0.32f), s)
        drawLine(tint, Offset(w * 0.10f, h * 0.68f), Offset(w * 0.24f, h * 0.68f), s)
        // Output stub
        drawLine(tint, Offset(w * 0.78f, h * 0.50f), Offset(w * 0.90f, h * 0.50f), s)
    }
}

@Composable
fun ConstantsIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.35f * density
        val w = this.size.width
        val h = this.size.height

        // Planck h-bar / constant symbol: ħ
        // Vertical stem with top hook
        val hPath = Path().apply {
            moveTo(w * 0.30f, h * 0.16f)
            lineTo(w * 0.30f, h * 0.82f)
            moveTo(w * 0.30f, h * 0.48f)
            cubicTo(w * 0.42f, h * 0.34f, w * 0.66f, h * 0.34f, w * 0.66f, h * 0.54f)
            lineTo(w * 0.66f, h * 0.82f)
        }
        drawPath(hPath, tint, style = Stroke(width = s, cap = StrokeCap.Round))

        // Slash bar on top of h for h-bar (ħ)
        drawLine(tint, Offset(w * 0.20f, h * 0.32f), Offset(w * 0.44f, h * 0.26f), s * 1.1f, StrokeCap.Round)
    }
}

@Composable
fun TemplatesIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.35f * density
        val w = this.size.width
        val h = this.size.height

        // Document with folded top-right corner
        val foldSize = w * 0.24f
        val path = Path().apply {
            moveTo(w * 0.20f, h * 0.16f)
            lineTo(w * 0.80f - foldSize, h * 0.16f)
            lineTo(w * 0.80f, h * 0.16f + foldSize)
            lineTo(w * 0.80f, h * 0.84f)
            lineTo(w * 0.20f, h * 0.84f)
            close()
        }
        drawPath(path, tint, style = Stroke(width = s, cap = StrokeCap.Round, join = StrokeJoin.Miter))

        // Fold corner flap
        val foldFlap = Path().apply {
            moveTo(w * 0.80f - foldSize, h * 0.16f)
            lineTo(w * 0.80f - foldSize, h * 0.16f + foldSize)
            lineTo(w * 0.80f, h * 0.16f + foldSize)
        }
        drawPath(foldFlap, tint, style = Stroke(width = s * 0.85f, cap = StrokeCap.Round))

        // Horizontal technical lines inside doc
        drawLine(tint, Offset(w * 0.32f, h * 0.46f), Offset(w * 0.68f, h * 0.46f), s * 0.8f)
        drawLine(tint, Offset(w * 0.32f, h * 0.62f), Offset(w * 0.60f, h * 0.62f), s * 0.8f)
    }
}

// -------------------------------------------------------------------------
// Sub-Mode Vector Icons (1D Curve, 3D Surface, Vector Field, Discrete DSP)
// -------------------------------------------------------------------------

@Composable
fun Curve1DIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.35f * density
        val w = this.size.width
        val h = this.size.height

        // Axis crosshair
        drawLine(tint.copy(alpha = 0.5f), Offset(w * 0.16f, h * 0.50f), Offset(w * 0.84f, h * 0.50f), s * 0.7f)
        drawLine(tint.copy(alpha = 0.5f), Offset(w * 0.50f, h * 0.16f), Offset(w * 0.50f, h * 0.84f), s * 0.7f)

        // Parabola / Cubic curve
        val path = Path().apply {
            moveTo(w * 0.20f, h * 0.80f)
            cubicTo(w * 0.40f, h * 0.20f, w * 0.60f, h * 0.80f, w * 0.80f, h * 0.20f)
        }
        drawPath(path, tint, style = Stroke(width = s, cap = StrokeCap.Round))
    }
}

@Composable
fun Surface3DIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.25f * density
        val w = this.size.width
        val h = this.size.height

        // Isometric wireframe mesh diamond
        val top = Offset(w * 0.50f, h * 0.18f)
        val left = Offset(w * 0.18f, h * 0.50f)
        val bottom = Offset(w * 0.50f, h * 0.82f)
        val right = Offset(w * 0.82f, h * 0.50f)

        val outer = Path().apply {
            moveTo(top.x, top.y)
            lineTo(right.x, right.y)
            lineTo(bottom.x, bottom.y)
            lineTo(left.x, left.y)
            close()
        }
        drawPath(outer, tint, style = Stroke(width = s))

        // Saddle wire curves
        drawLine(tint, Offset(w * 0.34f, h * 0.34f), Offset(w * 0.66f, h * 0.66f), s * 0.8f)
        drawLine(tint, Offset(w * 0.66f, h * 0.34f), Offset(w * 0.34f, h * 0.66f), s * 0.8f)
    }
}

@Composable
fun VectorFieldIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.25f * density
        val w = this.size.width
        val h = this.size.height

        // 4 small vector field arrows pointing in swirl
        fun drawArrow(start: Offset, end: Offset) {
            drawLine(tint, start, end, s, StrokeCap.Round)
            val dir = (end - start)
            val norm = Offset(-dir.y, dir.x) * 0.35f
            drawLine(tint, end, end - (dir * 0.35f) + norm, s * 0.85f, StrokeCap.Round)
        }

        drawArrow(Offset(w * 0.22f, h * 0.28f), Offset(w * 0.44f, h * 0.20f))
        drawArrow(Offset(w * 0.76f, h * 0.24f), Offset(w * 0.80f, h * 0.46f))
        drawArrow(Offset(w * 0.78f, h * 0.76f), Offset(w * 0.54f, h * 0.80f))
        drawArrow(Offset(w * 0.24f, h * 0.74f), Offset(w * 0.20f, h * 0.52f))
    }
}

@Composable
fun DspStemIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.35f * density
        val w = this.size.width
        val h = this.size.height

        // Horizontal zero baseline
        val base = h * 0.68f
        drawLine(tint.copy(alpha = 0.5f), Offset(w * 0.12f, base), Offset(w * 0.88f, base), s * 0.8f)

        // 3 discrete stems with circle head
        val xPositions = listOf(w * 0.28f, w * 0.50f, w * 0.72f)
        val heights = listOf(h * 0.44f, h * 0.20f, h * 0.34f)

        xPositions.forEachIndexed { i, x ->
            val topY = heights[i]
            drawLine(tint, Offset(x, base), Offset(x, topY), s)
            drawCircle(tint, radius = s * 1.1f, center = Offset(x, topY))
        }
    }
}

@Composable
fun OdeIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.35f * density
        val w = this.size.width
        val h = this.size.height

        // Phase portrait spiral / limit cycle
        val spiral = Path().apply {
            moveTo(w * 0.50f, h * 0.50f)
            cubicTo(w * 0.65f, h * 0.45f, w * 0.65f, h * 0.28f, w * 0.48f, h * 0.26f)
            cubicTo(w * 0.26f, h * 0.24f, w * 0.22f, h * 0.66f, w * 0.46f, h * 0.74f)
            cubicTo(w * 0.76f, h * 0.82f, w * 0.84f, h * 0.32f, w * 0.82f, h * 0.20f)
        }
        drawPath(spiral, tint, style = Stroke(width = s, cap = StrokeCap.Round))
    }
}

@Composable
fun BodeIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.35f * density
        val w = this.size.width
        val h = this.size.height

        // Axes
        drawLine(tint.copy(alpha = 0.5f), Offset(w * 0.16f, h * 0.18f), Offset(w * 0.16f, h * 0.82f), s * 0.8f)
        drawLine(tint.copy(alpha = 0.5f), Offset(w * 0.16f, h * 0.82f), Offset(w * 0.84f, h * 0.82f), s * 0.8f)

        // Log magnitude roll-off: flat then -20dB/dec slope
        val bodePath = Path().apply {
            moveTo(w * 0.18f, h * 0.34f)
            lineTo(w * 0.44f, h * 0.34f)
            lineTo(w * 0.82f, h * 0.74f)
        }
        drawPath(bodePath, tint, style = Stroke(width = s, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Cutoff frequency marker dot
        drawCircle(UmbraColors.AccentAmber, radius = s * 1.1f, center = Offset(w * 0.44f, h * 0.34f))
    }
}

@Composable
fun RootLocusIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = UmbraColors.TextSecondary
) {
    Canvas(modifier = modifier.size(size)) {
        val s = 1.35f * density
        val w = this.size.width
        val h = this.size.height

        // S-plane cross: Re & Im axes
        drawLine(tint.copy(alpha = 0.5f), Offset(w * 0.16f, h * 0.50f), Offset(w * 0.84f, h * 0.50f), s * 0.8f)
        drawLine(tint.copy(alpha = 0.5f), Offset(w * 0.62f, h * 0.16f), Offset(w * 0.62f, h * 0.84f), s * 0.8f)

        // S-plane Pole cross 'x'
        val px = w * 0.34f
        val py = h * 0.50f
        val d = 2.5f * density
        drawLine(tint, Offset(px - d, py - d), Offset(px + d, py + d), s)
        drawLine(tint, Offset(px - d, py + d), Offset(px + d, py - d), s)

        // Root locus branch extending leftward
        drawLine(UmbraColors.AccentAmber, Offset(px, py), Offset(w * 0.16f, py), s * 1.2f, StrokeCap.Round)
    }
}
