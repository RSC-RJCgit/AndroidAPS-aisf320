package app.aaps.ui.compose.wizardDialog

import app.aaps.core.interfaces.trio.TrioRemoteCommand
import app.aaps.ui.compose.trioRemote.TrioRemoteViewModel
import kotlin.math.roundToLong

/**
 * What the bolus wizard sends to Trio (2026-11-07, per explicit request). On a read-only client with Trio remote control
 * set up, the wizard's result goes to Trio instead of being only an example: the meal first, then the bolus, as the
 * Trio remote screen sends them.
 *
 * Fat and protein go as the grams typed, not as this app's split doses, because Trio spreads them with its own settings.
 * Trio has no extended carbs, so those are left out and named on the confirmation.
 */
internal data class WizardTrioSend(
    val meal: TrioRemoteCommand.Meal?,
    val bolus: TrioRemoteCommand.Bolus?,
    /** Extended carbs the wizard worked out, which are not sent */
    val eCarbsNotSent: Int,
    /** A carb time in the future: Trio takes only "eaten earlier", so the meal goes as eaten now */
    val futureCarbTimeIgnored: Boolean
) {

    val isEmpty: Boolean get() = meal == null && bolus == null
}

/**
 * [insulin] is the wizard's final dose, [carbs] its carbs now, [carbTimeMinutes] its carb time (negative = eaten earlier).
 * An earlier carb time goes as Trio's "eaten at", at most [TrioRemoteViewModel.MAX_MINUTES_AGO] minutes back.
 */
internal fun wizardTrioSend(insulin: Double, carbs: Int, fat: Int, protein: Int, eCarbs: Int, carbTimeMinutes: Int, now: Long): WizardTrioSend {
    // Two decimals, as the Trio remote screen sends it
    val units = (insulin.coerceAtLeast(0.0) * 100).roundToLong() / 100.0
    val c = carbs.coerceAtLeast(0)
    val f = fat.coerceAtLeast(0)
    val p = protein.coerceAtLeast(0)
    val eatenAt = if (carbTimeMinutes < 0) now - minOf(-carbTimeMinutes, TrioRemoteViewModel.MAX_MINUTES_AGO) * 60_000L else null
    return WizardTrioSend(
        meal = if (c + f + p > 0) TrioRemoteCommand.Meal(carbs = c, fat = f, protein = p, eatenAt = eatenAt) else null,
        bolus = if (units > 0.0) TrioRemoteCommand.Bolus(units) else null,
        eCarbsNotSent = eCarbs.coerceAtLeast(0),
        futureCarbTimeIgnored = carbTimeMinutes > 0 && c + f + p > 0
    )
}
