package com.axioloom.app.ui.renderer

import android.graphics.Canvas
import android.graphics.Color
import android.util.LruCache
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.max
import kotlin.math.roundToInt

/** Caches native Android LaTeX drawables; no WebView or HTML renderer is used. */
class LatexCanvasRenderer(cacheEntries: Int = 96) {
    private data class Key(val latex: String, val textSizePx: Int, val color: Int)
    private val cache = LruCache<Key, JLatexMathDrawable>(cacheEntries)

    fun drawAt(
        canvas: Canvas,
        latex: String,
        left: Float,
        centerY: Float,
        targetHeight: Float,
        color: Int = Color.WHITE
    ) {
        val sizePx = max(MIN_TEXT_SIZE_PX, targetHeight).roundToInt().coerceAtMost(MAX_TEXT_SIZE_PX)
        val key = Key(latex, sizePx, color)
        val drawable = cache.get(key) ?: runCatching {
            JLatexMathDrawable.builder(latex)
                .textSize(sizePx.toFloat())
                .color(color)
                .build()
        }.getOrNull()?.also { cache.put(key, it) }

        if (drawable != null) {
            val sourceWidth = drawable.intrinsicWidth.coerceAtLeast(1)
            val sourceHeight = drawable.intrinsicHeight.coerceAtLeast(1)
            val scale = targetHeight.coerceAtLeast(MIN_TEXT_SIZE_PX) / sourceHeight.toFloat()
            val save = canvas.save()
            try {
                canvas.translate(left, centerY - sourceHeight * scale / 2f)
                canvas.scale(scale, scale)
                drawable.setBounds(0, 0, sourceWidth, sourceHeight)
                drawable.draw(canvas)
            } finally {
                canvas.restoreToCount(save)
            }
        } else {
            // Robust plain text fallback when JLatexMath cannot parse complex or non-standard syntax
            val clean = latex.replace("\\\\", "")
                .replace("\\cdot", "·")
                .replace("\\times", "×")
                .replace("\\frac{", "(")
                .replace("}{", "/")
                .replace("}", ")")
                .replace("{", "")
                .replace("}", "")
                .trim()
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = max(MIN_TEXT_SIZE_PX, targetHeight * 0.75f)
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val fontMetrics = paint.fontMetrics
            val textY = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f
            canvas.drawText(clean, left, textY, paint)
        }
    }

    fun clear() = cache.evictAll()

    private companion object {
        const val MIN_TEXT_SIZE_PX = 18f
        const val MAX_TEXT_SIZE_PX = 160
    }
}
