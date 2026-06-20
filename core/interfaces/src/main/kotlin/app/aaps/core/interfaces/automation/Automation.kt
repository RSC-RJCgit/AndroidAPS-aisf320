package app.aaps.core.interfaces.automation

import kotlinx.coroutines.flow.StateFlow

interface Automation {

    /** Emits whenever automation events are saved to preferences — use to observe enabled-state changes. */
    val automationEventsFlow: StateFlow<String>

    /**
     * Returns the enabled state of the automation event with the given id,
     * read directly from the persisted JSON (automationEventsFlow.value) rather than
     * from the in-memory list. Use this instead of findEventById()?.isEnabled when
     * you need the authoritative saved state (e.g. quick-launch visibility checks).
     * Returns null if no event with that id is found.
     */
    fun isEventEnabledById(id: String): Boolean?

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