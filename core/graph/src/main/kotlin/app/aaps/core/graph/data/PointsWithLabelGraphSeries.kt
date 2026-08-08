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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale

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

        // Line1 (Carbs Absorption) quick show/hide, driven by direct long-press ACTIONS, not by passively
        // reading basalToggleIndex's current value (that would wrongly react to the IOB long-press's own
        // incidental reset of that counter to 0). Set explicitly by each relevant long-press handler:
        // basal icon landing on push0 -> false; basal icon landing on push1/push2 -> true; IOB icon press
        // -> true (its own reset of basalToggleIndex is a side effect of a DIFFERENT action, not a direct
        // push0 press, so it must NOT turn this off). Still gated by the CARB_ABS checkbox itself as a
        // master on/off at the call site -- this flag only matters when that checkbox is already on.
        var carbLine1QuickShow: Boolean = true

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
        val values = getValues(minX, maxX).asSequence().filterNotNull().toList()

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
        // SMB stack grouping: same rolling-anchor concept as the note stacks below — a new stack starts
        // only when >=10 real minutes have elapsed since the CURRENT stack's own first SMB, not a fixed
        // epoch-aligned grid (timestamp/10min), which could split two SMBs only a few minutes apart into
        // different buckets whenever they straddled one of the grid's absolute boundaries.
        // Shape.SMB_GRAPH2 (graph2 stacked labels) gets its OWN independent anchor/id/count, deliberately
        // NOT shared with Shape.SMB's above -- same reasoning as arrowStackAnchor being kept separate
        // from noteStackAnchor below: Shape.SMB and Shape.SMB_GRAPH2 are separate point series, drawn in
        // separate passes over this same draw() call, not interleaved per-event. Sharing one counter
        // meant the SMB_GRAPH2 pass ran on top of whatever count Shape.SMB's own full pass had already
        // left behind, offsetting every stack index by that entire prior count instead of starting fresh
        // -- worse the more SMBs there were, i.e. exactly "pushing the previous group higher" as more
        // SMBs accumulated. The two renderings were never actually kept height-consistent by sharing this
        // counter (separate passes over a shared cumulative counter doesn't achieve that), so there's no
        // real intent lost by splitting them.
        // Reverse offsets are calculated per 10-minute group so bottom-insertion in the active group
        // cannot move labels belonging to an earlier, completed group.
        val smbStackReverseIndex = calculateSmbStackIndices(values, Shape.SMB)
        val smbGraph2StackReverseIndex = calculateSmbStackIndices(values, Shape.SMB_GRAPH2)
        // 30-minute grouping (not the 10-min dosing-stack window the label's own VALUE represents) --
        // purely so two ΔIOB labels landing close together on screen stay readable instead of
        // overlapping. See Shape.SMB_STACK_DELTA_IOB.
        val stackDeltaIobStackReverseIndex = calculateSmbStackIndices(values, Shape.SMB_STACK_DELTA_IOB, 30 * 60_000L)
        // Note-text stack grouping: a ROLLING window anchored to each stack's own first note, not a
        // fixed epoch-aligned grid (timestamp/25min) — that fixed-grid scheme could split two notes only
        // a few minutes apart into different "buckets" whenever they straddled one of the grid's absolute
        // boundaries, so stacks weren't reliably 25 real minutes long. noteStackIndex is a synthetic,
        // monotonically increasing id for "which stack this note belongs to" (not a time value itself);
        // noteStackAnchor is the timestamp a new stack starts counting 25 minutes from.
        val noteStack = HashMap<Long, Int>() // stack id -> count of CarePortal notes drawn at this height
        val noteDedupSeen = HashMap<Long, MutableSet<String>>() // stack id -> note labels already drawn there, so no note repeats within a stack
        var noteStackId = 0L
        var noteStackAnchor = Long.MIN_VALUE
        // Arrowhead time-label grouping (Shape.NOTE_ARROWHEAD_GRAPH3): same rolling-anchor concept as the
        // note-text stack above, but tracked independently (arrowheads and note-text are separate point
        // series drawn in the same pass) — arrowStackAnchor is the timestamp the CURRENT 25-min window
        // started; only the first arrowhead seen since that anchor gets a time label.
        var arrowStackAnchor = Long.MIN_VALUE
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
        // ISF adaptation indices/SMB row ("f= ac= bg= pp= du= smb=") — moved from graph3 to graph1,
        // fixed one more line-height above stepsExtraRowPy so it sits above all three of the rows/lines
        // above rather than overlapping any of them.
        val isfIndicesRowPy = stepsExtraRowPy - scaledTextSize * 0.6f
        // Note-arrowhead position (Shape.NOTE_ARROWHEAD_GRAPH3) — graph4's top half, 0.2 of that graph's
        // own height.
        val noteArrowheadPy = graphTop + graphHeight * 0.2f
        // SMB-stack ΔIOB label (Shape.SMB_STACK_DELTA_IOB) — fixed at the very top of graph2 (IOB graph),
        // drawn at each event's own X (the stack's start time), unlike the fixed-left rows above.
        val stackDeltaIobPy = graphTop + scaledTextSize * 0.8f
        for (value in values) {
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
                value.shape == Shape.STEPS_EXTRA_ROW || value.shape == Shape.HP_ROW_BOTTOM || value.shape == Shape.NOTE_ARROWHEAD_GRAPH3 ||
                value.shape == Shape.SMB_STACK_DELTA_IOB
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
                    // No spill-to-next-bucket here (unlike the Notes stack below): with a 1-min minimum
                    // SMB cadence, a genuine 10-min bucket can hold at most 11 doses (t=0..10 inclusive) --
                    // that's the natural ceiling, so every SMB in a bucket is real and none should ever be
                    // treated as "overflow" into a synthetic bucket.
                    val stackIndex = smbStackReverseIndex[value] ?: 0
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
                    // Rolling window: a new stack starts only when >=25 real minutes have elapsed since
                    // the CURRENT stack's own first note — not a fixed epoch-aligned grid (which could
                    // split two notes only a few minutes apart into different buckets whenever they
                    // straddled one of the grid's absolute boundaries).
                    val noteX = value.getX().toLong()
                    if (noteStackAnchor == Long.MIN_VALUE || noteX - noteStackAnchor >= bucketMs) {
                        noteStackAnchor = noteX
                        noteStackId++
                    }
                    val rawBucket = noteStackId
                    // No note ever repeats within a bucket, regardless of type — throttle-less repeats
                    // (e.g. HardStackDelOff/DelOff, which re-check every loop cycle with no readyToRun)
                    // would otherwise flood a bucket with duplicates of themselves, but any exact label
                    // showing up twice in the same 25-min bucket is deduped to its first occurrence.
                    val alreadyDrawnThisBucket = !noteDedupSeen.getOrPut(rawBucket) { mutableSetOf() }.add(value.label)
                    if (value.label.isNotEmpty() && !alreadyDrawnThisBucket) {
                        // Stacked by 25-min bucket, max 8 per bucket, so notes landing close together in
                        // time offset downward instead of overlapping at the same fixed height. Nothing
                        // is ever dropped: once a bucket already has 8, the note spills into the next
                        // 25-min bucket's stack instead (rather than simply not drawing the 9th+ note in
                        // an overfull bucket).
                        var noteBucket = rawBucket
                        while (noteStack.getOrDefault(noteBucket, 0) >= 8) noteBucket++
                        val noteStackIndex = noteStack.getOrDefault(noteBucket, 0)
                        noteStack[noteBucket] = noteStackIndex + 1
                        // Truncated to 5 characters for display only — the full note text is unaffected
                        // in the database/NS, this only shortens what's drawn on the graph.
                        val displayLabel = value.label.take(5)
                        mPaint.strokeWidth = 0f
                        mPaint.textSize = (scaledTextSize * 0.5f).toFloat() // slightly smaller, was 0.6f
                        mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
                        mPaint.style = Paint.Style.FILL
                        // Bottom-anchored, stacking upward, flush against the very bottom of graph2 (no
                        // fixed base offset) — first note sits right at graphTop+graphHeight. Stack gap
                        // slightly larger, was 0.45f.
                        val py = graphTop + graphHeight - noteStackIndex * (scaledTextSize * 0.55f)
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
                        // No spill (see Shape.SMB above) -- 1-min minimum SMB cadence bounds a genuine
                        // 10-min bucket at 11 doses max, so nothing here is ever true overflow.
                        val stackIndex2 = smbGraph2StackReverseIndex[value] ?: 0
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
                } else if (value.shape == Shape.SMB_STACK_DELTA_IOB) {
                    // ΔIOB over the 10 minutes following an SMB-stack's start, fixed at the top of the
                    // IOB/COB panel, always white, drawn at the stack's own start timestamp. Stacks
                    // DOWNWARD in 30-min groups (stackDeltaIobStackReverseIndex) so two labels landing
                    // close together stay readable. See PrepareTreatmentsDataWorker.kt.
                    mPaint.strokeWidth = 0f
                    if (value.label.isNotEmpty()) {
                        val stackIndex3 = stackDeltaIobStackReverseIndex[value] ?: 0
                        mPaint.textSize = (scaledTextSize * 0.55f).toFloat()
                        mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
                        mPaint.style = Paint.Style.FILL
                        mPaint.color = Color.WHITE
                        mPaint.textAlign = Paint.Align.CENTER
                        val labelY = stackDeltaIobPy + stackIndex3 * scaledTextSize * 0.55f
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
                } else if (value.shape == Shape.L1_DELTA_POINT || value.shape == Shape.A1_DELTA_POINT || value.shape == Shape.UKF_DELTA_POINT) {
                    // Libre 1-min / AAPS (smoothed) 1-min delta label attached directly to the current
                    // graph point — same 45°-rotated style as GENERAL's drawLabel45Right, but no circle
                    // (the actual BG point already has its own dot drawn by the glucose series
                    // underneath). L1 now matches A1's own size (both 0.85f). Already bold via
                    // drawLabel45Right's isFakeBoldText.
                    val sizeMultiplier = 0.85f
                    if (value.label.isNotEmpty()) drawLabel45Right(endX, endY, value, canvas, scaledPxSize, scaledTextSize * sizeMultiplier)
                } else if (value.shape == Shape.HP_ROW_BOTTOM) {
                    // "hypoprediction= <value>" row, fixed at nearBottomPy — same static-position
                    // mechanism as Shape.STEPS_STACKED_BOTTOM (see that computation above), but drawn on
                    // the MAIN graph's own instance of this series, near its basal-column area at the
                    // bottom — the spot the steps row itself occupied there before it fully migrated to
                    // graph1. Decreased from 0.9f to 0.6f (matches the steps row's own size).
                    mPaint.strokeWidth = 0f
                    if (value.label.isNotEmpty()) {
                        mPaint.textSize = (scaledTextSize * 0.6f).toFloat()
                        mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
                        mPaint.style = Paint.Style.FILL
                        mPaint.textAlign = Paint.Align.LEFT
                        canvas.drawText(value.label, graphLeft + 10f, nearBottomPy, mPaint)
                    }
                } else if (value.shape == Shape.ISF_INDICES) {
                    // "f= ac= bg= pp= du= smb=" row, one color per field (matching
                    // AutoISFHistoryDialog's own column colors), fixed at isfIndicesRowPy — moved from
                    // graph3 to graph1, above the steps row/extra row/yellow-white line there.
                    if (value is IsfIndicesDataPoint && value.segments.isNotEmpty()) {
                        mPaint.strokeWidth = 0f
                        mPaint.textSize = (scaledTextSize * 0.7f).toFloat()
                        mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
                        mPaint.style = Paint.Style.FILL
                        mPaint.textAlign = Paint.Align.LEFT
                        var xCursor = graphLeft + 10f
                        for ((text, color) in value.segments) {
                            mPaint.color = color
                            canvas.drawText(text, xCursor, isfIndicesRowPy, mPaint)
                            xCursor += mPaint.measureText(text) + 12f
                        }
                    }
                } else if (value.shape == Shape.NOTE_ARROWHEAD_GRAPH3) {
                    // Plain CarePortal-note arrowhead, pointing DOWN (apex at bottom) — inverted from
                    // Shape.SMB's own upward BGL-point arrowhead, with a shaft half the length of the
                    // original (scaledTextSize*0.5f, was 1.0f) hanging above it down to the triangle's
                    // base. Fixed position (noteArrowheadPy, graph4's top half) for every arrowhead — no
                    // vertical stacking of the arrowheads themselves (that's the separate, pre-existing
                    // GENERAL_WITH_DURATION note-text stack above). Same rolling-25-min-anchor concept as
                    // that stack (see noteStackAnchor above) — a new window starts only when >=25 real
                    // minutes have elapsed since the current window's own first arrowhead, NOT a fixed
                    // epoch-aligned grid (which could split two arrowheads only a few minutes apart into
                    // different windows whenever they straddled one of the grid's absolute boundaries —
                    // the original bug: two notes 5 min apart both got their own time label). Only the
                    // first arrowhead in each window gets a time label (HHmm, e.g. "2228") at the top of
                    // its shaft; every other arrowhead in the same window stays label-free. Not tracking
                    // an actual BGL value, since graph4 isn't glucose-scaled.
                    mPaint.strokeWidth = 0f
                    val arrowBucketMs = 25 * 60_000L
                    val arrowX = value.x.toLong()
                    val isFirstInBucket = arrowStackAnchor == Long.MIN_VALUE || arrowX - arrowStackAnchor >= arrowBucketMs
                    if (isFirstInBucket) arrowStackAnchor = arrowX

                    val noteSize = value.size * scaledPxSize * 1.2f
                    val shaftStart = noteArrowheadPy
                    val shaftEnd = shaftStart + scaledTextSize * 0.25f * 2  // half of the original 4x
                    val triBase = shaftEnd
                    val triTip = triBase + noteSize * 1.5f
                    val halfWidth = noteSize * 0.25f
                    if (!value.hasColorOverride) mPaint.color = Color.YELLOW
                    mPaint.strokeWidth = 2f
                    mPaint.style = Paint.Style.STROKE
                    canvas.drawLine(endX, shaftStart, endX, shaftEnd, mPaint)
                    mPaint.strokeWidth = 0f
                    mPaint.style = Paint.Style.FILL_AND_STROKE
                    drawArrows(arrayOf(
                        Point(endX.toInt(), triTip.toInt()),
                        Point((endX + halfWidth).toInt(), triBase.toInt()),
                        Point((endX - halfWidth).toInt(), triBase.toInt())
                    ), canvas, mPaint)
                    if (isFirstInBucket) {
                        val timeLabel = SimpleDateFormat("HHmm", Locale.getDefault()).format(Date(value.x.toLong()))
                        mPaint.style = Paint.Style.FILL
                        mPaint.textAlign = Paint.Align.CENTER
                        mPaint.textSize = (scaledTextSize * 0.5f).toFloat()
                        mPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
                        canvas.drawText(timeLabel, endX, shaftStart - 4f, mPaint)
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

    private fun calculateSmbStackIndices(values: List<E>, shape: Shape, windowMs: Long = 10 * 60_000L): IdentityHashMap<E, Int> {
        // BaseSeries iteration order can be newest-first. Stack windows must be built in timestamp
        // order; otherwise timestamp-anchor stays negative and consecutive windows merge into one.
        val smbValues = values.filter { it.shape == shape }.sortedBy { it.x }
        val bucketByValue = IdentityHashMap<E, Long>()
        val bucketTotals = HashMap<Long, Int>()
        var anchor = Long.MIN_VALUE
        var bucket = 0L

        smbValues.forEach { value ->
            val timestamp = value.x.toLong()
            if (anchor == Long.MIN_VALUE || timestamp - anchor >= windowMs) {
                anchor = timestamp
                bucket++
            }
            bucketByValue[value] = bucket
            bucketTotals[bucket] = bucketTotals.getOrDefault(bucket, 0) + 1
        }

        val seen = HashMap<Long, Int>()
        return IdentityHashMap<E, Int>().also { indices ->
            smbValues.forEach { value ->
                val valueBucket = bucketByValue.getValue(value)
                val chronologicalIndex = seen.getOrDefault(valueBucket, 0)
                seen[valueBucket] = chronologicalIndex + 1
                indices[value] = bucketTotals.getValue(valueBucket) - chronologicalIndex - 1
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
