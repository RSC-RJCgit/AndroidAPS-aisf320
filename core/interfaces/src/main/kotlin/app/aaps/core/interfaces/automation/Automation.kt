package app.aaps.core.interfaces.automation

interface Automation {

    fun userEvents(): List<AutomationEvent>
    fun processEvent(someEvent: AutomationEvent)

    /**
     * Generate reminder via [app.aaps.plugins.automation.ui.TimerUtil]
     *
     */
    fun scheduleAutomationEventBolusReminder()

    /**
     * Remove scheduled reminder from automations
     *
     */
    fun removeAutomationEventBolusReminder()

    /**
     * Generate reminder via [app.aaps.plugins.automation.ui.TimerUtil]
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
     * Native (non-userAction) event titles currently classified CLOSE against the coded ported-
     * automations registry (see app.aaps.core.utils.CodedAutomationNames) that have no stored
     * accept/deny decision yet. Empty if there's nothing pending review.
     */
    fun pendingCodedAutomationReviews(): List<String>

    /**
     * Persists accept/deny choices from the coded-automation review popup (see
     * [pendingCodedAutomationReviews]), merging into any previously-decided titles.
     */
    fun saveCodedAutomationDecisions(accepted: Map<String, Boolean>)
}