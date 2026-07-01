package app.aaps.core.graph.data

import android.content.Context
import android.graphics.Paint
import androidx.annotation.ColorInt
import com.jjoe64.graphview.series.DataPointInterface

interface DataPointWithLabelInterface : DataPointInterface {

    override fun getX(): Double
    override fun getY(): Double
    fun setY(y: Double)

    val label: String
    val labelY: Double get() = getY()  // override to place label at a different y than the data point
    val duration: Long
    val shape: Shape
    val size: Float
    val paintStyle: Paint.Style
    @ColorInt fun color(context: Context?): Int
    val hasColorOverride: Boolean get() = false
}