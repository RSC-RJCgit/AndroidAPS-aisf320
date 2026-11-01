package app.aaps.ui.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.TextView

/**
 * One chart for all of the Statistics tabs ported from v4 (2026-11-01, per explicit request): shaded bands, lines with
 * gaps, bars, dashed threshold lines and day lines, on a value axis at the left and labels under the chart. v4 draws
 * each of its charts on a canvas the same way; this keeps the parts in one place instead of four.
 *
 * x runs from 0 to [xMax]; y values are in the caller's units (mg/dL for glucose, U for insulin).
 */
class StatsChartView(context: Context) : View(context) {

    class Band(val points: List<Triple<Float, Double, Double>>, val color: Int, val maxGap: Float = 1.01f)
    class Line(val points: List<Pair<Float, Double?>>, val color: Int, val widthDp: Float = 1.5f, val dashed: Boolean = false)
    class Bar(val x0: Float, val x1: Float, val value: Double, val color: Int, val label: String? = null)

    var xMax = 1f
    var yMin = 0.0
    var yMax = 1.0
    var yTicks: List<Pair<Double, String>> = emptyList()
    var xLabels: List<Pair<Float, String>> = emptyList()
    var verticalLines: List<Float> = emptyList()
    var thresholds: List<Pair<Double, Int>> = emptyList()
    var bands: List<Band> = emptyList()
    var lines: List<Line> = emptyList()
    var bars: List<Bar> = emptyList()
    var markers: List<Float> = emptyList()
    var gridColor = 0x40808080
    var textColor = TextView(context).currentTextColor
    var chartHeightDp = 220

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * resources.displayMetrics.scaledDensity }
    private val dash = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
    private val axisWidth get() = maxOf(40 * density, (yTicks.maxOfOrNull { text.measureText(it.second) } ?: 0f) + 8 * density)
    private val labelHeight get() = maxOf(16 * density, text.fontSpacing + 4 * density)
    private val plotRight get() = width - maxOf(8 * density, (xLabels.maxOfOrNull { text.measureText(it.second) } ?: 0f) / 2)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((320 * density).toInt(), widthMeasureSpec),
            resolveSize((chartHeightDp * density + labelHeight).toInt(), heightMeasureSpec)
        )
    }

    private fun x(value: Float) = axisWidth + (plotRight - axisWidth).coerceAtLeast(1f) * value / xMax.coerceAtLeast(0.001f)
    private fun y(value: Double): Float {
        val top = 4 * density
        val bottom = height - labelHeight
        val fraction = ((value - yMin) / (yMax - yMin).coerceAtLeast(0.001)).coerceIn(0.0, 1.0)
        return (bottom - (bottom - top) * fraction).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val bottom = height - labelHeight
        text.color = textColor
        // Value axis and grid
        paint.style = Paint.Style.STROKE
        paint.pathEffect = null
        paint.strokeWidth = 0.5f * density
        paint.color = gridColor
        text.textAlign = Paint.Align.RIGHT
        yTicks.forEach { (value, label) ->
            val y = y(value)
            canvas.drawLine(axisWidth, y, plotRight, y, paint)
            canvas.drawText(label, axisWidth - 4 * density, y + text.textSize / 3, text)
        }
        paint.pathEffect = dash
        verticalLines.forEach { canvas.drawLine(x(it), 0f, x(it), bottom, paint) }
        thresholds.forEach { (value, color) ->
            paint.color = color
            paint.strokeWidth = 1 * density
            canvas.drawLine(axisWidth, y(value), plotRight, y(value), paint)
        }
        paint.pathEffect = null
        canvas.save()
        canvas.clipRect(axisWidth, 0f, plotRight, bottom)
        // Bands, widest first as given
        paint.style = Paint.Style.FILL
        bands.forEach { band ->
            var segment = mutableListOf<Triple<Float, Double, Double>>()
            for (point in band.points) {
                val valid = point.first.isFinite() && point.second.isFinite() && point.third.isFinite()
                if (!valid || (segment.isNotEmpty() && point.first - segment.last().first > band.maxGap)) {
                    drawBand(canvas, segment, band.color)
                    segment = mutableListOf()
                }
                if (valid) segment.add(point)
            }
            drawBand(canvas, segment, band.color)
        }
        // Bars
        text.textAlign = Paint.Align.CENTER
        bars.forEach { bar ->
            if (!bar.value.isFinite()) return@forEach
            paint.color = bar.color
            canvas.drawRect(x(bar.x0) + density, y(bar.value), x(bar.x1) - density, y(yMin), paint)
            bar.label?.let { canvas.drawText(it, (x(bar.x0) + x(bar.x1)) / 2, y(bar.value) - 2 * density, text) }
        }
        // Lines, broken where a point has no value
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        lines.forEach { line ->
            paint.color = line.color
            paint.strokeWidth = line.widthDp * density
            paint.pathEffect = if (line.dashed) dash else null
            val path = Path()
            var drawing = false
            var segmentSize = 0
            var lastX = 0f
            var lastY = 0f
            line.points.forEach { (px, value) ->
                if (value == null || !value.isFinite()) {
                    if (segmentSize == 1) canvas.drawCircle(lastX, lastY, 2 * density, paint)
                    drawing = false
                    segmentSize = 0
                }
                else if (!drawing) {
                    path.moveTo(x(px), y(value)); drawing = true
                    segmentSize = 1
                } else {
                    path.lineTo(x(px), y(value))
                    segmentSize++
                }
                if (value != null && value.isFinite()) { lastX = x(px); lastY = y(value) }
            }
            if (segmentSize == 1) canvas.drawCircle(lastX, lastY, 2 * density, paint)
            canvas.drawPath(path, paint)
        }
        paint.pathEffect = null
        paint.color = textColor
        paint.strokeWidth = 1.5f * density
        markers.forEach { canvas.drawLine(x(it), 0f, x(it), bottom, paint) }
        canvas.restore()
        // Labels under the chart
        xLabels.forEach { (px, label) -> canvas.drawText(label, x(px), height - 4 * density, text) }
    }

    private fun drawBand(canvas: Canvas, points: List<Triple<Float, Double, Double>>, color: Int) {
        if (points.size == 1) {
            val (px, lower, upper) = points.single()
            paint.color = color
            paint.strokeWidth = 2 * density
            canvas.drawLine(x(px), y(lower), x(px), y(upper), paint)
            return
        }
        if (points.size < 2) return
        val path = Path()
        points.forEachIndexed { index, (px, _, upper) ->
            if (index == 0) path.moveTo(x(px), y(upper)) else path.lineTo(x(px), y(upper))
        }
        points.asReversed().forEach { (px, lower, _) -> path.lineTo(x(px), y(lower)) }
        path.close()
        paint.color = color
        canvas.drawPath(path, paint)
    }
}
