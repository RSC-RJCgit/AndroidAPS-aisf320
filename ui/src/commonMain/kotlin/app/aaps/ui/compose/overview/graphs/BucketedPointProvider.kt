package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.DominantIsf
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore

/**
 * PointProvider for BUCKETED BG data - colors points by range (LOW/IN_RANGE/HIGH).
 * Uses lookup map keyed by x-value to find the original BgDataPoint.
 */
@Immutable
class BucketedPointProvider(
    private val dataLookup: Map<Double, BgDataPoint>,
    lowColor: Color,
    inRangeColor: Color,
    highColor: Color,
    acceColor: Color,
    bgColor: Color,
    ppColor: Color,
    duraColor: Color,
    uniformColor: Color? = null,
) : LineCartesianLayer.PointProvider {

    // Pre-build point components for efficiency
    private val lowPoint = createFilledPoint(lowColor)
    private val inRangePoint = createFilledPoint(inRangeColor)
    private val highPoint = createFilledPoint(highColor)
    private val accePoint = createFilledPoint(acceColor)
    private val bgPoint = createFilledPoint(bgColor)
    private val ppPoint = createFilledPoint(ppColor)
    private val duraPoint = createFilledPoint(duraColor)
    private val uniformPoint = uniformColor?.let { createFilledPoint(it) }

    private fun createFilledPoint(color: Color) = LineCartesianLayer.Point(
        component = ShapeComponent(
            fill = Fill(color),
            shape = CircleShape
        ),
        size = 6.dp
    )

    override fun getPoint(
        entry: LineCartesianLayerModel.Entry,
        extraStore: ExtraStore
    ): LineCartesianLayer.Point? {
        uniformPoint?.let { return it }
        val dataPoint = dataLookup[entry.x] ?: return inRangePoint // fallback
        return factorPoint(dataPoint.dominantIsf) ?: when (dataPoint.range) {
            BgRange.LOW      -> lowPoint
            BgRange.IN_RANGE -> inRangePoint
            BgRange.HIGH     -> highPoint
        }
    }

    private fun factorPoint(factor: DominantIsf): LineCartesianLayer.Point? = when (factor) {
        DominantIsf.ACCE -> accePoint
        DominantIsf.BG   -> bgPoint
        DominantIsf.PP   -> ppPoint
        DominantIsf.DURA -> duraPoint
        DominantIsf.NONE -> null
    }

    override fun getLargestPoint(extraStore: ExtraStore): LineCartesianLayer.Point = inRangePoint
}

/**
 * Raw glucose readings. A dominant AutoISF factor replaces the hollow dot.
 * With no factor, the dot stays the normal hollow reading colour.
 */
@Immutable
class ReadingPointProvider(
    private val dataLookup: Map<Double, BgDataPoint>,
    regularColor: Color,
    acceColor: Color,
    bgColor: Color,
    ppColor: Color,
    duraColor: Color,
    uniformColor: Color? = null,
) : LineCartesianLayer.PointProvider {

    private val regularPoint = LineCartesianLayer.Point(
        component = ShapeComponent(
            fill = Fill(Color.Transparent),
            shape = CircleShape,
            strokeFill = Fill(regularColor.copy(alpha = 0.3f)),
            strokeThickness = 1.dp
        ),
        size = 6.dp
    )
    private val accePoint = filled(acceColor)
    private val bgPoint = filled(bgColor)
    private val ppPoint = filled(ppColor)
    private val duraPoint = filled(duraColor)
    private val uniformPoint = uniformColor?.let { filled(it) }

    private fun filled(color: Color) = LineCartesianLayer.Point(
        component = ShapeComponent(fill = Fill(color), shape = CircleShape),
        size = 6.dp
    )

    override fun getPoint(
        entry: LineCartesianLayerModel.Entry,
        extraStore: ExtraStore
    ): LineCartesianLayer.Point? {
        uniformPoint?.let { return it }
        val factor = dataLookup[entry.x]?.dominantIsf ?: return regularPoint
        return when (factor) {
            DominantIsf.ACCE -> accePoint
            DominantIsf.BG   -> bgPoint
            DominantIsf.PP   -> ppPoint
            DominantIsf.DURA -> duraPoint
            DominantIsf.NONE -> regularPoint
        }
    }

    override fun getLargestPoint(extraStore: ExtraStore): LineCartesianLayer.Point = regularPoint
}