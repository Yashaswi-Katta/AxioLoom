package com.axioloom.app.ml.hmer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.geometry.Rect
import com.axioloom.app.domain.model.TimedStroke
import com.axioloom.app.domain.model.isFiniteOrdered
import com.axioloom.app.domain.model.unionBounds
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** NCHW single-image tensor; each call to [asFloatBuffer] returns an independent read cursor. */
class RasterizedTensor internal constructor(
    val height: Int,
    val width: Int,
    private val values: FloatBuffer,
    val cropBounds: Rect,
    val contentHeight: Int,
    val contentWidth: Int
) {
    init {
        require(height > 0 && width > 0 && contentHeight > 0 && contentWidth > 0)
        require(values.isDirect && values.capacity() == height * width)
    }
    fun shape(): LongArray = longArrayOf(1L, 1L, height.toLong(), width.toLong())
    fun asFloatBuffer(): FloatBuffer = values.duplicate().apply { rewind() }
}

data class RasterDimensions(val contentWidth: Int, val contentHeight: Int, val tensorWidth: Int, val tensorHeight: Int, val scale: Float) {
    init { require(contentWidth > 0 && contentHeight > 0 && tensorWidth == contentWidth + 2 * PADDING_PX && tensorHeight == contentHeight + 2 * PADDING_PX) }
    companion object { const val PADDING_PX = 16 }
}

/**
 * Converts Ink centerlines to a black-background/white-ink, anti-aliased float image.
 * The rasterization is CPU/off-screen and intentionally separate from wet-ink frame rendering.
 */
class StrokeBitmapRasterizer {
    fun rasterize(strokes: List<TimedStroke>): RasterizedTensor {
        require(strokes.isNotEmpty()) { "Cannot rasterize an empty block" }
        val bounds = strokes.drop(1).fold(strokes.first().bounds) { box, stroke -> box.unionBounds(stroke.bounds) }
        require(bounds.isFiniteOrdered())
        val dimensions = planDimensions(bounds)
        val bitmap = Bitmap.createBitmap(dimensions.tensorWidth, dimensions.tensorHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = NORMALIZED_STROKE_WIDTH_PX
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                isDither = false
            }
            val offsetX = RasterDimensions.PADDING_PX + (dimensions.contentWidth - bounds.width * dimensions.scale) / 2f
            val offsetY = RasterDimensions.PADDING_PX + (dimensions.contentHeight - bounds.height * dimensions.scale) / 2f
            strokes.forEach { stroke ->
                val points = stroke.points
                val path = Path()
                path.moveTo(offsetX + (points.first().x - bounds.left) * dimensions.scale, offsetY + (points.first().y - bounds.top) * dimensions.scale)
                for (index in 1 until points.size) {
                    val point = points[index]
                    path.lineTo(offsetX + (point.x - bounds.left) * dimensions.scale, offsetY + (point.y - bounds.top) * dimensions.scale)
                }
                if (points.size == 1) {
                    canvas.drawCircle(pathPointX(points[0].x, bounds.left, dimensions.scale, offsetX), pathPointY(points[0].y, bounds.top, dimensions.scale, offsetY), NORMALIZED_STROKE_WIDTH_PX / 2f, paint.apply { style = Paint.Style.FILL })
                    paint.style = Paint.Style.STROKE
                } else {
                    canvas.drawPath(path, paint)
                }
            }
            val pixels = IntArray(dimensions.tensorWidth * dimensions.tensorHeight)
            bitmap.getPixels(pixels, 0, dimensions.tensorWidth, 0, 0, dimensions.tensorWidth, dimensions.tensorHeight)
            val buffer = ByteBuffer.allocateDirect(pixels.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
            pixels.forEach { pixel ->
                // RGB luminance keeps opaque black exactly 0 and anti-aliased white ink in [0,1].
                val red = Color.red(pixel); val green = Color.green(pixel); val blue = Color.blue(pixel)
                buffer.put((0.299f * red + 0.587f * green + 0.114f * blue) / 255f)
            }
            buffer.flip()
            return RasterizedTensor(dimensions.tensorHeight, dimensions.tensorWidth, buffer, bounds, dimensions.contentHeight, dimensions.contentWidth)
        } finally {
            bitmap.recycle()
        }
    }

    /** Pure shape planner: height=96 unless width forces a width-limited, 16-aligned height. */
    fun planDimensions(bounds: Rect): RasterDimensions {
        require(bounds.isFiniteOrdered()) { "Invalid raster bounds" }
        val sourceWidth = max(bounds.width, MIN_SOURCE_EXTENT_PX)
        val sourceHeight = max(bounds.height, MIN_SOURCE_EXTENT_PX)
        var scale = TARGET_HEIGHT_PX.toFloat() / sourceHeight
        val scaledWidth = sourceWidth * scale
        val contentWidth: Int
        val contentHeight: Int
        if (scaledWidth > MAX_CONTENT_WIDTH_PX.toFloat()) {
            scale = MAX_CONTENT_WIDTH_PX.toFloat() / sourceWidth
            contentWidth = MAX_CONTENT_WIDTH_PX
            contentHeight = roundUpToStride(max(1, ceil(sourceHeight * scale).toInt()))
        } else {
            contentHeight = TARGET_HEIGHT_PX
            contentWidth = max(1, ceil(scaledWidth).toInt())
        }
        require(contentWidth <= MAX_CONTENT_WIDTH_PX && contentHeight <= MAX_CONTENT_HEIGHT_PX) { "Expression is too large to rasterize safely" }
        val tensorWidth = contentWidth + 2 * RasterDimensions.PADDING_PX
        val tensorHeight = contentHeight + 2 * RasterDimensions.PADDING_PX
        require(tensorWidth.toLong() * tensorHeight <= MAX_TENSOR_PIXELS) { "Expression tensor exceeds the memory budget" }
        return RasterDimensions(contentWidth, contentHeight, tensorWidth, tensorHeight, scale)
    }

    private fun roundUpToStride(value: Int): Int = ((value + CNN_STRIDE_PX - 1) / CNN_STRIDE_PX) * CNN_STRIDE_PX
    private fun pathPointX(x: Float, left: Float, scale: Float, offset: Float) = offset + (x - left) * scale
    private fun pathPointY(y: Float, top: Float, scale: Float, offset: Float) = offset + (y - top) * scale

    private companion object {
        const val TARGET_HEIGHT_PX = 96
        const val MAX_CONTENT_WIDTH_PX = 512
        const val MAX_CONTENT_HEIGHT_PX = 4096
        const val MAX_TENSOR_PIXELS = 2_500_000L
        const val MIN_SOURCE_EXTENT_PX = 1f
        const val NORMALIZED_STROKE_WIDTH_PX = 3.5f
        const val CNN_STRIDE_PX = 16
    }
}
