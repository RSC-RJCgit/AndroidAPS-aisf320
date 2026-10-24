package app.aaps.ios.shell.liveactivity

/**
 * The Live Activity on the lock screen, in the Dynamic Island, in the Apple Watch Smart Stack and in CarPlay (2026-10-24, per
 * explicit request; the same way iAPS shows BG there).
 *
 * ActivityKit can only be called from Swift, so the Swift app implements this (`SwiftLiveActivity` in
 * `ios/app/Shared/LiveActivityBridge.swift`) and hands it to `aapsAppViewController`. [IosLiveActivityUpdater] calls it.
 * All values arrive as finished text, so the widget does no unit or number formatting of its own.
 */
interface LiveActivityBridge {

    /**
     * Shows or updates the Live Activity.
     *
     * @param bg glucose in the user's units, for example "7.5"
     * @param arrow trend arrow symbol, or empty when there is none
     * @param delta signed change over 5 minutes, for example "-0.2"
     * @param iob insulin on board with its unit, for example "0.65 U"
     * @param cob carbs on board with its unit, for example "12 g"
     * @param bgTimeMillis time of the glucose value, so the widget can show its age and grey out when it is old
     * @param range -1 below the low line, 1 above the high line, 0 in between
     */
    fun update(bg: String, arrow: String, delta: String, iob: String, cob: String, bgTimeMillis: Long, range: Int)
}
