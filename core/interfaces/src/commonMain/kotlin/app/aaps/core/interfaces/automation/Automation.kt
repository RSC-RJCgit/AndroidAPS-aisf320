package app.aaps.core.interfaces.automation

import kotlinx.coroutines.flow.StateFlow

interface Automation {

    /**
     * Live snapshot of the automation event list. Emits on user edits, ordering changes, and
     * NS-synced reloads. Replaces the prior `EventAutomationDataChanged` RxBus broadcast.
     */
    val events: StateFlow<List<AutomationEvent>>

    /**
     * Single source of truth for "automation executes here". True only on a master device; clients
     * (AAPSCLIENT) edit + sync definitions but never run them. UI surfaces that offer to run/execute
     * a user action must hide it when this is false. Execution itself is also hard-gated internally.
     */
    val executionEnabled: Boolean

    fun findEventById(id: String): AutomationEvent?
    suspend fun processEvent(someEvent: AutomationEvent)

    /**
     * Generate reminder via [app.aaps.plugins.automation.TimerUtil]
     *
     */
    fun scheduleAutomationEventBolusReminder()

    /**
     * Remove scheduled reminder from automations
     *
     */
    fun removeAutomationEventBolusReminder()

    /**
     * Generate reminder via [app.aaps.plugins.automation.TimerUtil]
     *
     * @param seconds seconds to the future
     */
    fun scheduleTimeToEatReminder(seconds: Int)

    /**
     * Remove Automation event
     */
    fun removeAutomationEventEatReminder()

    /**
     * Create new Automation event to alarm when is time to eat
     */
    fun scheduleAutomationEventEatReminder()

    /**
     * Native automation titles that are close to a coded AutoISF name and have no saved decision yet.
     * Button actions are not included. An exact name is not included either.
     */
    fun pendingCodedAutomationReviews(): List<String>

    /**
     * Save the review. A true value allows that exact title to keep running while custom AutoISF
     * automations are on. A false value keeps it blocked while that switch is on.
     */
    fun saveCodedAutomationDecisions(accepted: Map<String, Boolean>)
}