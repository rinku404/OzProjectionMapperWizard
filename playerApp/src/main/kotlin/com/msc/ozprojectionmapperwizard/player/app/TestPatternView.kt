package com.msc.ozprojectionmapperwizard.player.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Grid, centre cross and corner markers, for focusing the projector and checking the image edges. */
class TestPatternView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val grid = Paint().apply { color = Color.rgb(90, 90, 90); strokeWidth = 1f }
    private val edge = Paint().apply { color = Color.WHITE; strokeWidth = 4f; style = Paint.Style.STROKE }
    private val corner = Paint().apply { color = Color.rgb(0, 200, 255); strokeWidth = 6f }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Color.BLACK)
        val step = h / 12f
        var x = 0f
        while (x <= w) { canvas.drawLine(x, 0f, x, h, grid); x += step }
        var y = 0f
        while (y <= h) { canvas.drawLine(0f, y, w, y, grid); y += step }
        canvas.drawRect(2f, 2f, w - 2f, h - 2f, edge)
        canvas.drawLine(w / 2, 0f, w / 2, h, edge)
        canvas.drawLine(0f, h / 2, w, h / 2, edge)
        val len = step * 1.5f
        for ((cx, cy) in listOf(0f to 0f, w to 0f, w to h, 0f to h)) {
            val dx = if (cx == 0f) len else -len
            val dy = if (cy == 0f) len else -len
            canvas.drawLine(cx, cy, cx + dx, cy, corner)
            canvas.drawLine(cx, cy, cx, cy + dy, corner)
        }
    }
}
