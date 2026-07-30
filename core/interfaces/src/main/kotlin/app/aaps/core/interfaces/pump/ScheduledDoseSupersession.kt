package app.aaps.core.interfaces.pump

import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonically increasing token used to detect when a NEWER bolus/carbs entry has superseded an
 * earlier one's still-pending scheduled follow-up doses (BolusWizard's carb-split and protein/fat
 * delayed dosing). Any entry point that records a bolus or carbs (WizardDialog, InsulinDialog,
 * CarbsDialog, TreatmentDialog) calls [bump] once it succeeds; BolusWizard claims the resulting value
 * as its own schedule's token at scheduling time and re-checks it (via [isCurrent]) on every scheduled
 * poll — if a newer entry has since bumped the token, the pending schedule silently retires instead of
 * running alongside the newer one.
 *
 * Deliberately separate from BolusProgressData.followUpBolusCancelled, which serves a narrower purpose
 * (an explicit user "stop" press on the currently-showing progress dialog) — that flag is process-wide
 * and gets reset on every new bolus, so on its own it can't distinguish "a newer entry happened" from
 * "an unrelated bolus somewhere else just started," and would either over-cancel or silently revive an
 * already-cancelled schedule.
 */
object ScheduledDoseSupersession {

    private val token = AtomicLong(0)

    /** Call once a bolus or carbs entry has been successfully recorded, from any entry point. Returns
     *  the new token value — callers that are about to schedule their OWN follow-up doses should keep
     *  this as the token they claim for that schedule (see BolusWizard). */
    fun bump(): Long = token.incrementAndGet()

    /** True if [claimedToken] (captured via [bump] at scheduling time) still matches the current token —
     *  i.e. nothing newer has been recorded since. */
    fun isCurrent(claimedToken: Long): Boolean = token.get() == claimedToken
}
