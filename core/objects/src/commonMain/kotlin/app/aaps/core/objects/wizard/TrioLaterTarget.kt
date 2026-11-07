package app.aaps.core.objects.wizard

/**
 * Where a calculation's later doses go when they are sent to Trio (2026-11-07, per explicit request): the carb split
 * leftover (above max bolus, held for walking soon) and the planned fat/protein doses, including "give rest later".
 * The usual checks still run before each dose. The two that read this phone's own AutoISF loop use Trio's loop instead:
 * the insulinReq cap uses Trio's insulinReq, and the HP1 check uses the lowest of Trio's predictions over the next hour.
 */
class TrioLaterTarget(
    /** Sends a bolus to Trio. Null when it was sent, else why not. */
    val sendBolus: suspend (units: Double) -> String?,
    /** Trio's insulinReq in milli-units, and when Trio worked it out (0 or old = stale), or null when there is none */
    val insulinReq: () -> Pair<Long, Long>?,
    /** The lowest of Trio's predictions over the next hour in milli-mmol/L, and when Trio made them, or null */
    val lowestPrediction: () -> Pair<Long, Long>?,
)
