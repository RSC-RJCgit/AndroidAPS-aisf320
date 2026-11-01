package app.aaps.core.interfaces.stats

import android.content.Context
import android.widget.TableRow
import android.widget.TextView
import app.aaps.core.interfaces.profile.ProfileUtil

interface DexcomTIR {

    fun calculateSD(): Double
    fun veryLowPct(): Double
    fun lowPct(): Double
    fun inRangePct(): Double
    fun highPct(): Double
    fun veryHighPct(): Double
    fun mean(): Double
    fun count(): Int
    fun toHbA1cView(context: Context): TextView
    fun toSDView(context: Context, profileUtil: ProfileUtil): TextView
    fun toRangeHeaderView(context: Context, profileUtil: ProfileUtil): TextView
    fun toTableRowHeader(context: Context): TableRow
    fun toTableRow(context: Context): TableRow
}
