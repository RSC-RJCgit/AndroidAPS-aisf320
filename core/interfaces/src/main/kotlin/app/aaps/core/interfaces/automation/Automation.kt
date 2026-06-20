package app.aaps.core.interfaces.automation

import kotlinx.coroutines.flow.StateFlow

interface Automation {

    /** Emits whenever automation events are saved to preferences — use to observe enabled-state changes. */
    val automationEventsFlow: StateFlow<String>

    fun userEvents(): List<AutomationEvent>
    fun findEventById(id: String): AutomationEvent?
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