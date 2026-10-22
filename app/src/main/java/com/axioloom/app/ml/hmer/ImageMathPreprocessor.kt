package com.axioloom.app.ml.hmer

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * 100% Offline Adaptive Sauvola/Bradley-Roth Binarization & Connected-Component Denoiser.
 * Preprocesses camera snaps, textbook crops, or whiteboard photos into clean,
 * CROHME-normalized [RasterizedTensor] suitable for onnx HMER inference.
 */
object ImageMathPreprocessor {

    /**
     * Preprocesses an Android [Bitmap] into an HMER-normalized [RasterizedTensor].
     */
    fun preprocess(bitmap: Bitmap): RasterizedTensor {
        // 1. Downsample if larger than 1024px in any dimension
        val maxDim = max(bitmap.width, bitmap.height)
        val workingBitmap = if (maxDim > 1024) {
            val scale = 1024f / maxDim
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * scale).toInt()),
                max(1, (bitmap.height * scale).toInt()),
                true
            )
        } else {
            bitmap
        }

        val w = workingBitmap.width
        val h = workingBitmap.height

        val pixels = IntArray(w * h)
        workingBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val luminance = IntArray(w * h)
        for (i in 0 until w * h) {
            val p = pixels[i]
            val red = (p shr 16) and 0xFF
            val green = (p shr 8) and 0xFF
            val blue = p and 0xFF
            luminance[i] = (0.299 * red + 0.587 * green + 0.114 * blue).roundToInt().coerceIn(0, 255)
        }

        val result = preprocessLuminanceGrid(w, h, luminance)

        if (workingBitmap !== bitmap) {
            workingBitmap.recycle()
        }

        return result
    }

    /**
     * Pure JVM-compatible normalization pipeline from raw grayscale luminance grid to [RasterizedTensor].
     */
    fun preprocessLuminanceGrid(
        w: Int,
        h: Int,
        luminance: IntArray
    ): RasterizedTensor {
        // 2. Sauvola binarization
        val binary = binarizeLuminance(luminance, w, h)

        // 3. Connected component noise filtering
        val denoised = removeSpeckles(binary, w, h, minArea = 6)

        // 4. Find bounding box of foreground ink pixels
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (denoised[y * w + x]) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            // No ink found; create an empty 96x96 tensor with padding
            val tensorH = 96 + 32
            val tensorW = 96 + 32
            val buffer = ByteBuffer.allocateDirect(tensorH * tensorW * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            return RasterizedTensor(tensorH, tensorW, buffer, Rect(0f, 0f, 96f, 96f), 96, 96)
        }

        val cropW = maxX - minX + 1
        val cropH = maxY - minY + 1
        val cropBounds = Rect(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat())

        // 5. Dimension planning matching StrokeBitmapRasterizer
        val rasterizer = StrokeBitmapRasterizer()
        val dims = rasterizer.planDimensions(Rect(0f, 0f, cropW.toFloat(), cropH.toFloat()))

        val tensorH = dims.tensorHeight
        val tensorW = dims.tensorWidth
        val buffer = ByteBuffer.allocateDirect(tensorH * tensorW * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        val tensorFloats = FloatArray(tensorH * tensorW)

        val scale = min(
            dims.contentWidth.toFloat() / cropW,
            dims.contentHeight.toFloat() / cropH
        )

        val offX = RasterDimensions.PADDING_PX + (dims.contentWidth - cropW * scale) / 2f
        val offY = RasterDimensions.PADDING_PX + (dims.contentHeight - cropH * scale) / 2f

        for (cy in 0 until cropH) {
            val srcY = minY + cy
            for (cx in 0 until cropW) {
                val srcX = minX + cx
                if (denoised[srcY * w + srcX]) {
                    val tx = (offX + cx * scale).roundToInt().coerceIn(0, tensorW - 1)
                    val ty = (offY + cy * scale).roundToInt().coerceIn(0, tensorH - 1)
                    tensorFloats[ty * tensorW + tx] = 1.0f
                }
            }
        }

        buffer.put(tensorFloats)
        buffer.flip()

        return RasterizedTensor(tensorH, tensorW, buffer, cropBounds, dims.contentHeight, dims.contentWidth)
    }

    /**
     * Sauvola Adaptive Local Thresholding using O(1) integral images.
     * Returns a BooleanArray where true = foreground ink.
     */
    fun binarizeLuminance(
        luminance: IntArray,
        width: Int,
        height: Int,
        k: Double = 0.2,
        rParam: Double = 128.0
    ): BooleanArray {
        val n = width * height
        // Compute Integral Image S and Squared Integral Image S2 in O(W*H)
        val s = DoubleArray((width + 1) * (height + 1))
        val s2 = DoubleArray((width + 1) * (height + 1))

        val stride = width + 1
        for (y in 0 until height) {
            var rowSum = 0.0
            var rowSumSq = 0.0
            for (x in 0 until width) {
                val lum = luminance[y * width + x].toDouble()
                rowSum += lum
                rowSumSq += lum * lum

                val idx = (y + 1) * stride + (x + 1)
                val idxAbove = y * stride + (x + 1)
                s[idx] = s[idxAbove] + rowSum
                s2[idx] = s2[idxAbove] + rowSumSq
            }
        }

        val windowSize = max(15, min(width, height) / 16)
        val rad = windowSize / 2
        val binary = BooleanArray(n)
        var darkCount = 0

        for (y in 0 until height) {
            val y1 = max(0, y - rad)
            val y2 = min(height - 1, y + rad)
            for (x in 0 until width) {
                val x1 = max(0, x - rad)
                val x2 = min(width - 1, x + rad)

                val count = (x2 - x1 + 1) * (y2 - y1 + 1)

                // Query window sum from integral image: S(x2, y2) - S(x1-1, y2) - S(x2, y1-1) + S(x1-1, y1-1)
                val sum = s[(y2 + 1) * stride + (x2 + 1)] - s[(y2 + 1) * stride + x1] - s[y1 * stride + (x2 + 1)] + s[y1 * stride + x1]
                val sumSq = s2[(y2 + 1) * stride + (x2 + 1)] - s2[(y2 + 1) * stride + x1] - s2[y1 * stride + (x2 + 1)] + s2[y1 * stride + x1]

                val mean = sum / count
                val variance = max(0.0, (sumSq / count) - (mean * mean))
                val stdev = sqrt(variance)

                val threshold = mean * (1.0 + k * (stdev / rParam - 1.0))
                val isDark = luminance[y * width + x] < threshold
                binary[y * width + x] = isDark
                if (isDark) darkCount++
            }
        }

        // Polarity check: if foreground pixels > 50%, invert
        if (darkCount > n / 2) {
            for (i in 0 until n) {
                binary[i] = !binary[i]
            }
        }

        return binary
    }

    /**
     * 8-connected component labeling to discard speckles and noise blobs with area < minArea.
     */
    fun removeSpeckles(binary: BooleanArray, width: Int, height: Int, minArea: Int = 6): BooleanArray {
        val n = width * height
        val visited = BooleanArray(n)
        val result = binary.clone()

        val queue = IntArray(n)
        val compPixels = IntArray(n)

        for (i in 0 until n) {
            if (!result[i] || visited[i]) continue

            var qHead = 0
            var qTail = 0
            var compSize = 0

            queue[qTail++] = i
            visited[i] = true

            while (qHead < qTail) {
                val curr = queue[qHead++]
                compPixels[compSize++] = curr
                val cx = curr % width
                val cy = curr / width

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx
                        val ny = cy + dy
                        if (nx in 0 until width && ny in 0 until height) {
                            val nIdx = ny * width + nx
                            if (result[nIdx] && !visited[nIdx]) {
                                visited[nIdx] = true
                                queue[qTail++] = nIdx
                            }
                        }
                    }
                }
            }

            // If area < minArea, delete from result
            if (compSize < minArea) {
                for (k in 0 until compSize) {
                    result[compPixels[k]] = false
                }
            }
        }

        return result
    }
}
