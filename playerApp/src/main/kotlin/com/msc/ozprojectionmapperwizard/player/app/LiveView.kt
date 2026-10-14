package com.msc.ozprojectionmapperwizard.player.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.msc.ozprojectionmapperwizard.core.model.Warp
import com.msc.ozprojectionmapperwizard.core.protocol.LiveState
import com.msc.ozprojectionmapperwizard.core.protocol.TestPattern

/**
 * Draws a live alignment frame: each surface's test pattern warped onto its corners, plus a
 * crosshair on the corner being moved. Canvas matrices are full 3x3 perspective transforms, so
 * this matches the homography the renderer uses.
 */
class LiveView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var state: LiveState? = null
        set(value) {
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.WHITE }
    private val matrix = Matrix()

    init {
        setBackgroundColor(Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        val state = state ?: return
        val output = state.mapping.output
        // The mapping is in projector pixels; scale in case the view isn't exactly that size.
        canvas.save()
        canvas.scale(width.toFloat() / output.width, height.toFloat() / output.height)

        state.mapping.surfaces.forEachIndexed { index, surface ->
            val quad = surface.warp as? Warp.Quad ?: return@forEachIndexed
            val selected = surface.id == state.selectedSurface
            val color = if (selected) Color.rgb(255, 196, 0) else PALETTE[index % PALETTE.size]
            val dst = FloatArray(8).also { a -> quad.corners.forEachIndexed { i, p -> a[2 * i] = p.x.toFloat(); a[2 * i + 1] = p.y.toFloat() } }
            matrix.setPolyToPoly(SRC, 0, dst, 0, 4)

            canvas.save()
            canvas.concat(matrix)
            drawPattern(canvas, state.pattern, color, state.faceNames[surface.faceId] ?: surface.id, selected)
            canvas.restore()
        }

        // Crosshair in output space so it stays sharp and the same size wherever the corner is.
        val selectedSurface = state.mapping.surfaces.firstOrNull { it.id == state.selectedSurface }
        val corner = state.selectedCorner
        val quad = selectedSurface?.warp as? Warp.Quad
        if (quad != null && corner != null && corner in quad.corners.indices) {
            val p = quad.corners[corner]
            val x = p.x.toFloat()
            val y = p.y.toFloat()
            line.color = Color.RED
            line.strokeWidth = 2f
            canvas.drawLine(x - 40, y, x + 40, y, line)
            canvas.drawLine(x, y - 40, x, y + 40, line)
            canvas.drawCircle(x, y, 12f, line)
        }
        canvas.restore()
    }

    /** Draws in the unit face space (0..UNIT on both axes, before the corner-pin matrix). */
    private fun drawPattern(canvas: Canvas, pattern: TestPattern, color: Int, name: String, selected: Boolean) {
        when (pattern) {
            TestPattern.WHITE -> {
                fill.color = if (selected) Color.rgb(255, 240, 200) else Color.WHITE
                canvas.drawRect(0f, 0f, UNIT, UNIT, fill)
            }
            TestPattern.GRID -> {
                fill.color = Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawRect(0f, 0f, UNIT, UNIT, fill)
                line.color = color
                line.strokeWidth = 3f
                for (i in 1 until GRID) {
                    val v = UNIT * i / GRID
                    canvas.drawLine(v, 0f, v, UNIT, line)
                    canvas.drawLine(0f, v, UNIT, v, line)
                }
                line.strokeWidth = 2f
                canvas.drawLine(0f, 0f, UNIT, UNIT, line)
                canvas.drawLine(UNIT, 0f, 0f, UNIT, line)
                text.textSize = UNIT / 10
                canvas.drawText(name, UNIT / 2, UNIT / 2 - UNIT / 20, text)
            }
            TestPattern.OUTLINE -> Unit
        }
        line.color = color
        line.strokeWidth = if (selected) 12f else 8f
        canvas.drawRect(0f, 0f, UNIT, UNIT, line)
    }

    private companion object {
        const val UNIT = 1000f
        const val GRID = 8
        val SRC = floatArrayOf(0f, 0f, UNIT, 0f, UNIT, UNIT, 0f, UNIT)
        val PALETTE = intArrayOf(
            Color.rgb(0, 200, 255), Color.rgb(80, 255, 120), Color.rgb(255, 90, 200), Color.rgb(160, 120, 255),
            Color.rgb(255, 140, 60), Color.rgb(0, 255, 220), Color.rgb(255, 255, 255), Color.rgb(120, 200, 255),
        )
    }
}
