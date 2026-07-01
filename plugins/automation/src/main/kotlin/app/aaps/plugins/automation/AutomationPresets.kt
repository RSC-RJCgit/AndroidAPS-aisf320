package app.aaps.plugins.automation

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.automation.actions.ActionNotification
import app.aaps.plugins.automation.actions.ActionSetAutomationState
import app.aaps.plugins.automation.elements.Comparator
import app.aaps.plugins.automation.triggers.TriggerAutomationState
import app.aaps.plugins.automation.triggers.TriggerBg
import app.aaps.plugins.automation.triggers.TriggerConnector
import dagger.android.HasAndroidInjector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hardcoded automation presets registered on every app start.
 * Each preset is readOnly + systemAction so users can see but not edit/delete them.
 * addIfNotExists() deduplicates by title, so re-registration on restart is safe.
 *
 * To add a new preset: add a private fun buildXxx(): AutomationEventObject,
 * then call plugin.addIfNotExists(buildXxx()) from registerAll().
 */
@Singleton
class AutomationPresets @Inject constructor(
    private val injector: HasAndroidInjector,
    private val aapsLogger: AAPSLogger,
) {

    fun registerAll(plugin: AutomationPlugin) {
        aapsLogger.debug(LTag.AUTOMATION, "Registering system automation presets")
        plugin.addIfNotExists(buildExampleMjStateReaction())
    }

    // ---------------------------------------------------------------------------
    // Example: when State MJ = MJ4 AND BG > 3.0 mmol → notify + set MJ=NOMJremains
    // Mirrors the screenshot automation but as a permanent, read-only system event.
    // ---------------------------------------------------------------------------
    private fun buildExampleMjStateReaction(): AutomationEventObject =
        AutomationEventObject(injector).apply {
            title = "System: MJ4 detected"
            note = "Auto-generated system preset — do not delete"
            systemAction = true
            readOnly = true
            repeatInterval = 5

            // OR connector at root (matches UI structure; a single AND branch inside)
            trigger = TriggerConnector(injector, TriggerConnector.Type.OR).apply {
                list.add(TriggerConnector(injector, TriggerConnector.Type.AND).apply {
                    list.add(TriggerBg(injector, 3.0, GlucoseUnit.MMOL, Comparator.Compare.IS_GREATER))
                    list.add(TriggerAutomationState(injector).apply {
                        fromJSON("""{"stateName":"MJ","stateValue":"MJ4"}""")
                    })
                })
            }

            actions.add(ActionNotification(injector).apply {
                fromJSON("""{"text":"MJ4 active — transitioning"}""")
            })
            actions.add(ActionSetAutomationState(injector).apply {
                fromJSON("""{"inputStateName":"MJ","inputState":"NOMJremains"}""")
            })
        }
}
