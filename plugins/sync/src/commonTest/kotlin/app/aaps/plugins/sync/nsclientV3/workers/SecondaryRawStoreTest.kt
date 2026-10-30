package app.aaps.plugins.sync.nsclientV3.workers

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.nssdk.localmodel.entry.NSSgvV3
import app.aaps.core.nssdk.localmodel.entry.NsUnits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Raw from the secondary NS on iOS (2026-10-30): BG stays, only a reading without raw takes the second site's. */
class SecondaryRawStoreTest {

    private fun gv(time: Long, noise: Double? = null) =
        GV(timestamp = time, utcOffset = 0L, raw = null, value = 150.0, trendArrow = TrendArrow.FLAT, noise = noise, sourceSensor = SourceSensor.UNKNOWN)

    private fun sgv(value: Double, filtered: Double?, unfiltered: Double?) = NSSgvV3(
        date = 0L, identifier = null, utcOffset = 0L, isValid = true, sgv = value, units = NsUnits.MG_DL, direction = null,
        noise = null, filtered = filtered, unfiltered = unfiltered
    )

    private val secondary = listOf(60_000L to 140.0, 120_000L to 145.0, 300_000L to 160.0)

    @Test
    fun theNearestSecondaryReadingWithinAMinuteIsTaken() {
        assertEquals(145.0, secondaryRawNear(secondary, 110_000L))
        assertEquals(140.0, secondaryRawNear(secondary, 0L))
    }

    @Test
    fun noSecondaryReadingNearLeavesRawEmpty() {
        assertNull(secondaryRawNear(secondary, 200_000L))
        assertNull(secondaryRawNear(emptyList(), 60_000L))
    }

    @Test
    fun aReadingWithoutRawTakesTheSecondaryValueAndKeepsItsBg() {
        val filled = fillMissingRaw(listOf(gv(120_000L)), secondary).single()
        assertEquals(145.0, filled.noise)
        assertEquals(150.0, filled.value)
    }

    @Test
    fun aReadingWithItsOwnRawIsNeverTouched() {
        assertTrue(fillMissingRaw(listOf(gv(120_000L, noise = 130.0)), secondary).isEmpty())
    }

    @Test
    fun realRawCloseToTheBgIsKept() {
        assertTrue(fillMissingRaw(listOf(gv(120_000L, noise = 150.2)), secondary).isEmpty())
    }

    @Test
    fun onlyAllThreeExactlyEqualIsACopy() {
        // Trio's site: filtered = unfiltered = sgv
        assertTrue(sgv(134.0, filtered = 134.0, unfiltered = 134.0).rawIsCopyOfSgv())
        // aapsfour (AAPS, Libre2): real raw
        assertFalse(sgv(94.6, filtered = 100.2, unfiltered = 100.0).rawIsCopyOfSgv())
        assertFalse(sgv(100.0, filtered = 100.2, unfiltered = 100.0).rawIsCopyOfSgv())
        // rsnrjc: no raw fields
        assertFalse(sgv(143.0, filtered = null, unfiltered = null).rawIsCopyOfSgv())
    }

    @Test
    fun aReadingWithNoSecondaryNearIsLeftOut() {
        assertTrue(fillMissingRaw(listOf(gv(200_000L)), secondary).isEmpty())
    }

    @Test
    fun theSwitchCountsAsOffWhileTheSecondSiteOnlySuppliesRaw() {
        assertTrue(glucoseFromSecondarySite(secondaryEnabled = true, bgFromThisConnection = true))
        assertFalse(glucoseFromSecondarySite(secondaryEnabled = true, bgFromThisConnection = true, rawFromSecondary = true))
        assertTrue(rawFromSecondarySite(secondaryEnabled = true, rawFromSecondary = true))
        assertFalse(rawFromSecondarySite(secondaryEnabled = false, rawFromSecondary = true))
    }

    @Test
    fun theStoreKeepsSixHoursOldestFirstWithoutDuplicates() {
        val store = SecondaryRawStore()
        val now = 10L * 60 * 60 * 1000
        store.add(listOf(now - 60_000L to 120.0, now - 7L * 60 * 60 * 1000 to 100.0), now)
        store.add(listOf(now - 120_000L to 118.0, now - 60_000L to 121.0), now)
        assertEquals(listOf(now - 120_000L to 118.0, now - 60_000L to 121.0), store.snapshot())
        assertEquals(now - 60_000L, store.newest)
    }
}
