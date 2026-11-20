package app.aaps.core.objects.wizard

/**
 * Delayed doses (protein/fat and carb split parts) are cut while the user walks (2026-10-19, per explicit request). The part
 * that is cut off is dropped, not given later. [steps10Bin] and [steps15Bin] are the 5-minute bins (steps 5 to 10 and 10 to
 * 15 minutes ago), the way the loop reads S10 and S15. Any one limit is enough:
 * - heavy activity (S5/S10/S15 > 100, S30 > 300, S60 > 500): x0.25
 * - activity (S5/S10/S15 > 50, S30 > 200, S60 > 400): x0.5
 *   (2026-11-20, per explicit request, both lowered from the loop's activity detection, 300/1500/2500 and 200/500/800: on
 *   20 Nov 13:39 S5 199, S30 495, S60 495 counted only as little walking)
 * - little walking, the loop's "moving" test for the SMB cut (S5 > 10, S15 > 30, S30 > 100, S60 > 300): x0.75
 * - otherwise x1
 */
fun delayedDoseWalkingFactor(steps5: Int, steps10Bin: Int, steps15Bin: Int, steps30: Int, steps60: Int): Double = when {
    steps5 > 100 || steps10Bin > 100 || steps15Bin > 100 || steps30 > 300 || steps60 > 500 -> 0.25
    steps5 > 50 || steps10Bin > 50 || steps15Bin > 50 || steps30 > 200 || steps60 > 400     -> 0.5
    steps5 > 10 || steps15Bin > 30 || steps30 > 100 || steps60 > 300                          -> 0.75
    else                                                                                      -> 1.0
}

/** The name of the level for the notes and the log. */
fun delayedDoseWalkingLevel(factor: Double): String = when {
    factor <= 0.25 -> "heavy activity"
    factor <= 0.5  -> "activity"
    factor < 1.0   -> "walking"
    else           -> "not walking"
}
