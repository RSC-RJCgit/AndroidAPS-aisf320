package app.aaps.plugins.automation

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.automation.actions.ActionCarePortalEvent
import app.aaps.plugins.automation.actions.ActionProfileSwitchPercent
import app.aaps.plugins.automation.actions.ActionSetAcceWeight
import app.aaps.plugins.automation.actions.ActionSetAutomationState
import app.aaps.plugins.automation.elements.Comparator
import app.aaps.plugins.automation.triggers.TriggerAutomationState
import app.aaps.plugins.automation.triggers.TriggerBg
import app.aaps.plugins.automation.triggers.TriggerBolusAgo
import app.aaps.plugins.automation.triggers.TriggerCOB
import app.aaps.plugins.automation.triggers.TriggerConnector
import app.aaps.plugins.automation.triggers.TriggerDelta
import app.aaps.plugins.automation.triggers.TriggerIob
import app.aaps.plugins.automation.triggers.TriggerProfilePercent
import app.aaps.plugins.automation.triggers.TriggerTempTarget
import app.aaps.plugins.automation.triggers.TriggerTimeRange
import dagger.android.HasAndroidInjector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hardcoded automation presets registered on every app start.
 * readOnly + systemAction — visible in UI but not editable/deletable.
 * addIfNotExists() deduplicates by title so restart re-registration is safe.
 */
@Singleton
class AutomationPresets @Inject constructor(
    private val injector: HasAndroidInjector,
    private val aapsLogger: AAPSLogger,
) {

    fun registerAll(plugin: AutomationPlugin) {
        aapsLogger.debug(LTag.AUTOMATION, "Registering system automation presets")
        plugin.addIfNotExists(buildMjOff())
        plugin.addIfNotExists(buildMj3())
        plugin.addIfNotExists(buildMj2())
        plugin.addIfNotExists(buildSkittles3ok2BG90())
    }

    // ---------------------------------------------------------------------------
    // Skittles3ok2BG9.0: falling BG with high IOB and no temp target
    //   Triggers: BG<=9.5, COB<=15, SDelta<=-0.8, Delta<=-1.1, IOB>=1.4,
    //             Profile%>=65, bolus>=5min ago, LDelta<=-0.8
    //   Precondition (on weight action): temp target not exists
    //   Actions: set bgAccel_ISF_weight=0.02, profile switch 100%, note "Skit4"
    // ---------------------------------------------------------------------------
    private fun buildSkittles3ok2BG90(): AutomationEventObject =
        AutomationEventObject(injector).apply {
            title = "Skittles3ok2BG9.0"
            systemAction = true
            readOnly = true
            repeatInterval = 5
            trigger = TriggerConnector(injector, TriggerConnector.Type.OR).apply {
                list.add(TriggerConnector(injector, TriggerConnector.Type.AND).apply {
                    list.add(TriggerBg(injector, 9.5, GlucoseUnit.MMOL, Comparator.Compare.IS_EQUAL_OR_LESSER))
                    list.add(TriggerCOB(injector).apply {
                        fromJSON("""{"carbs":15.0,"comparator":"IS_EQUAL_OR_LESSER"}""")
                    })
                    list.add(TriggerDelta(injector, GlucoseUnit.MMOL).apply {
                        fromJSON("""{"value":-0.8,"deltaType":"SHORT_AVERAGE","comparator":"IS_EQUAL_OR_LESSER","units":"mmol"}""")
                    })
                    list.add(TriggerDelta(injector, GlucoseUnit.MMOL).apply {
                        fromJSON("""{"value":-1.1,"deltaType":"DELTA","comparator":"IS_EQUAL_OR_LESSER","units":"mmol"}""")
                    })
                    list.add(TriggerIob(injector).apply {
                        fromJSON("""{"insulin":1.4,"comparator":"IS_EQUAL_OR_GREATER"}""")
                    })
                    list.add(TriggerProfilePercent(injector).apply {
                        fromJSON("""{"percentage":65.0,"comparator":"IS_EQUAL_OR_GREATER"}""")
                    })
                    list.add(TriggerBolusAgo(injector).apply {
                        fromJSON("""{"minutesAgo":5,"comparator":"IS_EQUAL_OR_GREATER"}""")
                    })
                    list.add(TriggerDelta(injector, GlucoseUnit.MMOL).apply {
                        fromJSON("""{"value":-0.8,"deltaType":"LONG_AVERAGE","comparator":"IS_EQUAL_OR_LESSER","units":"mmol"}""")
                    })
                })
            }
            actions.add(ActionSetAcceWeight(injector).apply {
                fromJSON("""{"weight":0.02}""")
                precondition = TriggerTempTarget(injector).apply {
                    fromJSON("""{"comparator":"NOT_EXISTS"}""")
                }
            })
            actions.add(ActionProfileSwitchPercent(injector).apply {
                fromJSON("""{"percentage":100.0,"durationInMinutes":0}""")
            })
            actions.add(ActionCarePortalEvent(injector).apply {
                fromJSON("""{"cpEvent":"NOTE","note":"Skit4"}""")
            })
        }

    // ---------------------------------------------------------------------------
    // MJoff: State MJ=MJ3 AND (time 12:00-21:04 AND BG>=10.5 OR time 00:00-01:00)
    //   → set MJ=NOMJremains + note "MJoff"
    // ---------------------------------------------------------------------------
    private fun buildMjOff(): AutomationEventObject =
        AutomationEventObject(injector).apply {
            title = "MJoff"
            systemAction = true
            readOnly = true
            repeatInterval = 5
            trigger = TriggerConnector(injector, TriggerConnector.Type.OR).apply {
                list.add(TriggerConnector(injector, TriggerConnector.Type.AND).apply {
                    list.add(TriggerTimeRange(injector, 720, 1264))   // 12:00 PM–09:04 PM
                    list.add(TriggerBg(injector, 10.5, GlucoseUnit.MMOL, Comparator.Compare.IS_EQUAL_OR_GREATER))
                    list.add(TriggerAutomationState(injector).apply {
                        fromJSON("""{"stateName":"MJ","stateValue":"MJ3"}""")
                    })
                })
                list.add(TriggerConnector(injector, TriggerConnector.Type.AND).apply {
                    list.add(TriggerTimeRange(injector, 0, 60))        // 12:00 AM–01:00 AM
                    list.add(TriggerAutomationState(injector).apply {
                        fromJSON("""{"stateName":"MJ","stateValue":"MJ3"}""")
                    })
                })
            }
            actions.add(ActionSetAutomationState(injector).apply {
                fromJSON("""{"inputStateName":"MJ","inputState":"NOMJremains"}""")
            })
            actions.add(ActionCarePortalEvent(injector).apply {
                fromJSON("""{"cpEvent":"NOTE","note":"MJoff"}""")
            })
        }

    // ---------------------------------------------------------------------------
    // MJ3: State MJ=MJ2 AND time 01:05–02:05 AM → set MJ=MJ3 + note "MJ3"
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
            actions.add(ActionSetAutomationState(injector).apply {
                fromJSON("""{"inputStateName":"MJ","inputState":"MJ3"}""")
            })
            actions.add(ActionCarePortalEvent(injector).apply {
                fromJSON("""{"cpEvent":"NOTE","note":"MJ3"}""")
            })
        }

    // ---------------------------------------------------------------------------
    // MJ2: State MJ=MJ active AND time 02:10–03:10 AM → set MJ=MJ2 + note "MJ2"
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
            actions.add(ActionSetAutomationState(injector).apply {
                fromJSON("""{"inputStateName":"MJ","inputState":"MJ2"}""")
            })
            actions.add(ActionCarePortalEvent(injector).apply {
                fromJSON("""{"cpEvent":"NOTE","note":"MJ2"}""")
            })
        }
}
