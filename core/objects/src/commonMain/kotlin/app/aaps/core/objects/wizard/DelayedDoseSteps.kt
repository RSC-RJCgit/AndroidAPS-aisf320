package app.aaps.core.objects.wizard

/**
 * Delayed doses (protein/fat and carb split parts) are cut while the user walks (2026-10-19, per explicit request). The part
 * that is cut off is dropped, not given later. [steps10Bin] and [steps15Bin] are the 5-minute bins (steps 5 to 10 and 10 to
 * 15 minutes ago), the way the loop reads S10 and S15. Any one limit is enough:
 * - heavy activity, the loop's activity detection (S5/S10/S15 > 300, S30 > 1500, S60 > 2500): x0.25
 * - activity, the loop's activity detection (S5/S10/S15 > 200, S30 > 500, S60 > 800): x0.5
 * - little walking, the loop's "moving" test for the SMB cut (S5 > 10, S15 > 30, S30 > 100, S60 > 300): x0.75
 * - otherwise x1
 */
fun delayedDoseWalkingFactor(steps5: Int, steps10Bin: Int, steps15Bin: Int, steps30: Int, steps60: Int): Double = when {
    steps5 > 300 || steps10Bin > 300 || steps15Bin > 300 || steps30 > 1500 || steps60 > 2500 -> 0.25
    steps5 > 200 || steps10Bin > 200 || steps15Bin > 200 || steps30 > 500 || steps60 > 800   -> 0.5
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
