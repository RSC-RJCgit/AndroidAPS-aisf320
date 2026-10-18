package app.aaps.core.objects.wizard

/**
 * Delayed doses (protein/fat and carb split parts) wait while the user walks (2026-10-18, per explicit request), with the
 * loop's own "moving" test (DetermineBasalAutoISF, the little-walking SMB cut): S60 > 300, S30 > 100, S15 > 30 or S5 > 10.
 * [steps15Bin] is the steps 10 to 15 minutes ago, the way the loop reads S15. A dose that keeps waiting is cancelled at the
 * end of its retry window, as for the BG check.
 */
fun movingForDelayedDose(steps5: Int, steps15Bin: Int, steps30: Int, steps60: Int): Boolean =
    steps60 > 300 || steps30 > 100 || steps15Bin > 30 || steps5 > 10
