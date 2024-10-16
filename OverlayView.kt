package com.surendramaran.yolov8tflite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results = listOf<BoundingBox>()
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()

    private var bounds = Rect()

    private val labelColors = mapOf(
        "BIODEGRADABLE" to Color.GREEN,
        "CARDBOARD" to Color.YELLOW,
        "CLOTH" to Color.MAGENTA,
        "GLASS" to Color.CYAN,
        "METAL" to Color.BLUE,
        "PAPER" to Color.WHITE,
        "PLASTIC" to Color.RED,
        "TRASH" to Color.BLACK
    )

    init {
        initPaints()
    }

    fun clear() {
        results = listOf() // Limpia los resultados
        invalidate() // Fuerza un redibujado
    }

    private fun initPaints() {
        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.textSize = 50f

        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f

        boxPaint.strokeWidth = 8F
        boxPaint.style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Limpia el canvas completamente
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // Si no hay resultados, no dibuja nada
        if (results.isEmpty()) {
            return
        }

        results.forEach {
            boxPaint.color = labelColors[it.clsName] ?: Color.WHITE

            val left = it.x1 * width
            val top = it.y1 * height
            val right = it.x2 * width
            val bottom = it.y2 * height

            canvas.drawRect(left, top, right, bottom, boxPaint)

            val drawableText = it.clsName
            textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
            val textWidth = bounds.width()
            val textHeight = bounds.height()
            canvas.drawRect(
                left,
                top,
                left + textWidth + BOUNDING_RECT_TEXT_PADDING,
                top + textHeight + BOUNDING_RECT_TEXT_PADDING,
                textBackgroundPaint
            )

            canvas.drawText(drawableText, left, top + bounds.height(), textPaint)
        }
    }

    fun setResults(boundingBoxes: List<BoundingBox>) {
        results = boundingBoxes
        invalidate()
    }

    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }
}
