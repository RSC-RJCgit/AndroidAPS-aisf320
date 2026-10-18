package app.aaps.core.interfaces.aps

/**
 * Steps and movement measured by this phone itself (AutoISF 3.2.1 activity monitor).
 *
 * The 5, 10 and 15 minute values are single 5-minute blocks: the steps 0-5, 5-10 and 10-15 minutes ago.
 * The 30, 60 and 180 minute values are totals. This is how AutoISF 3.2.1 counts them.
 *
 * On a device without a step counter (iOS and desktop for now) every count is 0 and [phoneMoved] is false.
 * The activity monitor then says that the phone seems not to be carried and changes nothing, the same as
 * on an Android phone without the sensor.
 */
interface PhoneSteps {

    /** Starts listening to the sensors. Called once at app start. */
    fun start()

    fun steps5Min(): Int
    fun steps10Min(): Int
    fun steps15Min(): Int
    fun steps30Min(): Int
    fun steps60Min(): Int
    fun steps180Min(): Int

    /** True when the phone was moved in the last 15 minutes, so it seems to be carried. */
    fun phoneMoved(): Boolean
}
