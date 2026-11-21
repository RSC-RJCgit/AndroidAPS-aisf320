package app.aaps.ui.compose.overview.graphs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.layer.CartesianLayerDimensions
import com.patrykandpatrick.vico.compose.cartesian.layer.CartesianLayerMargins
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer

/**
 * A line layer without the top and bottom margin of its points (2026-11-21, per explicit request, Simple graphs).
 *
 * Every graph has the invisible normalizer line ([NORMALIZER_POINT_SIZE], 48 dp points) so the synced graphs lay out
 * alike across. Vico also keeps half a point free above and below the plot for it: on a 100 dp graph that was 24 dp at the
 * top and 24 dp at the bottom (measured on the Windows client: an 83 px graph drew a 23 px plot). This layer keeps the
 * point sizes for the spacing across, so the graphs stay in line, and keeps only a small margin for the line strokes.
 */
internal class CompactLineCartesianLayer(
    lineProvider: LineCartesianLayer.LineProvider,
    rangeProvider: CartesianLayerRangeProvider,
    verticalAxisPosition: Axis.Position.Vertical?,
) : LineCartesianLayer(lineProvider, rangeProvider = rangeProvider, verticalAxisPosition = verticalAxisPosition) {

    override fun updateLayerMargins(
        context: CartesianMeasuringContext,
        layerMargins: CartesianLayerMargins,
        layerDimensions: CartesianLayerDimensions,
        model: LineCartesianLayerModel,
    ) {
        with(context) {
            val margin = COMPACT_LAYER_MARGIN.pixels
            layerMargins.ensureValuesAtLeast(top = margin, bottom = margin)
        }
    }
}

/** Room above and below the plot of a [CompactLineCartesianLayer], for half a line stroke. */
private val COMPACT_LAYER_MARGIN = 2.dp

/** [rememberLineCartesianLayer] or, when [compact], a remembered [CompactLineCartesianLayer] with the same inputs. */
@Composable
internal fun rememberGraphLineLayer(
    lineProvider: LineCartesianLayer.LineProvider,
    rangeProvider: CartesianLayerRangeProvider,
    verticalAxisPosition: Axis.Position.Vertical,
    compact: Boolean,
): LineCartesianLayer {
    val normal = com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer(
        lineProvider = lineProvider,
        rangeProvider = rangeProvider,
        verticalAxisPosition = verticalAxisPosition
    )
    val compactLayer = remember(lineProvider, rangeProvider, verticalAxisPosition) {
        CompactLineCartesianLayer(lineProvider, rangeProvider, verticalAxisPosition)
    }
    return if (compact) compactLayer else normal
}
