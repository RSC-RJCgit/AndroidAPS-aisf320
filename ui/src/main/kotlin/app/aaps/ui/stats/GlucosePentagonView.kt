package app.aaps.ui.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The v4 glucose pentagon, using the same 14-day Dexcom TIR numbers and reference values. */
internal class GlucosePentagonView(
    context: Context,
    private val data: CgpData,
    private val labels: List<String>,
    private val values: List<String>,
    private val patientColor: Int,
    private val referenceColor: Int
) : View(context) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11 * resources.displayMetrics.scaledDensity
        color = TextView(context).currentTextColor
        textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = resolveSize((320 * density).toInt(), widthMeasureSpec)
        setMeasuredDimension(measuredWidth, resolveSize((measuredWidth / 1.2f).toInt(), heightMeasureSpec))
    }

    private fun point(axis: Int, radius: Float): Pair<Float, Float> {
        val angle = -PI / 2 + axis * 2 * PI / 5
        return width / 2f + (cos(angle) * radius).toFloat() to height / 2f + (sin(angle) * radius).toFloat()
    }

    private fun polygon(radii: List<Double>, radius: Float): Path = Path().apply {
        radii.forEachIndexed { axis, value ->
            val (x, y) = point(axis, radius * value.toFloat())
            if (axis == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = min(width, height) * 0.31f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = Color.GRAY
        for (scale in 1..4) canvas.drawPath(polygon(List(5) { scale / 4.0 }, radius), paint)
        for (axis in 0 until 5) {
            val (x, y) = point(axis, radius)
            canvas.drawLine(width / 2f, height / 2f, x, y, paint)
        }
        listOf(data.referenceValues to referenceColor, data.normalizedValues to patientColor).forEach { (radii, color) ->
            val shape = polygon(radii, radius)
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.alpha = 45
            canvas.drawPath(shape, paint)
            paint.style = Paint.Style.STROKE
            paint.alpha = 255
            paint.strokeWidth = 2 * density
            canvas.drawPath(shape, paint)
        }
        labels.forEachIndexed { axis, label ->
            val (x, y) = point(axis, radius + 22 * density)
            val half = (maxOf(text.measureText(label), text.measureText(values[axis])) / 2).coerceAtMost(width / 2f)
            val labelX = x.coerceIn(half, width - half)
            canvas.drawText(label, labelX, y, text)
            canvas.drawText(values[axis], labelX, y + text.fontSpacing, text)
        }
    }
}
