package app.aaps.plugins.main.general.overview.graphData

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.plugins.main.R

/**
 * The label of a secondary graph, with the "Steps 5/15/30/60" short name (STP4) replaced by "S5 S15 S30 S60", each in
 * the colour of its line (2026-10-19). Used by the overview and the history browser.
 */
fun stepsLinesLabel(label: String, rh: ResourceHelper, context: Context?): CharSequence {
    val shortName = rh.gs(R.string.steps_lines_shortname)
    val at = label.indexOf(shortName)
    if (at < 0) return label
    val parts = listOf(
        rh.gs(R.string.steps_lines_s5) to rh.gac(context, app.aaps.core.ui.R.attr.stepsColor),
        rh.gs(R.string.steps_lines_s15) to rh.gc(app.aaps.core.ui.R.color.steps_line_15),
        rh.gs(R.string.steps_lines_s30) to rh.gc(app.aaps.core.ui.R.color.steps_line_30),
        rh.gs(R.string.steps_lines_s60) to rh.gc(app.aaps.core.ui.R.color.steps_line_60)
    )
    val builder = SpannableStringBuilder(label.substring(0, at))
    parts.forEachIndexed { i, (text, color) ->
        if (i > 0) builder.append(" ")
        val start = builder.length
        builder.append(text)
        builder.setSpan(ForegroundColorSpan(color), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    builder.append(label.substring(at + shortName.length))
    return builder
}
