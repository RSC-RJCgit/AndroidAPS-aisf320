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

        // Basal long-press cycles through 3 display presets (0→1→2→0...). IOB long-press always resets
        // this back to 0 in addition to its own showSmbLabels toggle, regardless of which direction that
        // toggle goes, so the "reset to normal" gesture is independent of the SMB-label state.
        //   0: near-BGL arrowheads on,  normal per-point ISF colors
        //   1: near-BGL arrowheads off, normal per-point ISF colors
        //   2: near-BGL arrowheads off, uniform transparent green BG dots
        var basalToggleIndex: Int = 0
            set(value) { field = ((value % 3) + 3) % 3 }

        // Shape.SMB's "arrowhead just below the relevant BGL point" and Shape.BOLUS's arrowhead are NOT
        // affected by this — Shape.BOLUS (meal bolus arrows) always draws regardless, and the SMB
        // baseline triangle at the bottom of the graph always draws regardless too.
        val showBglArrowheads: Boolean get() = basalToggleIndex == 0
        val uniformGreenBg: Boolean get() = basalToggleIndex == 2

        // Flat color used for all BG dots when uniformGreenBg is active, ignoring per-point ISF-weight overrides.
        val uniformGreenBgColor: Int = android.graphics.Color.argb(140, 0, 200, 0)

        // Live BGL (mg/dL) and active-profile target, refreshed each overview update cycle (see
        // OverviewFragment.updateBg()) — read at draw time to decide the GENERAL_WITH_DURATION_OFFSET
        // annotation's vertical position. 0.0 default means "unknown yet" -> stays at the top (the
        // underTarget checks below are all comparisons that fail at 0.0).
        // currentBgMgdl stays raw mg/dL (only ever compared against the mg/dL literal thresholds below).
        // currentTargetInDisplayUnits, despite the name difference, must be in the SAME units as the
        // graph's own Y-axis/viewport (minY/maxY below) to compute a valid pixel position — and this
        // graph's data points (e.g. NoisyBgDeltaDataPoint's own yValue) are built via
        // profileUtil.fromMgdlToUnits(), i.e. the user's DISPLAY units (mmol here), not raw mg/dL. A
        // previous version of this field held raw mg/dL and got compared directly against the
        // mmol-scaled viewport, producing a wildly out-of-range ratio that pushed the label off-canvas
        // entirely — hence the unit-explicit rename.
        var currentBgMgdl: Double = 0.0
        var currentTargetInDisplayUnits: Double = 0.0

        // Cached decision for the GENERAL_WITH_DURATION_OFFSET annotation (top vs. under-target-line).
        // Previously this was recomputed from currentBgMgdl/showSmbLabels on every single draw() call,
        // which fires far more often than the BGL actually changes (any scroll/zoom/invalidate) and was
        // measurably slowing graph redraws. Refreshed by refreshAnnotationPosition(), called from the IOB
        // long-press AND a 15-min auto-refresh in OverviewFragment.updateBg() — draw() just reads the
        // cached value. The pixel geometry itself (dependent on the live viewport) still has to be
        // computed in draw().
        var annotationUnderTarget: Boolean = false

        // Hysteresis around 7.5mmol: BGL alone decides outside the 7.0-8.0mmol band (>=8.0 always LOW,
        // <7.0 always HIGH). Inside that ambiguous band, lean by showSmbLabels — ON prefers LOW (avoids
        // a HIGH-position conflict while labels are on), OFF prefers HIGH (avoids a LOW-position
        // legibility issue while labels are off). Outside the band this is BGL-only, same as before.
        fun refreshAnnotationPosition() {
            annotationUnderTarget = when {
                currentBgMgdl >= 144.1 /* 8.0 mmol */ -> true
                currentBgMgdl < 126.1 /* 7.0 mmol */  -> false
                else                                   -> showSmbLabels
            }
        }
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
        // Center-justify the top/bottom fixed annotations only on the tightest (~3h) zoom level, where there's
        // room either side of endX; wider ranges compress the x-axis enough that centered text would clip, so
        // those fall back to right-justified (grows leftward from endX).
        val displayedHoursForAlignment = diffX / (1000.0 * 60 * 60)
        val fixedAnnotationAlign = if (displayedHoursForAlignment <= 3.0) Paint.Align.CENTER else Paint.Align.RIGHT
        val graphHeight = graphView.graphContentHeight.toFloat()
        val graphWidth = graphView.graphContentWidth.toFloat()
        val graphLeft = graphView.graphContentLeft.toFloat()
        val graphTop = graphView.graphContentTop.toFloat()
        val scaleX = (graphWidth / diffX).toFloat()
        val smbStack = HashMap<Long, Int>() // bucket (5-min) -> count of SMBs drawn
        val noteStack = HashMap<Long, Int>() // bucket (20-min) -> count of CarePortal notes drawn at this height
        val noteDedupSeen = HashMap<Long, MutableSet<String>>() // bucket (20-min) -> note labels already drawn there, so no note repeats within a bucket
        // Steps row — graph1 now, fixed near the bottom of THIS graph's own viewport. No longer
        // glucose-pinned — graph1 isn't necessarily glucose-scaled. Completely static: no toggle, no
        // dependency on BGL state/long-press at all.
        // Anchored at 0.88 (not right at the very bottom) so the yellow line below it (stepsRowPy +
        // gap) still has room and doesn't clip past graphHeight.
        val nearBottomPy = graphTop + graphHeight * 0.88f
        val stepsRowPy = nearBottomPy
        // DR=/AW=/LS= row (Shape.STEPS_EXTRA_ROW) — split out of the steps row, fixed one line-height
        // above it (graph1, same static positioning as the steps row itself).
        val stepsExtraRowPy = stepsRowPy - scaledTextSize * 0.6f
        // Yellow/white line (GENERAL_WITH_DURATION_OFFSET) — graph1 too, just below the steps row
        // (stepsRowPy + a small gap). No more HIGH/LOW toggle (annotationUnderTarget is no longer read
        // here — see its own declaration if reviving later).
        val greenLinePy = stepsRowPy + scaledTextSize * 0.5f
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
            // Y-independent shapes (fixed pixel position, never actually use the data point's Y value
            // for on-screen placement) skip the Y-range culling below entirely — GENERAL_WITH_DURATION
            // notes were being silently dropped whenever their Y (tied to an actual glucose value for
            // some TE types, or a 0.0 default for plain notes) fell outside whichever graph the series
            // is attached to, e.g. graph2's own IOB/percentage-scaled range having nothing to do with
            // glucose values.
            val yIndependentShape = value.shape == Shape.GENERAL_WITH_DURATION || value.shape == Shape.GENERAL_WITH_DURATION_OFFSET ||
                value.shape == Shape.STEPS_STACKED_BOTTOM || value.shape == Shape.SMB_GRAPH2 || value.shape == Shape.ISF_INDICES ||
                value.shape == Shape.STEPS_EXTRA_ROW
            if (!yIndependentShape) {
                if (y < 0) { // end bottom
                    overdraw = true
                }
                if (y > graphHeight) { // end top
                    overdraw = true
                }
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
                    // baseline arrowhead's shaft above instead. Toggled by showBglArrowheads (long-press
                    // basal icon) — the baseline triangle/shaft above always draws regardless.
                    if (showBglArrowheads) {
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
                    }
                    val displayedHours = (maxX - minX) / (1000.0 * 60 * 60)
                    // label near BG dot — gated by showSmbLabels long-press, unchanged behaviour
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
                } else if (value.shape == Shape.ACTIVITY_PEAK) {
                    // Same style as GENERAL's drawLabel45Right, but below the peak point instead of above.
                    mPaint.style = Paint.Style.FILL_AND_STROKE
                    mPaint.strokeWidth = 0f
                    canvas.drawCircle(endX, endY, scaledPxSize, mPaint)
                    if (value.label.isNotEmpty()) drawLabel45RightBelow(endX, endY, value, canvas, scaledPxSize, scaledTextSize * 0.6f)
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
                    val bucketMs = 25 * 60_000L
                    val rawBucket = value.getX().toLong() / bucketMs
                    // No note ever repeats within a bucket, regardless of type — throttle-less repeats
                    // (e.g. HardStackDelOff/DelOff, which re-check every loop cycle with no readyToRun)
                    // would otherwise flood a bucket with duplicates of themselves, but any exact label
                    // showing up twice in the same 25-min bucket is deduped to its first occurrence.
                    val alreadyDrawnThisBucket = !noteDedupSeen.getOrPut(rawBucket) { mutableSetOf() }.add(value.label)
                    if (value.label.isNotEmpty() && !alreadyDrawnThisBucket) {
                        // Stacked by 25-min bucket, max 6 per bucket, so notes landing close together in
                        // time offset downward instead of overlapping at the same fixed height. Nothing
                        // is ever dropped: once a bucket already has 6, the note spills into the next
                        // 25-min bucket's stack instead (rather than simply not drawing the 7th+ note in
                        // an overfull bucket).
                        var noteBucket = rawBucket
                        while (noteStack.getOrDefault(noteBucket, 0) >= 6) noteBucket++
                        val noteStackIndex = noteStack.getOrDefault(noteBucket, 0)
                        noteStack[noteBucket] = noteStackIndex + 1
                        // Truncated to 5 characters for display only — the full note text is unaffected
                        // in the database/NS, this only shortens what's drawn on the graph.
                        val displayLabel = value.label.take(5)
                        mPaint.strokeWidth = 0f
                        mPaint.textSize = (scaledTextSize * 0.6f).toFloat()
                        mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
                        mPaint.style = Paint.Style.FILL
                        // Bottom-anchored, stacking upward, flush against the very bottom of graph2 (no
                        // fixed base offset) — first note sits right at graphTop+graphHeight.
                        val py = graphTop + graphHeight - noteStackIndex * (scaledTextSize * 0.45f)
                        canvas.drawText(displayLabel, endX, py, mPaint)
                    }
                } else if (value.shape == Shape.GENERAL_WITH_DURATION_OFFSET) {
                    // Raw-BG/delta ("green line") annotation. No bounding box — just the text.
                    // Left-justified to the graph's own left edge (graphLeft), not to endX (the data
                    // point's own timestamp position, which for this "live" single-point annotation sits
                    // at the current-time/"now" position — anchoring there instead of the graph's left
                    // edge was pushing the text off toward/past the right side of the visible graph).
                    // Position (greenLinePy, computed once above the loop) is fixed, just below the
                    // steps row (stepsRowPy) on graph1 — see that computation's own comment.
                    mPaint.strokeWidth = 0f
                    if (value.label.isNotEmpty()) {
                        mPaint.strokeWidth = 0f
                        mPaint.textSize = (scaledTextSize * 0.6f).toFloat()
                        mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
                        mPaint.style = Paint.Style.FILL
                        mPaint.textAlign = Paint.Align.LEFT
                        canvas.drawText(value.label, graphLeft + 10f, greenLinePy, mPaint)
                    }
                } else if (value.shape == Shape.SMB_GRAPH2) {
                    if (value.label.isNotEmpty()) {
                        val bucket2 = (value.x / 600_000L).toLong()
                        val stackIndex2 = smbStack.getOrDefault(bucket2, 0)
                        smbStack[bucket2] = stackIndex2 + 1
                        mPaint.strokeWidth = 0f
                        mPaint.textSize = (scaledTextSize * 0.5f).toFloat()
                        mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
                        mPaint.style = Paint.Style.FILL
                        // white when uniform-green active; ISF color override otherwise
                        mPaint.color = if (uniformGreenBg) Color.WHITE else value.color(graphView.context)
                        mPaint.textAlign = Paint.Align.CENTER
                        // near bottom of graph, stacking upward with half-height steps
                        val labelY = graphTop + graphHeight - scaledTextSize * 0.3f - stackIndex2 * scaledTextSize * 0.5f
                        canvas.drawText(value.label, endX, labelY, mPaint)
                        mPaint.textAlign = Paint.Align.LEFT
                    }
                } else if (value.shape == Shape.STEPS_STACKED_BOTTOM) {
                    // Single row ("S5=... S15=..."), always drawn at stepsRowPy — this series now
                    // renders on graph1, fixed near its bottom. The yellow line sits just below this,
                    // at greenLinePy.
                    mPaint.strokeWidth = 0f
                    if (value.label.isNotEmpty()) {
                        mPaint.textSize = (scaledTextSize * 0.6f).toFloat()
                        mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
                        mPaint.style = Paint.Style.FILL
                        // Same left-justification as the green line above — anchored to the graph's own
                        // left edge (graphLeft), not to endX/fixedAnnotationAlign.
                        mPaint.textAlign = Paint.Align.LEFT
                        canvas.drawText(value.label, graphLeft + 10f, stepsRowPy, mPaint)
                    }
                } else if (value.shape == Shape.STEPS_EXTRA_ROW) {
                    // "DR=.../AW=.../LS=..." row, split out of the steps row above — fixed at
                    // stepsExtraRowPy (one line-height above stepsRowPy), same graph1, same styling.
                    mPaint.strokeWidth = 0f
                    if (value.label.isNotEmpty()) {
                        mPaint.textSize = (scaledTextSize * 0.6f).toFloat()
                        mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
                        mPaint.style = Paint.Style.FILL
                        mPaint.textAlign = Paint.Align.LEFT
                        canvas.drawText(value.label, graphLeft + 10f, stepsExtraRowPy, mPaint)
                    }
                } else if (value.shape == Shape.L1_DELTA_POINT || value.shape == Shape.A1_DELTA_POINT || value.shape == Shape.HP_DELTA_POINT) {
                    // Libre 1-min / AAPS (smoothed) 1-min delta / hypo-prediction label attached directly
                    // to the current graph point — same 45°-rotated style as GENERAL's drawLabel45Right,
                    // but no circle (the actual BG point already has its own dot drawn by the glucose
                    // series underneath). L1/A1 bumped up from the original 0.6f — hard to read at that
                    // size on the graph background. HP kept smaller (0.55f) — it's further out along the
                    // diagonal and already has more room via its longer underscore offset. Already bold
                    // via drawLabel45Right's isFakeBoldText.
                    val sizeMultiplier = if (value.shape == Shape.HP_DELTA_POINT) 0.55f else 0.85f
                    if (value.label.isNotEmpty()) drawLabel45Right(endX, endY, value, canvas, scaledPxSize, scaledTextSize * sizeMultiplier)
                } else if (value.shape == Shape.ISF_INDICES) {
                    // "f= ac= bg= pp= du= smb=" row, one color per field (matching
                    // AutoISFHistoryDialog's own column colors), fixed in the bottom area of graph3.
                    if (value is IsfIndicesDataPoint && value.segments.isNotEmpty()) {
                        mPaint.strokeWidth = 0f
                        mPaint.textSize = (scaledTextSize * 0.7f).toFloat()
                        mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
                        mPaint.style = Paint.Style.FILL
                        mPaint.textAlign = Paint.Align.LEFT
                        val py = graphTop + graphHeight * 0.94f
                        var xCursor = graphLeft + 10f
                        for ((text, color) in value.segments) {
                            mPaint.color = color
                            canvas.drawText(text, xCursor, py, mPaint)
                            xCursor += mPaint.measureText(text) + 12f
                        }
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
                mPaint.color = if (value.hasDelayedComponent) Color.YELLOW else Color.MAGENTA
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

    // Same as drawLabel45Right, but below the point (py = endY + scaledPxSize) instead of above —
    // used for the peak insulin activity label.
    private fun drawLabel45RightBelow(endX: Float, endY: Float, value: E, canvas: Canvas, scaledPxSize: Float, scaledTextSize: Float) {
        val py = endY + scaledPxSize
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
