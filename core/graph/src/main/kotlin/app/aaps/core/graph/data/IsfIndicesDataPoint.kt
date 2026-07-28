package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import app.aaps.core.interfaces.resources.ResourceHelper

// Single live annotation row: "f=<final> a=<acce> b=<bg> d=<dura> g=<glucose> smb=<delivered>", one
// color per field (see PointsWithLabelGraphSeries.draw(), Shape.ISF_INDICES) — matching
// AutoISFHistoryDialog's own column colors exactly, since this is the same values shown there.
// segments holds (text, color) pairs in draw order; label is a plain-text fallback (dedup/non-empty
// checks only, never actually drawn — the ISF_INDICES branch draws segments directly).
class IsfIndicesDataPoint(
    private val timestamp: Long,
    val segments: List<Pair<String, Int>>,
    private val rh: ResourceHelper
) : DataPointWithLabelInterface {

    override fun getX(): Double = timestamp.toDouble()
    override fun getY(): Double = 0.0
    override fun setY(y: Double) {}

    override val label: String = segments.joinToString(" ") { it.first }
    override val duration = 60_000L // 1 minute; only needs to be > 0 to select a "with duration" shape
    override val shape = Shape.ISF_INDICES
    override val size get() = if (rh.gb(app.aaps.core.ui.R.bool.isTablet)) 12.0f else 10.0f
    override val paintStyle: Paint.Style = Paint.Style.FILL
    override fun color(context: Context?): Int = Color.WHITE
}
