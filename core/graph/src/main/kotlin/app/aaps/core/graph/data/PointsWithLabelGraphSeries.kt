package app.aaps.core.graph.data

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.graph.SeriesData
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.series.BaseSeries
import kotlin.math.min
import androidx.core.graphics.withSave
import androidx.core.graphics.withRotation

/**
 * Series that plots the data as points.
 * The points can be different shapes or a
 * complete custom drawing.
 *
 * @author jjoe64
 */
open class PointsWithLabelGraphSeries<E : DataPointWithLabelInterface> : BaseSeries<E>, SeriesData {

    companion object {
        var showSmbLabels: Boolean = true
    }

    // Default spSize
    private var spSize = 18

    fun setSpSize(size: Int) { spSize = size }

    /**
     * internal paint object
     */
    private lateinit var mPaint: Paint

    /**
     * creates the series without data
     */
    constructor() {
        init()
    }

    /**
     * creates the series with data
     *
     * @param data dataPoints
     */
    constructor(data: Array<E>?) : super(data) {
        init()
    }

    /**
     * init the internal objects
     * set the defaults
     */
    protected fun init() {
        mPaint = Paint()
        mPaint.strokeCap = Paint.Cap.ROUND
    }

    /**
     * plot the data to the viewport
     *
     * @param graphView     graphview
     * @param canvas        canvas to draw on
     * @param isSecondScale whether it is the second scale
     */
    @Suppress("deprecation")
    override fun draw(graphView: GraphView, canvas: Canvas, isSecondScale: Boolean) {
        // Convert the sp to pixels
        val scaledTextSize = spSize * graphView.context.resources.displayMetrics.scaledDensity
        val scaledPxSize = graphView.context.resources.displayMetrics.scaledDensity * 3f
        resetDataPoints()

        // get data
        val maxX = graphView.viewport.getMaxX(false)
        val minX = graphView.viewport.getMinX(false)
        val maxY: Double
        val minY: Double
        if (isSecondScale) {
            maxY = graphView.secondScale.maxY
            minY = graphView.secondScale.minY
        } else {
            maxY = graphView.viewport.getMaxY(false)
            minY = graphView.viewport.getMinY(false)
        }
        val values = getValues(minX, maxX)

        // draw background
        // draw data
        val diffY = maxY - minY
        val diffX = maxX - minX
        val graphHeight = graphView.graphContentHeight.toFloat()
        val graphWidth = graphView.graphContentWidth.toFloat()
        val graphLeft = graphView.graphContentLeft.toFloat()
        val graphTop = graphView.graphContentTop.toFloat()
        val scaleX = (graphWidth / diffX).toFloat()
        val smbStack = HashMap<Long, Int>() // bucket (5-min) -> count of SMBs drawn
        while (values.hasNext()) {
            val value = values.next() ?: break
            mPaint.color = value.color(graphView.context)
            val valY = value.y - minY
            val ratY = valY / diffY
            val y = graphHeight * ratY
            val valX = value.x - minX
            val ratX = valX / diffX
            var x = graphWidth * ratX

            // overdraw
            var overdraw = x > graphWidth
            // end right
            if (y < 0) { // end bottom
                overdraw = true
            }
            if (y > graphHeight) { // end top
                overdraw = true
            }
            val duration = value.duration
            val endWithDuration = (x + duration * scaleX + graphLeft + 1).toFloat()
            // cut off to graph start if needed
            if (x < 0 && endWithDuration > 0) {
                x = 0.0
            }

            /* Fix a bug that continue to show the DOT after Y axis */
            if (x < 0) {
                overdraw = true
            }
            val endX = x.toFloat() + (graphLeft + 1)
            val endY = (graphTop - y).toFloat() + graphHeight
            registerDataPoint(endX, endY, value)
            var xPlusLength = 0f
            if (duration > 0) {
                xPlusLength = min(endWithDuration, graphLeft + graphWidth)
            }

            // draw data point
            if (!overdraw) {
                if (value.shape == Shape.BG || value.shape == Shape.COB_FAIL_OVER) {
                    mPaint.style = value.paintStyle
                    mPaint.strokeWidth = 0f
                    canvas.drawCircle(endX, endY, value.size * scaledPxSize, mPaint)
                } else if (value.shape == Shape.BG || value.shape == Shape.IOB_PREDICTION || value.shape == Shape.BUCKETED_BG) {
                    mPaint.color = value.color(graphView.context)
                    mPaint.style = value.paintStyle
                    mPaint.strokeWidth = 0f
                    canvas.drawCircle(endX, endY, value.size * scaledPxSize, mPaint)
                } else if (value.shape == Shape.PREDICTION) {
                    mPaint.color = value.color(graphView.context)
                    mPaint.style = value.paintStyle
                    mPaint.strokeWidth = 0f
                    canvas.drawCircle(endX, endY, scaledPxSize, mPaint)
                    mPaint.style = value.paintStyle
                    mPaint.strokeWidth = 0f
                    canvas.drawCircle(endX, endY, scaledPxSize / 3, mPaint)
                } else if (value.shape == Shape.RECTANGLE) {
                    canvas.drawRect(endX - scaledPxSize, endY - scaledPxSize, endX + scaledPxSize, endY + scaledPxSize, mPaint)
                } else if (value.shape == Shape.TRIANGLE) {
                    mPaint.strokeWidth = 0f
                    val points = arrayOf(
                        Point(endX.toInt(), (endY - scaledPxSize).toInt()),
                        Point((endX + scaledPxSize).toInt(), (endY + scaledPxSize * 0.67).toInt()),
                        Point((endX - scaledPxSize).toInt(), (endY + scaledPxSize * 0.67).toInt())
                    )
                    drawArrows(points, canvas, mPaint)
                } else if (value.shape == Shape.CARBS) {
                    mPaint.strokeWidth = 0f
                    val points = arrayOf(
                        Point(endX.toInt(), (endY - scaledPxSize).toInt()),
                        Point((endX + scaledPxSize).toInt(), (endY + scaledPxSize * 0.67).toInt()),
                        Point((endX - scaledPxSize).toInt(), (endY + scaledPxSize * 0.67).toInt())
                    )
                    mPaint.style = Paint.Style.FILL_AND_STROKE
                    drawArrows(points, canvas, mPaint)
                    if (value.label.isNotEmpty()) drawLabel45Left(endX, endY, value, canvas, scaledPxSize, scaledTextSize * 0.7f)
                } else if (value.shape == Shape.SMB) {
                    val bucket = (value.x / 600_000L).toLong()
                    val stackIndex = smbStack.getOrDefault(bucket, 0)
                    smbStack[bucket] = stackIndex + 1
                    val size = value.size * scaledPxSize * 1.2f
                    val bgValY = value.labelY - minY
                    val bgRatY = bgValY / diffY
                    val bgEndY = (graphTop - graphHeight * bgRatY).toFloat() + graphHeight
                    // original blue triangle at baseline (IOB graph zero line)
                    mPaint.strokeWidth = 0f
                    mPaint.style = Paint.Style.FILL_AND_STROKE
                    val baseTriBase = endY + scaledPxSize * 0.67f
                    drawArrows(arrayOf(
                        Point(endX.toInt(), (endY - scaledPxSize).toInt()),
                        Point((endX + scaledPxSize).toInt(), baseTriBase.toInt()),
                        Point((endX - scaledPxSize).toInt(), baseTriBase.toInt())
                    ), canvas, mPaint)
                    // shaft below the baseline arrowhead — length scales with value.shaftLengthMultiplier (dose size).
                    // shaftLengthMultiplier >= 4 corresponds to dose >= 0.20U (1 + floor((amount-0.05)/0.05));
                    // thicker stroke for these larger doses.
                    mPaint.strokeWidth = if (value.shaftLengthMultiplier >= 4) 4f else 2f
                    mPaint.style = Paint.Style.STROKE
                    val baseShaftEnd = baseTriBase + scaledTextSize * 0.25f * value.shaftLengthMultiplier
                    canvas.drawLine(endX, baseTriBase, endX, baseShaftEnd, mPaint)
                    // arrowhead just below the relevant BGL point — ISF color if available, else yellow.
                    // Shaft here is the original fixed (non-dose-scaled) length; dose scaling lives on the
                    // baseline arrowhead's shaft above instead.
                    if (!value.hasColorOverride) mPaint.color = Color.YELLOW
                    val triTop = bgEndY + size
                    val triBase = triTop + size * 1.5f
                    val halfWidth = size * 0.25f
                    val points = arrayOf(
                        Point(endX.toInt(), triTop.toInt()),
                        Point((endX + halfWidth).toInt(), triBase.toInt()),
                        Point((endX - halfWidth).toInt(), triBase.toInt())
                    )
                    mPaint.style = Paint.Style.FILL_AND_STROKE
                    drawArrows(points, canvas, mPaint)
                    mPaint.strokeWidth = 2f
                    mPaint.style = Paint.Style.STROKE
                    val bgShaftEnd = triBase + scaledTextSize * 0.25f
                    canvas.drawLine(endX, triBase, endX, bgShaftEnd, mPaint)
                    // fast-rise indicator (factor*10, rounded) at the bottom of this shaft, if one fired for this dose.
                    // Stacked by stackIndex (same bucket as the dose label) since close-together SMBs share
                    // a similar BG position and would otherwise overlap here.
                    if (value.fastRiseLabel.isNotEmpty()) {
                        mPaint.style = Paint.Style.FILL
                        val fastRiseY = bgShaftEnd + scaledTextSize * 0.5f + stackIndex * (scaledTextSize * 0.6f)
                        drawLabelCentered(endX, fastRiseY, value, canvas, scaledTextSize * 0.5f, value.fastRiseLabel)
                    }
                    // label centered over the BGL dot instead of offset to the right
                    val displayedHours = (maxX - minX) / (1000.0 * 60 * 60)
                    if (showSmbLabels && displayedHours <= 15.0 && value.label.isNotEmpty()) {
                        val labelY = bgEndY - size - stackIndex * (scaledTextSize * 0.6f)
                        val savedColor = mPaint.color
                        if (!value.hasColorOverride) mPaint.color = Color.WHITE
                        mPaint.style = Paint.Style.FILL
                        drawLabelCentered(endX, labelY, value, canvas, scaledTextSize * 0.5f)
                        mPaint.color = savedColor
                    }
                } else if (value.shape == Shape.EXTENDEDBOLUS) {
                    mPaint.strokeWidth = 0f
                    if (value.label.isNotEmpty()) {
                        val bounds = Rect(endX.toInt(), endY.toInt() + 3, xPlusLength.toInt(), endY.toInt() + 8)
                        mPaint.style = Paint.Style.FILL_AND_STROKE
                        canvas.drawRect(bounds, mPaint)
                        mPaint.textSize = scaledTextSize
                        mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL))
                        mPaint.isFakeBoldText = true
                        canvas.drawText(value.label, endX, endY, mPaint)
                    }
                } else if (value.shape == Shape.HEART_RATE || value.shape === Shape.STEPS) {
                    mPaint.strokeWidth = 0f
                    val bounds = Rect(endX.toInt(), endY.toInt() - 8, xPlusLength.toInt(), endY.toInt() + 8)
                    mPaint.style = Paint.Style.FILL_AND_STROKE
                    canvas.drawRect(bounds, mPaint)
                } else if (value.shape == Shape.PROFILE) {
                    val drawable = ContextCompat.getDrawable(graphView.context, app.aaps.core.ui.R.drawable.ic_ribbon_profile) ?: break
                    drawable.setColorFilter(Color.WHITE, PorterDuff.Mode.MULTIPLY)
                    drawable.setBounds(
                        (endX - drawable.intrinsicWidth / 2).toInt(),
                        (endY - drawable.intrinsicHeight / 2).toInt(),
                        (endX + drawable.intrinsicWidth / 2).toInt(),
                        (endY + drawable.intrinsicHeight / 2).toInt()
                    )
                    drawable.draw(canvas)
                    mPaint.textSize = scaledTextSize * 0.48f
                    mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL))
                    mPaint.color = value.color(graphView.context)
                    val bounds = Rect()
                    mPaint.getTextBounds(value.label, 0, value.label.length, bounds)
                    val px = endX - bounds.width() / 2.0f
                    val py = endY + drawable.intrinsicHeight
                    mPaint.style = Paint.Style.FILL
                    canvas.drawText(value.label, px, py, mPaint)
                } else if (value.shape == Shape.MBG) {
                    mPaint.style = Paint.Style.STROKE
                    mPaint.strokeWidth = 5f
                    canvas.drawCircle(endX, endY, scaledPxSize, mPaint)
                } else if (value.shape == Shape.BGCHECK || value.shape == Shape.ANNOUNCEMENT || value.shape == Shape.GENERAL) {
                    mPaint.style = Paint.Style.FILL_AND_STROKE
                    mPaint.strokeWidth = 0f
                    canvas.drawCircle(endX, endY, scaledPxSize, mPaint)
                    if (value.label.isNotEmpty()) drawLabel45Right(endX, endY, value, canvas, scaledPxSize, scaledTextSize * 0.6f)
                } else if (value.shape == Shape.EXERCISE) {
                    mPaint.strokeWidth = 0f
                    if (value.label.isNotEmpty()) {
                        mPaint.strokeWidth = 0f
                        mPaint.textSize = (scaledTextSize * 0.6f).toFloat()
                        mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
                        val bounds = Rect()
                        mPaint.getTextBounds(value.label, 0, value.label.length, bounds)
                        mPaint.style = Paint.Style.FILL
                        val py = graphTop + 20
                        canvas.drawText(value.label, endX, py, mPaint)
                        mPaint.strokeWidth = 5f
                        canvas.drawRect(endX - 3, bounds.top + py - 3, xPlusLength + 3, bounds.bottom + py + 3, mPaint)
                    }
                } else if (value.shape == Shape.RUNNING_MODE) {
                    mPaint.strokeWidth = 0f
                    mPaint.style = Paint.Style.FILL_AND_STROKE
                    mPaint.strokeWidth = 5f
                    canvas.drawRect(endX, graphTop, xPlusLength, graphTop + 4, mPaint)
                } else if (value.shape == Shape.GENERAL_WITH_DURATION) {
                    mPaint.strokeWidth = 0f
                    if (value.label.isNotEmpty()) {
                        mPaint.strokeWidth = 0f
                        mPaint.textSize = (scaledTextSize * 0.6f).toFloat()
                        mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
                        val bounds = Rect()
                        mPaint.getTextBounds(value.label, 0, value.label.length, bounds)
                        mPaint.style = Paint.Style.FILL
                        val py = graphTop + 80
                        canvas.drawText(value.label, endX, py, mPaint)
                        mPaint.strokeWidth = 5f
                        canvas.drawRect(endX - 3, bounds.top + py - 3, xPlusLength + 3, bounds.bottom + py + 3, mPaint)
                    }
                } else if (value.shape == Shape.GENERAL_WITH_DURATION_OFFSET) {
                    // Same as GENERAL_WITH_DURATION, drawn further down so it doesn't overlap CarePortal notes.
                    // Centered over the BGL point (endX) — no bounding box (unlike GENERAL_WITH_DURATION), just the text.
                    mPaint.strokeWidth = 0f
                    if (value.label.isNotEmpty()) {
                        mPaint.strokeWidth = 0f
                        mPaint.textSize = (scaledTextSize * 0.6f).toFloat()
                        mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
                        mPaint.style = Paint.Style.FILL
                        mPaint.textAlign = Paint.Align.CENTER
                        val py = graphTop + 130
                        canvas.drawText(value.label, endX, py, mPaint)
                        mPaint.textAlign = Paint.Align.LEFT
                    }
                }
                // set values above point
            }
            // BOLUS arrowhead+shaft: drawn outside overdraw gate so it always appears when x is on-screen.
            // Same size/shaft proportions as the SMB arrow (value.size * scaledPxSize * 1.2f arrowhead,
            // scaledTextSize * 0.25f shaft), instead of the smaller bare-triangle-no-shaft look this used before.
            // Color.BLACK previously here likely blended into a dark theme's graph background — this was
            // a themed value.color(context) (bolusDataPointColor, a visible blue/cyan) before an earlier
            // change hardcoded it to black. Using a fixed bright color instead of black or a theme lookup,
            // so it can't blend into either a light or dark background.
            if (value.shape == Shape.BOLUS && x >= 0 && x <= graphWidth) {
                mPaint.color = Color.MAGENTA
                mPaint.strokeWidth = 0f
                mPaint.style = Paint.Style.FILL_AND_STROKE
                val bolusSize = value.size * scaledPxSize * 1.2f
                // Offset down from the BG line so this doesn't sit exactly on top of a carb marker,
                // which is also placed at the nearest-BG-line position.
                val bEndY = (endY + bolusSize * 2f).coerceIn(graphTop + bolusSize, graphTop + graphHeight - bolusSize)
                val bTriBase = bEndY + bolusSize * 0.67f
                drawArrows(arrayOf(
                    Point(endX.toInt(), (bEndY - bolusSize).toInt()),
                    Point((endX + bolusSize).toInt(), bTriBase.toInt()),
                    Point((endX - bolusSize).toInt(), bTriBase.toInt())
                ), canvas, mPaint)
                // shaft below the arrowhead, matching the SMB arrow's shaft style
                mPaint.strokeWidth = 2f
                mPaint.style = Paint.Style.STROKE
                canvas.drawLine(endX, bTriBase, endX, bTriBase + scaledTextSize * 0.25f * value.shaftLengthMultiplier, mPaint)
                if (value.label.isNotEmpty()) {
                    val labelRatY = (value.labelY - minY) / diffY
                    val labelEndY = (graphTop + graphHeight - graphHeight * labelRatY).toFloat()
                    val savedColor = mPaint.color
                    mPaint.color = Color.YELLOW
                    mPaint.style = Paint.Style.FILL
                    drawLabel45Right(endX, labelEndY, value, canvas, scaledPxSize, scaledTextSize * 0.7f)
                    mPaint.color = savedColor
                }
            }
        }
    }

    /**
     * helper to render triangle
     *
     * @param point  array with 3 coordinates
     * @param canvas canvas to draw on
     * @param paint  paint object
     */
    private fun drawArrows(point: Array<Point>, canvas: Canvas, paint: Paint) {
        canvas.withSave {
            val path = Path()
            path.moveTo(point[0].x.toFloat(), point[0].y.toFloat())
            path.lineTo(point[1].x.toFloat(), point[1].y.toFloat())
            path.lineTo(point[2].x.toFloat(), point[2].y.toFloat())
            path.close()
            drawPath(path, paint)
        }
    }

    private fun drawLabel45Right(endX: Float, endY: Float, value: E, canvas: Canvas, scaledPxSize: Float, scaledTextSize: Float) {
        val py = endY - scaledPxSize
        canvas.withRotation(-45f, endX, py) {
            mPaint.textSize = (scaledTextSize * 0.8).toFloat()
            mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL))
            mPaint.isFakeBoldText = true
            drawText(value.label, endX + scaledPxSize, py, mPaint)
        }
    }

    private fun drawLabel45Left(endX: Float, endY: Float, value: E, canvas: Canvas, scaledPxSize: Float, scaledTextSize: Float) {
        val py = endY + scaledPxSize
        canvas.withRotation(-45f, endX, py) {
            mPaint.textSize = (scaledTextSize * 0.8).toFloat()
            mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL))
            mPaint.isFakeBoldText = true
            mPaint.textAlign = Paint.Align.RIGHT
            drawText(value.label, endX - scaledPxSize, py, mPaint)
            mPaint.textAlign = Paint.Align.LEFT
        }
    }

    // Upright, horizontally centered on endX — unlike drawLabel45Right/Left, no rotation and no offset,
    // so the label sits directly over/under the point instead of angled off to one side.
    // text defaults to value.label (the SMB dose number); pass an explicit string for anything else
    // (e.g. the fast-rise indicator, which isn't value.label).
    private fun drawLabelCentered(endX: Float, endY: Float, value: E, canvas: Canvas, scaledTextSize: Float, text: String = value.label) {
        mPaint.textSize = (scaledTextSize * 0.8).toFloat()
        mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL))
        mPaint.isFakeBoldText = true
        mPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, endX, endY, mPaint)
        mPaint.textAlign = Paint.Align.LEFT
    }
}
