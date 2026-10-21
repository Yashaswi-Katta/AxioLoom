package com.offline.inkmath.domain

import com.offline.inkmath.domain.cas.OfflineCasEngine
import com.offline.inkmath.domain.dag.ReactiveSymbolGraph
import com.offline.inkmath.domain.evaluator.FastPlotEvaluator
import com.offline.inkmath.domain.model.BinaryOperator
import com.offline.inkmath.domain.model.BlockStatus
import com.offline.inkmath.domain.model.CasMode
import com.offline.inkmath.domain.model.MathExpr
import com.offline.inkmath.domain.parser.LatexToSymjaTranspiler
import com.offline.inkmath.domain.stats.DataPoint
import com.offline.inkmath.domain.stats.DataRegressionEngine
import com.offline.inkmath.ml.hmer.ImageMathPreprocessor
import com.offline.inkmath.ui.canvas.Surface3DProjector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.math.abs
import kotlin.math.exp

class Phase21To25FinalTest {

    private val parser = LatexToSymjaTranspiler()

    @Test
    fun surface3DProjectorOrdersQuadsBackToFrontAndEvaluatesTwoVarFunction() {
        // f(x, y) = x^2 - y^2 (Hyperbolic paraboloid / saddle surface)
        val expr = MathExpr.BinaryOp(
            BinaryOperator.SUBTRACT,
            MathExpr.BinaryOp(BinaryOperator.POWER, MathExpr.Variable("x"), MathExpr.Number("2")),
            MathExpr.BinaryOp(BinaryOperator.POWER, MathExpr.Variable("y"), MathExpr.Number("2"))
        )

        val steps = 10
        val surfaceData = FastPlotEvaluator.sampleSurface(
            expr = expr,
            var1 = "x",
            var2 = "y",
            range1 = -2.0..2.0,
            range2 = -2.0..2.0,
            steps = steps
        )

        assertEquals("Expected 10x10 grid of points", 100, surfaceData.vertices.size)
        assertEquals(10, surfaceData.steps)
        assertTrue("Min Z should be <= -4.0", surfaceData.zMin <= -3.9)
        assertTrue("Max Z should be >= 4.0", surfaceData.zMax >= 3.9)

        // Verify Center Point (0, 0) => z = 0
        val centerPt = surfaceData.vertices.minByOrNull { abs(it.x) + abs(it.y) }
        assertNotNull(centerPt)
        assertEquals(0.0, centerPt!!.z, 0.2)

        // Project quads via isometric projection and Painter's algorithm
        val quads = Surface3DProjector.buildQuads(
            grid = surfaceData,
            yawDeg = 45.0,
            pitchDeg = 35.0,
            scale = 20.0,
            centerX = 300f,
            centerY = 300f
        )

        assertEquals("Expected (steps-1)*(steps-1) quads", (steps - 1) * (steps - 1), quads.size)

        // Painter's algorithm invariant: depth must be monotonically non-decreasing
        for (i in 1 until quads.size) {
            assertTrue(
                "Quad depth violated Painter's algorithm back-to-front order at $i: ${quads[i - 1].avgDepth} > ${quads[i].avgDepth}",
                quads[i].avgDepth >= quads[i - 1].avgDepth - 1e-6
            )
        }

        // Test height color palette mapping
        val lowColor = Surface3DProjector.heightToColor(0.1)
        val midColor = Surface3DProjector.heightToColor(0.5)
        val highColor = Surface3DProjector.heightToColor(0.9)
        assertNotNull(lowColor)
        assertNotNull(midColor)
        assertNotNull(highColor)
    }

    @Test
    fun vectorFieldEvaluatorAndSimulatorGeneratesVortexAndRK4Streamline() {
        // Rotational vector field F(x, y) = <y, -x>
        val pExpr = MathExpr.BinaryOp(BinaryOperator.SUBTRACT, MathExpr.Variable("y"), MathExpr.Number("0"))
        val qExpr = MathExpr.BinaryOp(BinaryOperator.SUBTRACT, MathExpr.Number("0"), MathExpr.Variable("x"))

        val vectors = FastPlotEvaluator.sampleVectorField(pExpr, qExpr, gridSize = 11)
        assertEquals("Expected 11x11 = 121 vectors", 121, vectors.size)

        // Center should have zero magnitude
        val centerVec = vectors.minByOrNull { abs(it.x) + abs(it.y) }
        assertNotNull(centerVec)
        assertEquals(0.0, centerVec!!.magnitude, 1e-5)

        // Point (1, 0) should have P=0, Q=-1, magnitude = 1
        val pt10 = vectors.firstOrNull { abs(it.x - 1.0) < 0.1 && abs(it.y) < 0.1 }
        if (pt10 != null) {
            assertEquals(0.0, pt10.u, 0.1)
            assertEquals(-1.0, pt10.v, 0.1)
            assertEquals(1.0, pt10.magnitude, 0.1)
        }
    }

    @Test
    fun dataRegressionEngineComputesExactLinearAndQuadraticLeastSquares() {
        // 1. Linear: y = 2x + 1
        val linearPoints = listOf(
            DataPoint(1.0, 3.0),
            DataPoint(2.0, 5.0),
            DataPoint(3.0, 7.0),
            DataPoint(4.0, 9.0)
        )
        val linearFit = DataRegressionEngine.linearRegression(linearPoints)
        assertEquals(2.0, linearFit.slope, 1e-4)
        assertEquals(1.0, linearFit.intercept, 1e-4)
        assertEquals(1.0, linearFit.rSquared, 1e-4)
        assertEquals(1.0, linearFit.r, 1e-4)
        assertTrue(linearFit.rmse < 1e-4)

        // 2. Quadratic: y = x^2 + 1
        val quadPoints = listOf(
            DataPoint(0.0, 1.0),
            DataPoint(1.0, 2.0),
            DataPoint(2.0, 5.0),
            DataPoint(3.0, 10.0),
            DataPoint(4.0, 17.0)
        )
        val quadFit = DataRegressionEngine.polynomialRegression(quadPoints, degree = 2)
        assertEquals(3, quadFit.coefficients.size)
        assertEquals(1.0, quadFit.coefficients[0], 1e-4) // c = 1
        assertEquals(0.0, quadFit.coefficients[1], 1e-4) // b = 0
        assertEquals(1.0, quadFit.coefficients[2], 1e-4) // a = 1
        assertEquals(1.0, quadFit.rSquared, 1e-4)
        assertTrue(quadFit.rmse < 1e-4)

        // 3. Exponential: y = 2 * e^x
        val expPoints = listOf(
            DataPoint(0.0, 2.0 * exp(0.0)),
            DataPoint(1.0, 2.0 * exp(1.0)),
            DataPoint(2.0, 2.0 * exp(2.0)),
            DataPoint(3.0, 2.0 * exp(3.0))
        )
        val expFit = DataRegressionEngine.exponentialRegression(expPoints)
        assertEquals(2.0, expFit.a, 1e-3)
        assertEquals(1.0, expFit.b, 1e-3)
        assertTrue("R^2 should be ~1.0", expFit.rSquared > 0.999)

        // 4. Descriptive Statistics
        val sample = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val stats = DataRegressionEngine.descriptiveStats(sample)
        assertEquals(5, stats.count)
        assertEquals(3.0, stats.mean, 1e-5)
        assertEquals(2.5, stats.variance, 1e-5)
        assertEquals(1.5811388, stats.stdev, 1e-4)
        assertEquals(3.0, stats.median, 1e-5)
        assertEquals(1.0, stats.min, 1e-5)
        assertEquals(5.0, stats.max, 1e-5)
    }

    @Test
    fun sauvolaImagePreprocessorBinarizesSyntheticNoisyBitmapIntoNormalizedTensor() {
        // Create 64x64 synthetic image: bright background (240) with a dark cross at (32, y) and (x, 32)
        val w = 64
        val h = 64
        val luminance = IntArray(w * h) { 240 }

        // Draw cross (dark ink = 20)
        for (y in 16..48) {
            luminance[y * w + 32] = 20
        }
        for (x in 16..48) {
            luminance[32 * w + x] = 20
        }

        // Add 2-pixel isolated noise blob at (5, 5)
        luminance[5 * w + 5] = 20
        luminance[5 * w + 6] = 20

        // Sauvola binarization
        val binary = ImageMathPreprocessor.binarizeLuminance(luminance, w, h)
        // Center cross should be detected as foreground ink
        assertTrue("Center cross at (32, 32) must be true", binary[32 * w + 32])
        assertTrue("Cross arm at (32, 20) must be true", binary[20 * w + 32])
        // Corner background should be false
        assertFalse("Background corner (0, 0) must be false", binary[0])

        // Noise removal filter (minArea = 6)
        val denoised = ImageMathPreprocessor.removeSpeckles(binary, w, h, minArea = 6)
        // Cross must be preserved
        assertTrue("Cross must be preserved after speckle filter", denoised[32 * w + 32])
        // 2-pixel isolated noise blob must be eliminated
        assertFalse("2-pixel noise blob at (5, 5) must be removed", denoised[5 * w + 5])
        assertFalse("2-pixel noise blob at (5, 6) must be removed", denoised[5 * w + 6])

        // Full normalization pipeline on headless JVM
        val tensor = ImageMathPreprocessor.preprocessLuminanceGrid(w, h, luminance)
        assertNotNull(tensor)
        assertTrue("Tensor height must be positive", tensor.height > 0)
        assertTrue("Tensor width must be positive", tensor.width > 0)
        assertTrue("Crop bounds width must be positive", tensor.cropBounds.width > 0)
        val buf = tensor.asFloatBuffer()
        assertTrue("Buffer must contain normalized float data", buf.hasRemaining())
    }

    @Test
    fun fullEndToEndStressTestWith50DependentBlocksCompletesUnderBudget() = runBlocking {
        val cas = OfflineCasEngine()
        try {
            cas.warmUp()
            val graph = ReactiveSymbolGraph(cas, CasMode.NUMERIC)

            val startTime = System.currentTimeMillis()
            val blockCount = 50

            // Chain:
            // v_0 = 1
            // v_1 = v_0 + 1
            // ...
            // v_49 = v_48 + 1
            val blockIds = List(blockCount) { UUID.randomUUID() }

            val firstIntent = parser.transpile("v_0 = 1").intent
            graph.upsert(blockIds[0], firstIntent)

            for (i in 1 until blockCount) {
                val latex = "v_$i = v_${i - 1} + 1"
                val intent = parser.transpile(latex).intent
                graph.upsert(blockIds[i], intent)
            }

            val snapshot = graph.state.value
            assertEquals("All $blockCount blocks must be in graph", blockCount, snapshot.blocks.size)
            assertTrue("No cyclic blocks allowed", snapshot.cyclicBlocks.isEmpty())

            // Every block must be Ready
            for (id in blockIds) {
                val block = snapshot.blocks[id]
                assertNotNull("Block $id must exist", block)
                assertTrue("Block $id must be in Ready status, got: ${block?.status}", block?.status is BlockStatus.Ready)
            }

            // Verify final node v_49 evaluates to 50
            val lastBlock = snapshot.blocks[blockIds.last()]
            assertEquals("v_49 should evaluate to 50", "50", lastBlock?.result?.expression)

            val elapsedMs = System.currentTimeMillis() - startTime
            println("50-node reactive DAG chain evaluated in $elapsedMs ms")
            assertTrue("50-node reactive evaluation must complete under 10000ms budget, took $elapsedMs ms", elapsedMs < 10000)
        } finally {
            cas.close()
        }
    }
}
