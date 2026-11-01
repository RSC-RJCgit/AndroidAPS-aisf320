// Calculation ported unchanged from v4. The UI reads the same completed 14-day Dexcom TIR period.
package app.aaps.ui.stats

import app.aaps.core.interfaces.stats.DexcomTIR
import kotlin.math.PI
import kotlin.math.sin

internal data class CgpData(
    val torPct: Double,
    val cvPct: Double,
    val hypoPct: Double,
    val hyperPct: Double,
    val meanGlucose: Double,
    val normalizedValues: List<Double>,
    val referenceValues: List<Double>,
    val pgr: Double
)

private const val BASELINE_OFFSET = 0.18

private const val ANGLE_STEP_DEG = 72.0

// Max values for each axis
private const val TOR_MAX = 100.0

private const val CV_MAX = 60.0

private const val HYPO_MAX = 20.0

private const val HYPER_MAX = 80.0

private const val MEAN_MAX = 300.0

// Reference (healthy) values
private const val TOR_REF = 0.0

private const val CV_REF = 17.0

private const val HYPO_REF = 0.0

private const val HYPER_REF = 0.0

private const val MEAN_REF = 90.0

/**
 * Computes CGP data from DexcomTIR statistics.
 *
 * @param dexcomTir 14-day Dexcom TIR data
 * @return CgpData with raw values, normalized values (0-1), reference values, and PGR score
 */
internal fun computeCgpData(dexcomTir: DexcomTIR): CgpData {
    val torPct = 100.0 - dexcomTir.inRangePct()
    val mean = dexcomTir.mean()
    val sd = dexcomTir.calculateSD()
    val cvPct = if (mean > 0) sd / mean * 100.0 else 0.0
    val hypoPct = dexcomTir.veryLowPct() + dexcomTir.lowPct()
    val hyperPct = dexcomTir.highPct() + dexcomTir.veryHighPct()

    fun normalize(value: Double, max: Double): Double =
        BASELINE_OFFSET + (1.0 - BASELINE_OFFSET) * (value / max).coerceIn(0.0, 1.0)

    val normalizedValues = listOf(
        normalize(torPct, TOR_MAX),
        normalize(cvPct, CV_MAX),
        normalize(hypoPct, HYPO_MAX),
        normalize(hyperPct, HYPER_MAX),
        normalize(mean, MEAN_MAX)
    )

    val referenceValues = listOf(
        normalize(TOR_REF, TOR_MAX),
        normalize(CV_REF, CV_MAX),
        normalize(HYPO_REF, HYPO_MAX),
        normalize(HYPER_REF, HYPER_MAX),
        normalize(MEAN_REF, MEAN_MAX)
    )

    val patientArea = pentagonArea(normalizedValues)
    val referenceArea = pentagonArea(referenceValues)
    val pgr = if (referenceArea > 0) patientArea / referenceArea else 0.0

    return CgpData(
        torPct = torPct,
        cvPct = cvPct,
        hypoPct = hypoPct,
        hyperPct = hyperPct,
        meanGlucose = mean,
        normalizedValues = normalizedValues,
        referenceValues = referenceValues,
        pgr = pgr
    )
}

/**
 * Pentagon area using the shoelace formula:
 * Area = 0.5 * sin(72°) * Σ(r_i * r_{i+1})
 */
private fun pentagonArea(radii: List<Double>): Double {
    val sinAngle = sin(ANGLE_STEP_DEG * PI / 180.0)
    var sum = 0.0
    for (i in radii.indices) {
        sum += radii[i] * radii[(i + 1) % radii.size]
    }
    return 0.5 * sinAngle * sum
}

