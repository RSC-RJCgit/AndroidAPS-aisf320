package app.aaps.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodedAutomationNamesTest {

    @Test
    fun punctuationAndCaseStillMatchExactly() {
        assertEquals(CodedAutomationNames.MatchType.EXACT, CodedAutomationNames.classify("bolus given"))
    }

    @Test
    fun aLongerNameIsClose() {
        assertEquals(CodedAutomationNames.MatchType.CLOSE, CodedAutomationNames.classify("NightFrSkip extra"))
    }

    @Test
    fun aShorterNameInsideACodedKeyIsClose() {
        assertEquals(CodedAutomationNames.MatchType.CLOSE, CodedAutomationNames.classify("Pod"))
    }

    @Test
    fun anUnrelatedNameIsLeftAlone() {
        assertEquals(CodedAutomationNames.MatchType.NONE, CodedAutomationNames.classify("Kitchen lights"))
    }

    @Test
    fun anEmptyNameIsLeftAlone() {
        assertEquals(CodedAutomationNames.MatchType.NONE, CodedAutomationNames.classify("   "))
    }

    @Test
    fun theReviewListKeepsUndecidedCloseNamesOnly() {
        val events = listOf(
            "NightFrSkip extra" to false,
            "BolusGiven" to false,
            "Kitchen lights" to false,
            "Pod change button" to true,
            "Pod" to false,
            "Pod" to false
        )
        val pending = CodedAutomationNames.pendingCloseTitles(events, mapOf("Pod" to false))
        assertEquals(listOf("NightFrSkip extra"), pending)
    }

    @Test
    fun nothingIsBlockedWhileTheSwitchIsOff() {
        assertFalse(CodedAutomationNames.nativeEventSuppressed("BolusGiven", emptyMap(), customAutomationsOn = false))
    }

    @Test
    fun anExactNameIsBlockedWhileTheSwitchIsOn() {
        assertTrue(CodedAutomationNames.nativeEventSuppressed("BolusGiven", mapOf("BolusGiven" to true), customAutomationsOn = true))
    }

    @Test
    fun aCloseNameRunsOnlyWhenThatTitleWasAllowed() {
        assertTrue(CodedAutomationNames.nativeEventSuppressed("Pod", emptyMap(), customAutomationsOn = true))
        assertFalse(CodedAutomationNames.nativeEventSuppressed("Pod", mapOf("Pod" to true), customAutomationsOn = true))
        assertTrue(CodedAutomationNames.nativeEventSuppressed("Pod", mapOf("Pod" to false), customAutomationsOn = true))
    }

    @Test
    fun anUnrelatedNameStillRuns() {
        assertFalse(CodedAutomationNames.nativeEventSuppressed("Kitchen lights", emptyMap(), customAutomationsOn = true))
    }

    @Test
    fun decisionsRoundTrip() {
        val stored = CodedAutomationNames.encodeDecisions(mapOf("Pod" to true, "NightFrSkip extra" to false))
        assertEquals(mapOf("Pod" to true, "NightFrSkip extra" to false), CodedAutomationNames.decodeDecisions(stored))
    }

    @Test
    fun aBrokenDecisionFileIsEmpty() {
        assertEquals(emptyMap(), CodedAutomationNames.decodeDecisions("not json"))
    }
}
