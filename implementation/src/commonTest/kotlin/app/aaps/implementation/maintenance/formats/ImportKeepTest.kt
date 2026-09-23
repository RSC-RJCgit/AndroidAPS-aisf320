package app.aaps.implementation.maintenance.formats

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportKeepTest {

    @Test
    fun thePumpBoxShowsWhenTheFileWouldChangeAPumpKey() {
        val pump = KeyDomain(exact = setOf("pod_state"), prefixes = emptySet())
        val offer = pumpOffer(
            current = mapOf("pod_state" to "live"),
            imported = mapOf("pod_state" to "from-file"),
            pump = pump,
            sessionKeys = setOf("pod_state"),
        )
        assertTrue(offer.showKeepPump)
        assertTrue(offer.keepPumpChecked)
    }

    @Test
    fun thePumpBoxStaysHiddenWhenNothingInThatGroupChanges() {
        val pump = KeyDomain(exact = setOf("pod_state"), prefixes = emptySet())
        val offer = pumpOffer(
            current = mapOf("pod_state" to "live"),
            imported = mapOf("pod_state" to "live"),
            pump = pump,
            sessionKeys = setOf("pod_state"),
        )
        assertFalse(offer.showKeepPump)
        assertFalse(offer.keepPumpChecked)
    }

    @Test
    fun anEmptySessionDoesNotCheckThePumpBoxByItself() {
        val pump = KeyDomain(exact = setOf("pump_setting"), prefixes = emptySet())
        val offer = pumpOffer(
            current = mapOf("pump_setting" to "a"),
            imported = mapOf("pump_setting" to "b"),
            pump = pump,
            sessionKeys = emptySet(),
        )
        assertTrue(offer.showKeepPump)
        assertFalse(offer.keepPumpChecked)
    }

    @Test
    fun bgSourcePrefixFindsTheUploadSwitchAndNotThePatientName() {
        val keys = coreKeysByEnumNamePrefix("BgSource")
        assertTrue(BooleanKey.BgSourceUploadToNs.key in keys)
        assertFalse(StringKey.GeneralPatientName.key in keys)
        assertEquals("patient_name", StringKey.GeneralPatientName.key)
    }
}
