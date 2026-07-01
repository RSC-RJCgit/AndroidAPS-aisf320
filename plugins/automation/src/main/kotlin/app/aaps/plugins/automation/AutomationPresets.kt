package app.aaps.plugins.automation

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.automation.actions.ActionCarePortalEvent
import app.aaps.plugins.automation.actions.ActionNotification
import app.aaps.plugins.automation.actions.ActionSendSMS
import app.aaps.plugins.automation.actions.ActionSetAutomationState
import app.aaps.plugins.automation.elements.Comparator
import app.aaps.plugins.automation.triggers.TriggerAutomationState
import app.aaps.plugins.automation.triggers.TriggerBg
import app.aaps.plugins.automation.triggers.TriggerConnector
import app.aaps.plugins.automation.triggers.TriggerTimeRange
import dagger.android.HasAndroidInjector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hardcoded automation presets registered on every app start.
 * readOnly + systemAction — visible in UI but not editable/deletable.
 * addIfNotExists() deduplicates by title so restart re-registration is safe.
 *
 * To add a preset: add a private fun buildXxx() and call addIfNotExists(buildXxx()) in registerAll().
 */
@Singleton
class AutomationPresets @Inject constructor(
    private val injector: HasAndroidInjector,
    private val aapsLogger: AAPSLogger,
) {

    fun registerAll(plugin: AutomationPlugin) {
        aapsLogger.debug(LTag.AUTOMATION, "Registering system automation presets")
        plugin.addIfNotExists(buildExampleMjStateReaction())
        plugin.addIfNotExists(buildMjOff())
        plugin.addIfNotExists(buildMj3())
        plugin.addIfNotExists(buildMj2())
    }

    // ---------------------------------------------------------------------------
    // Example: BG > 3.0 mmol AND State MJ=MJ4 → notify + set MJ=NOMJremains
    // ---------------------------------------------------------------------------
    private fun buildExampleMjStateReaction(): AutomationEventObject =
        AutomationEventObject(injector).apply {
            title = "System: MJ4 detected"
            note = "Auto-generated system preset"
            systemAction = true
            readOnly = true
            repeatInterval = 5
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

    // ---------------------------------------------------------------------------
    // MJoff: State MJ=MJ3 AND (time 12:00-21:04 AND BG>=10.5) OR (time 00:00-01:00)
    //   → SMS MJoff + set MJ=NOMJremains + note MJoff
    // ---------------------------------------------------------------------------
    private fun buildMjOff(): AutomationEventObject =
        AutomationEventObject(injector).apply {
            title = "MJoff"
            systemAction = true
            readOnly = true
            repeatInterval = 5
            trigger = TriggerConnector(injector, TriggerConnector.Type.OR).apply {
                // Branch 1: daytime high BG
                list.add(TriggerConnector(injector, TriggerConnector.Type.AND).apply {
                    list.add(TriggerTimeRange(injector, 720, 1264))   // 12:00 PM–09:04 PM
                    list.add(TriggerBg(injector, 10.5, GlucoseUnit.MMOL, Comparator.Compare.IS_EQUAL_OR_GREATER))
                    list.add(TriggerAutomationState(injector).apply {
                        fromJSON("""{"stateName":"MJ","stateValue":"MJ3"}""")
                    })
                })
                // Branch 2: early morning window
                list.add(TriggerConnector(injector, TriggerConnector.Type.AND).apply {
                    list.add(TriggerTimeRange(injector, 0, 60))        // 12:00 AM–01:00 AM
                    list.add(TriggerAutomationState(injector).apply {
                        fromJSON("""{"stateName":"MJ","stateValue":"MJ3"}""")
                    })
                })
            }
            actions.add(ActionSendSMS(injector).apply { fromJSON("""{"text":"MJoff"}""") })
            actions.add(ActionSetAutomationState(injector).apply {
                fromJSON("""{"inputStateName":"MJ","inputState":"NOMJremains"}""")
            })
            actions.add(ActionCarePortalEvent(injector).apply {
                fromJSON("""{"cpEvent":"NOTE","note":"MJoff"}""")
            })
        }

    // ---------------------------------------------------------------------------
    // MJ3: State MJ=MJ2 AND time 01:05–02:05 AM → SMS MJ3 + set MJ=MJ3 + note MJ3
    // ---------------------------------------------------------------------------
    private fun buildMj3(): AutomationEventObject =
        AutomationEventObject(injector).apply {
            title = "MJ3"
            systemAction = true
            readOnly = true
            repeatInterval = 5
            trigger = TriggerConnector(injector, TriggerConnector.Type.OR).apply {
                list.add(TriggerConnector(injector, TriggerConnector.Type.AND).apply {
                    list.add(TriggerAutomationState(injector).apply {
                        fromJSON("""{"stateName":"MJ","stateValue":"MJ2"}""")
                    })
                    list.add(TriggerTimeRange(injector, 65, 125))      // 01:05–02:05 AM
                })
            }
            actions.add(ActionSendSMS(injector).apply { fromJSON("""{"text":"MJ3"}""") })
            actions.add(ActionSetAutomationState(injector).apply {
                fromJSON("""{"inputStateName":"MJ","inputState":"MJ3"}""")
            })
            actions.add(ActionCarePortalEvent(injector).apply {
                fromJSON("""{"cpEvent":"NOTE","note":"MJ3"}""")
            })
        }

    // ---------------------------------------------------------------------------
    // MJ2: State MJ=MJ active AND time 02:10–03:10 AM → SMS MJ2 + set MJ=MJ2 + note MJ2
    // ---------------------------------------------------------------------------
    private fun buildMj2(): AutomationEventObject =
        AutomationEventObject(injector).apply {
            title = "MJ2"
            systemAction = true
            readOnly = true
            repeatInterval = 5
            trigger = TriggerConnector(injector, TriggerConnector.Type.OR).apply {
                list.add(TriggerConnector(injector, TriggerConnector.Type.AND).apply {
                    list.add(TriggerAutomationState(injector).apply {
                        fromJSON("""{"stateName":"MJ","stateValue":"MJ active"}""")
                    })
                    list.add(TriggerTimeRange(injector, 130, 190))     // 02:10–03:10 AM
                })
            }
            actions.add(ActionSendSMS(injector).apply { fromJSON("""{"text":"MJ2"}""") })
            actions.add(ActionSetAutomationState(injector).apply {
                fromJSON("""{"inputStateName":"MJ","inputState":"MJ2"}""")
            })
            actions.add(ActionCarePortalEvent(injector).apply {
                fromJSON("""{"cpEvent":"NOTE","note":"MJ2"}""")
            })
        }
}
