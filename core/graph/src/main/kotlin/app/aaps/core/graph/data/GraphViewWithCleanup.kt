package app.aaps.core.graph.data

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.series.Series

open class GraphViewWithCleanup @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) : GraphView(context, attrs, defStyle) {

    /** A tap anywhere in the plot, expressed as the time on its current x axis. */
    var onPlotTap: ((Long) -> Unit)? = null
    private var tapStartX = 0f
    private var tapStartY = 0f
    private var tapPossible = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapStartX = event.x
                tapStartY = event.y
                tapPossible = onPlotTap != null
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                val dx = event.x - tapStartX
                val dy = event.y - tapStartY
                if (event.pointerCount > 1 || dx * dx + dy * dy > slop * slop) tapPossible = false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tapPossible = false
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (tapPossible && event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout() &&
                event.x >= graphContentLeft && event.x <= graphContentLeft + graphContentWidth &&
                event.y >= graphContentTop && event.y <= graphContentTop + graphContentHeight && graphContentWidth > 0
            ) {
                val fraction = (event.x - graphContentLeft) / graphContentWidth
                val from = viewport.getMinX(false)
                val to = viewport.getMaxX(false)
                performClick()
                onPlotTap?.invoke((from + fraction * (to - from)).toLong())
            }
            tapPossible = false
        }
        // A non-scrollable graph must retain DOWN to receive UP and report a tap.
        return handled || onPlotTap != null
    }

    override fun removeAllSeries() {
        onPlotTap = null
        series.forEach {
            it.setOnDataPointTapListener(null)
        }
        super.removeAllSeries()
    }

    override fun removeSeries(series: Series<*>?) {
        series?.setOnDataPointTapListener(null)
        super.removeSeries(series)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeAllSeries()
    }
}
