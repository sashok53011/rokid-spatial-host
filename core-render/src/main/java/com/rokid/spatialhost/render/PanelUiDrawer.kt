package com.rokid.spatialhost.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.rokid.spatialhost.scene.SpatialPanel
import com.rokid.spatialhost.scene.UvRect
import com.rokid.spatialhost.scene.Widget

/**
 * Draws a panel (title + widgets) into the bitmap that becomes the panel
 * texture. Canvas coordinates are y-down, so widget UVs (v up) map via
 * `y = (1 - v) * height`; the `panel_ui` material flips V back when
 * sampling (see MaterialFactory.buildPanelUi).
 *
 * Fixed texture size — panels are UI cards, ~500 px/m at arm's length is
 * plenty for readable text at2.5 m.
 */
object PanelUiDrawer {

    const val WIDTH = 1024
    const val HEIGHT = 576

    private const val PAD = 28f
    private const val HEADER_H = 96f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(16, 22, 32)
        style = Paint.Style.FILL
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(24, 33, 47)
        style = Paint.Style.FILL
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(210, 224, 240)
        textSize = 44f
        isFakeBoldText = true
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(46, 128, 176)
        style = Paint.Style.FILL
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(52, 64, 82)
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(90, 190, 235)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(176, 194, 214)
        textSize = 36f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 200, 240)
        textSize = 34f
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(38, 54, 74)
        style = Paint.Style.FILL
    }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(226, 238, 250)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(90, 210, 150)
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Redraw [panel] (its label + widgets) into [bmp]. */
    fun draw(panel: SpatialPanel, bmp: Bitmap) {
        val canvas = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()

        canvas.drawRect(0f, 0f, w, h, bgPaint)
        canvas.drawRect(0f, 0f, w, HEADER_H, headerPaint)
        canvas.drawText(panel.label, PAD, HEADER_H * 0.68f, titlePaint)

        for (widget in panel.widgets) {
            val r = toCanvas(widget.rect, w, h)
            when (widget) {
                is Widget.Button -> drawButton(canvas, r, widget)
                is Widget.Toggle -> drawToggle(canvas, r, widget)
                is Widget.Slider -> drawSlider(canvas, r, widget)
                is Widget.Label -> canvas.drawText(widget.text, r.left, r.centerY() + 12f, labelPaint)
            }
        }
    }

    /** UV rect (v up) → canvas RectF (y down). */
    private fun toCanvas(rect: UvRect, w: Float, h: Float) = RectF(
        rect.x * w,
        (1f - (rect.y + rect.h)) * h,
        (rect.x + rect.w) * w,
        (1f - rect.y) * h,
    )

    private fun drawButton(canvas: Canvas, r: RectF, b: Widget.Button) {
        canvas.drawRoundRect(r, 18f, 18f, buttonPaint)
        // keep long labels inside the button
        val text = fitText(b.label, buttonTextPaint, r.width() - 24f)
        canvas.drawText(text, r.centerX(), r.centerY() + 14f, buttonTextPaint)
    }

    private fun drawToggle(canvas: Canvas, r: RectF, t: Widget.Toggle) {
        canvas.drawRoundRect(r, 18f, 18f, buttonPaint)
        val box = RectF(r.left + 14f, r.centerY() - 18f, r.left + 50f, r.centerY() + 18f)
        canvas.drawRoundRect(box, 8f, 8f, trackPaint)
        if (t.checked) {
            canvas.drawLine(box.left + 9f, box.centerY(), box.left + 18f, box.bottom - 9f, checkPaint)
            canvas.drawLine(box.left + 18f, box.bottom - 9f, box.right - 9f, box.top + 9f, checkPaint)
        }
        val text = fitText(t.label, buttonTextPaint, r.width() - 84f)
        canvas.drawText(text, r.left + 66f + (r.width() - 80f) / 2f - 6f, r.centerY() + 14f, buttonTextPaint)
    }

    private fun drawSlider(canvas: Canvas, r: RectF, s: Widget.Slider) {
        canvas.drawText(s.label, r.left, r.top - 10f, labelPaint)
        if (s.valueText.isNotEmpty()) {
            canvas.drawText(s.valueText, r.right, r.top - 10f, valuePaint.apply { textAlign = Paint.Align.RIGHT })
            valuePaint.textAlign = Paint.Align.LEFT // restore for other uses
        }
        val cy = r.centerY()
        val track = RectF(r.left, cy - 9f, r.right, cy + 9f)
        canvas.drawRoundRect(track, 9f, 9f, trackPaint)
        val t = if (s.max > s.min) (s.value - s.min) / (s.max - s.min) else 0f
        val thumbX = r.left + t.coerceIn(0f, 1f) * r.width()
        canvas.drawCircle(thumbX, cy, 22f, thumbPaint)
    }

    private fun fitText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var out = text
        while (out.length > 1 && paint.measureText("$out…") > maxWidth) {
            out = out.dropLast(1)
        }
        return "$out…"
    }
}
