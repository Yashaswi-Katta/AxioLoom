package com.axioloom.app.ui.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import com.axioloom.app.domain.model.EquationBlock
import com.axioloom.app.domain.model.InkPoint
import com.axioloom.app.domain.model.TimedStroke
import java.util.UUID
import kotlin.math.hypot

enum class CanvasToolMode {
    DRAW,
    PAN_SELECT,
    ERASER,
    SCHEMATIC
}

/** Compose host for the low-latency Jetpack Ink wet layer, interactive tool modes, and gesture handling. */
@Composable
fun WetInkOverlay(
    viewport: ViewportState,
    toolMode: CanvasToolMode = CanvasToolMode.DRAW,
    blocks: List<EquationBlock> = emptyList(),
    selectedBlockId: UUID? = null,
    onSelectBlock: (UUID?) -> Unit = {},
    onMoveBlock: (UUID, Offset) -> Unit = { _, _ -> },
    onStartMoveBlock: (() -> Unit)? = null,
    onDeleteBlock: (UUID) -> Unit = {},
    onEraseAt: ((Offset, Float) -> Unit)? = null,
    onStrokeFinished: (Stroke) -> Unit,
    onTimedStrokeFinished: ((TimedStroke) -> Unit)? = null,
    onLongPressWorld: (Offset) -> Unit,
    onDoubleTapWorld: (screen: Offset, world: Offset) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val scale = viewport.scale
    val translation = viewport.translation
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            InkInputLayout(
                context = context,
                viewport = viewport,
                toolMode = toolMode,
                blocks = blocks,
                selectedBlockId = selectedBlockId,
                onSelectBlock = onSelectBlock,
                onMoveBlock = onMoveBlock,
                onStartMoveBlock = onStartMoveBlock,
                onDeleteBlock = onDeleteBlock,
                onEraseAt = onEraseAt,
                onStrokeFinished = onStrokeFinished,
                onTimedStrokeFinished = onTimedStrokeFinished,
                onLongPressWorld = onLongPressWorld,
                onDoubleTapWorld = onDoubleTapWorld
            )
        },
        update = { view ->
            view.toolMode = toolMode
            view.blocks = blocks
            view.selectedBlockId = selectedBlockId
            view.onSelectBlock = onSelectBlock
            view.onMoveBlock = onMoveBlock
            view.onStartMoveBlock = onStartMoveBlock
            view.onDeleteBlock = onDeleteBlock
            view.onEraseAt = onEraseAt
            view.onStrokeFinished = onStrokeFinished
            view.onTimedStrokeFinished = onTimedStrokeFinished
            view.onLongPressWorld = onLongPressWorld
            view.onDoubleTapWorld = onDoubleTapWorld
            view.updateViewport(viewport, scale, translation)
        }
    )
}

private class InkInputLayout(
    context: Context,
    private var viewport: ViewportState,
    var toolMode: CanvasToolMode,
    var blocks: List<EquationBlock>,
    var selectedBlockId: UUID?,
    var onSelectBlock: (UUID?) -> Unit,
    var onMoveBlock: (UUID, Offset) -> Unit,
    var onStartMoveBlock: (() -> Unit)? = null,
    var onDeleteBlock: (UUID) -> Unit,
    var onEraseAt: ((Offset, Float) -> Unit)?,
    var onStrokeFinished: (Stroke) -> Unit,
    var onTimedStrokeFinished: ((TimedStroke) -> Unit)?,
    var onLongPressWorld: (Offset) -> Unit,
    var onDoubleTapWorld: (screen: Offset, world: Offset) -> Unit
) : FrameLayout(context) {
    private val inkView: InProgressStrokesView? = runCatching { InProgressStrokesView(context) }.getOrNull()
    private val predictor = runCatching { MotionEventPredictor.newInstance(this) }.getOrNull()
    private val activeStrokes = linkedMapOf<Int, InProgressStrokeId>()
    private val fingerStrokePointers = mutableSetOf<Int>()
    private val previousFingerPositions = linkedMapOf<Int, Offset>()
    private var suppressCurrentGesture = false

    private var draggingBlockId: UUID? = null
    private var lastDragWorld: Offset = Offset.Zero
    private var lastPanScreen: Offset? = null

    private val brush: Brush? = runCatching {
        Brush.createWithColorIntArgb(
            family = StockBrushes.markerLatest,
            colorIntArgb = Color.WHITE,
            size = 3.5f,
            epsilon = 0.1f
        )
    }.getOrNull()

    private val isNativeInkAvailable: Boolean get() = inkView != null && brush != null

    // Pure Canvas fallback paths in case native Jetpack Ink is unsupported on host hardware
    private val fallbackPoints = mutableListOf<InkPoint>()
    private val fallbackPath = android.graphics.Path()
    private var fallbackStrokeStartTime = 0L
    private var eraserScreenPos: Offset? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true
        override fun onDoubleTap(event: MotionEvent): Boolean {
            val pointer = event.actionIndex.coerceIn(0, event.pointerCount - 1)
            val screenPos = Offset(event.getX(pointer), event.getY(pointer))
            val worldPos = viewport.screenToWorld(screenPos)
            onDoubleTapWorld(screenPos, worldPos)
            return true
        }
        override fun onLongPress(event: MotionEvent) {
            val pointer = event.actionIndex.coerceIn(0, event.pointerCount - 1)
            if (event.getToolType(pointer) != MotionEvent.TOOL_TYPE_STYLUS &&
                event.getToolType(pointer) != MotionEvent.TOOL_TYPE_ERASER
            ) {
                inkView?.let { v ->
                    activeStrokes.values.forEach { v.cancelStroke(it, event) }
                }
                activeStrokes.clear()
                fingerStrokePointers.clear()
                suppressCurrentGesture = true
                onLongPressWorld(viewport.screenToWorld(Offset(event.getX(pointer), event.getY(pointer))))
            }
        }
    })

    private val finishedListener = object : InProgressStrokesFinishedListener {
        override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
            strokes.values.forEach { stroke: Stroke -> onStrokeFinished(stroke) }
            inkView?.removeFinishedStrokes(strokes.keys)
        }
    }

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = true
        val v = inkView
        val b = brush
        if (v != null && b != null) {
            runCatching {
                addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                v.addFinishedStrokesListener(finishedListener)
                updateViewport(viewport)
            }.onFailure { error ->
                Log.w("WetInkOverlay", "InProgressStrokesView attachment failed, using fallback canvas", error)
            }
        } else {
            Log.i("WetInkOverlay", "Native InProgressStrokesView not available; standard canvas fallback engaged.")
        }
    }

    fun updateViewport(
        next: ViewportState,
        scale: Float = next.scale,
        translation: Offset = next.translation
    ) {
        viewport = next
        inkView?.let { v ->
            runCatching {
                v.motionEventToViewTransform = viewport.screenToWorldMatrix()
            }
        }
        postInvalidateOnAnimation()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    private var hoverScreenPos: Offset? = null

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE || event.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
            hoverScreenPos = Offset(event.x, event.y)
            invalidate()
            return true
        } else if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            hoverScreenPos = null
            invalidate()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.concat(viewport.worldToScreenMatrix())
        runCatching { super.dispatchDraw(canvas) }

        if (!fallbackPath.isEmpty) {
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3.5f
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
                isAntiAlias = true
            }
            canvas.drawPath(fallbackPath, paint)
        }
        canvas.restoreToCount(save)

        hoverScreenPos?.let { pos ->
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#FFB300")
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2.5f
                isAntiAlias = true
                alpha = 180
            }
            canvas.drawCircle(pos.x, pos.y, 14f, paint)
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawCircle(pos.x, pos.y, 2.5f, paint)
        }

        eraserScreenPos?.let { pos ->
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#D4515C") // Crimson
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2.0f
                isAntiAlias = true
            }
            canvas.drawCircle(pos.x, pos.y, 22f, paint)
            paint.style = android.graphics.Paint.Style.FILL
            paint.alpha = 35
            canvas.drawCircle(pos.x, pos.y, 22f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        hoverScreenPos = null

        // Hardware Stylus Barrel Button Erase Shortcut
        val isBarrelButton = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        if (isBarrelButton) {
            val screenPos = Offset(event.x, event.y)
            val worldPos = viewport.screenToWorld(screenPos)
            val radius = 24f / viewport.scale
            eraserScreenPos = screenPos
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    onEraseAt?.invoke(worldPos, radius)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    eraserScreenPos = null
                    invalidate()
                }
            }
            if (activeStrokes.isNotEmpty()) {
                inkView?.let { v -> activeStrokes.values.forEach { v.cancelStroke(it, event) } }
                activeStrokes.clear()
            }
            return true
        }
        // Mode 1: Pan / Select Tool
        if (toolMode == CanvasToolMode.PAN_SELECT) {
            val screenPos = Offset(event.x, event.y)
            val worldPos = viewport.screenToWorld(screenPos)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hit = blocks.firstOrNull { b ->
                        val bounds = b.bounds
                        worldPos.x in (bounds.left - 20f)..(bounds.right + 20f) &&
                            worldPos.y in (bounds.top - 20f)..(bounds.bottom + 20f)
                    }
                    if (hit != null) {
                        draggingBlockId = hit.id
                        onSelectBlock(hit.id)
                        lastDragWorld = worldPos
                        lastPanScreen = null
                        onStartMoveBlock?.invoke()
                    } else {
                        draggingBlockId = null
                        onSelectBlock(null)
                        lastPanScreen = screenPos
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (draggingBlockId != null) {
                        val delta = worldPos - lastDragWorld
                        if (delta != Offset.Zero) {
                            onMoveBlock(draggingBlockId!!, delta)
                            lastDragWorld = worldPos
                        }
                    } else if (lastPanScreen != null) {
                        val deltaScreen = screenPos - lastPanScreen!!
                        viewport.panBy(deltaScreen)
                        lastPanScreen = screenPos
                        inkView?.let { v ->
                            runCatching { v.motionEventToViewTransform = viewport.screenToWorldMatrix() }
                        }
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingBlockId = null
                    lastPanScreen = null
                }
            }
            return true
        }

        // Mode 2: Eraser Tool
        if (toolMode == CanvasToolMode.ERASER) {
            val screenPos = Offset(event.x, event.y)
            val worldPos = viewport.screenToWorld(screenPos)
            val radius = 24f / viewport.scale
            eraserScreenPos = screenPos
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    onEraseAt?.invoke(worldPos, radius)
                    val hit = blocks.firstOrNull { b ->
                        val bounds = b.bounds
                        worldPos.x in (bounds.left - 16f)..(bounds.right + 16f) &&
                            worldPos.y in (bounds.top - 16f)..(bounds.bottom + 16f)
                    }
                    if (hit != null && (onEraseAt == null || hit.strokes.all { !it.hasNativeStroke })) {
                        onDeleteBlock(hit.id)
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    eraserScreenPos = null
                    invalidate()
                }
            }
            if (activeStrokes.isNotEmpty()) {
                inkView?.let { v -> activeStrokes.values.forEach { v.cancelStroke(it, event) } }
                activeStrokes.clear()
            }
            return true
        }

        // Process 2-finger pan/zoom gesture detector across all drawing engines
        runCatching { gestureDetector.onTouchEvent(event) }

        // Mode 3: Draw Tool (Standard Canvas Fallback if native Ink view unavailable)
        val nativeView = inkView
        val nativeBrush = brush
        if (!isNativeInkAvailable || nativeView == null || nativeBrush == null) {
            val worldPos = viewport.screenToWorld(Offset(event.x, event.y))
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fallbackPoints.clear()
                    fallbackPath.reset()
                    fallbackStrokeStartTime = SystemClock.elapsedRealtime()
                    fallbackPoints.add(InkPoint(worldPos.x, worldPos.y, 0L))
                    fallbackPath.moveTo(worldPos.x, worldPos.y)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val lastElapsed = fallbackPoints.lastOrNull()?.elapsedTimeMillis ?: 0L
                    val elapsed = (SystemClock.elapsedRealtime() - fallbackStrokeStartTime).coerceAtLeast(lastElapsed)
                    fallbackPoints.add(InkPoint(worldPos.x, worldPos.y, elapsed))
                    fallbackPath.lineTo(worldPos.x, worldPos.y)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val lastElapsed = fallbackPoints.lastOrNull()?.elapsedTimeMillis ?: 0L
                    val elapsed = (SystemClock.elapsedRealtime() - fallbackStrokeStartTime).coerceAtLeast(lastElapsed)
                    fallbackPoints.add(InkPoint(worldPos.x, worldPos.y, elapsed))
                    if (fallbackPoints.size >= 2) {
                        val stroke = TimedStroke.fromPoints(
                            points = fallbackPoints.toList(),
                            finishedAtMillis = SystemClock.elapsedRealtime()
                        )
                        onTimedStrokeFinished?.invoke(stroke)
                    }
                    fallbackPoints.clear()
                    fallbackPath.reset()
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> {
                    fallbackPoints.clear()
                    fallbackPath.reset()
                    invalidate()
                }
            }
            return true
        }

        // Mode 3: Jetpack Ink native accelerated drawing & 2-finger pan/zoom
        runCatching { predictor?.record(event) }
        if (suppressCurrentGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                suppressCurrentGesture = false
                activeStrokes.values.forEach { strokeId ->
                    runCatching { nativeView.cancelStroke(strokeId, event) }
                }
                activeStrokes.clear()
                fingerStrokePointers.clear()
                previousFingerPositions.clear()
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                suppressCurrentGesture = false
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                if (isStylus(event, index)) {
                    previousFingerPositions.clear()
                    runCatching { requestUnbufferedDispatch(event) }
                    runCatching {
                        activeStrokes[pointerId] = nativeView.startStroke(event, pointerId, nativeBrush)
                    }
                } else if (isFinger(event, index)) {
                    previousFingerPositions.clear()
                    runCatching { requestUnbufferedDispatch(event) }
                    runCatching {
                        activeStrokes[pointerId] = nativeView.startStroke(event, pointerId, nativeBrush)
                        fingerStrokePointers.add(pointerId)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                if (isStylus(event, index)) {
                    previousFingerPositions.clear()
                    runCatching { requestUnbufferedDispatch(event) }
                    runCatching {
                        activeStrokes[pointerId] = nativeView.startStroke(event, pointerId, nativeBrush)
                    }
                } else if (isFinger(event, index)) {
                    fingerStrokePointers.forEach { activeFingerPointerId ->
                        val strokeId = activeStrokes.remove(activeFingerPointerId)
                        if (strokeId != null) {
                            runCatching { nativeView.cancelStroke(strokeId, event) }
                        }
                    }
                    fingerStrokePointers.clear()
                    previousFingerPositions.clear()
                    updateFingerPositions(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeStrokes.isNotEmpty()) {
                    val predicted = runCatching { predictor?.predict() }.getOrNull()
                    try {
                        activeStrokes.forEach { (pointerId, strokeId) ->
                            runCatching { nativeView.addToStroke(event, pointerId, strokeId, predicted) }
                        }
                    } finally {
                        predicted?.recycle()
                    }
                } else {
                    updateFingerPositions(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                if (activeStrokes.isNotEmpty()) {
                    finishPointer(event, index)
                } else {
                    updateFingerPositions(event)
                    previousFingerPositions.remove(event.getPointerId(index))
                    updateFingerPositions(event, excludedPointerIndex = index)
                }
            }
            MotionEvent.ACTION_UP -> {
                val index = event.actionIndex
                if (activeStrokes.isNotEmpty()) finishPointer(event, index)
                activeStrokes.clear()
                fingerStrokePointers.clear()
                previousFingerPositions.clear()
                suppressCurrentGesture = false
            }
            MotionEvent.ACTION_CANCEL -> {
                activeStrokes.values.forEach { strokeId ->
                    runCatching { nativeView.cancelStroke(strokeId, event) }
                }
                activeStrokes.clear()
                fingerStrokePointers.clear()
                previousFingerPositions.clear()
                suppressCurrentGesture = false
            }
        }
        return true
    }

    private fun finishPointer(event: MotionEvent, index: Int) {
        val pointerId = event.getPointerId(index)
        val strokeId = activeStrokes.remove(pointerId) ?: return
        fingerStrokePointers.remove(pointerId)
        val v = inkView ?: return
        if ((event.flags and MotionEvent.FLAG_CANCELED) != 0 || suppressCurrentGesture) {
            runCatching { v.cancelStroke(strokeId, event) }
        } else {
            runCatching { v.finishStroke(event, pointerId, strokeId) }
        }
    }

    private fun updateFingerPositions(event: MotionEvent, excludedPointerIndex: Int = -1) {
        val current = linkedMapOf<Int, Offset>()
        for (index in 0 until event.pointerCount) {
            if (index == excludedPointerIndex || !isFinger(event, index)) continue
            current[event.getPointerId(index)] = Offset(event.getX(index), event.getY(index))
        }
        val commonIds = current.keys.intersect(previousFingerPositions.keys).sorted()
        if (commonIds.size >= 2) {
            val first = commonIds[0]
            val second = commonIds[1]
            val oldA = previousFingerPositions.getValue(first)
            val oldB = previousFingerPositions.getValue(second)
            val newA = current.getValue(first)
            val newB = current.getValue(second)
            val oldCenter = (oldA + oldB) / 2f
            val newCenter = (newA + newB) / 2f
            val oldDistance = hypot((oldB.x - oldA.x).toDouble(), (oldB.y - oldA.y).toDouble()).toFloat()
            val newDistance = hypot((newB.x - newA.x).toDouble(), (newB.y - newA.y).toDouble()).toFloat()
            if (oldDistance > 0.5f) viewport.applyGesture(oldCenter, newCenter, newDistance / oldDistance)
        } else if (commonIds.size == 1) {
            val id = commonIds.single()
            viewport.panBy(current.getValue(id) - previousFingerPositions.getValue(id))
        }
        previousFingerPositions.clear()
        previousFingerPositions.putAll(current)
        inkView?.let { v ->
            runCatching { v.motionEventToViewTransform = viewport.screenToWorldMatrix() }
        }
        invalidate()
    }

    private fun isStylus(event: MotionEvent, index: Int): Boolean =
        event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS ||
            event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER

    private fun isFinger(event: MotionEvent, index: Int): Boolean =
        event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER
}
