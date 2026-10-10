package app.aaps.core.interfaces.smoothing

/**
 * Smooth a raw glucose series for display and for the AutoISF raw checks.
 *
 * Points are newest first. The result is in the same order and the same length.
 * This pass does not use the live smoother's saved state.
 */
interface DisplayRawSmoothing {
    fun smoothForDisplay(points: List<Pair<Long, Double>>): List<Double>
}
