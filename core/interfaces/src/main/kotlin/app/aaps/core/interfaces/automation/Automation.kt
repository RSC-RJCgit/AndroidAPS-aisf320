package app.aaps.core.interfaces.automation

interface Automation {

    fun userEvents(): List<AutomationEvent>
    fun findEventById(id: String): AutomationEvent?

    /**
     * Returns the enabled state of the automation event with the given id.
     * Returns null if no event with that id is found.
     */
    fun isEventEnabledById(id: String): Boolean?
    suspend fun processEvent(someEvent: AutomationEvent)

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
}