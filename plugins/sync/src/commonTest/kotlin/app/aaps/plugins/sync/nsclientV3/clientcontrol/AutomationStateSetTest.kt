package app.aaps.plugins.sync.nsclientV3.clientcontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutomationStateSetTest {

    private val values = """{"MJ":["NOMJremains","MJ active","MJ3"],"LowBG":["50recent","none"]}"""
    private val current = """{"MJ":"MJ3","LowBG":"none"}"""

    @Test
    fun oneStateChangesAndTheOthersStay() {
        assertEquals("""{"MJ":"NOMJremains","LowBG":"none"}""", automationStateSetJson(current, values, "MJ", "NOMJremains"))
    }

    @Test
    fun aStateWithoutACurrentValueGetsOne() {
        assertEquals("""{"MJ":"MJ3","LowBG":"50recent"}""", automationStateSetJson("""{"MJ":"MJ3"}""", values, " LowBG ", "50recent "))
    }

    @Test
    fun anUnknownStateOrValueIsRejected() {
        assertNull(automationStateSetJson(current, values, "Steroids", "SteroidsON"))
        assertNull(automationStateSetJson(current, values, "MJ", "MJ9"))
        assertNull(automationStateSetJson(current, "not json", "MJ", "MJ3"))
    }
}
