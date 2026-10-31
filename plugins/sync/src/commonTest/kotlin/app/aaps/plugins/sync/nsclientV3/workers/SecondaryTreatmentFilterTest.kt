package app.aaps.plugins.sync.nsclientV3.workers

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The secondary site must feed a virtual pump the carbs and boluses a person entered, and must
 * not copy the other phone's SMB deliveries.
 */
class SecondaryTreatmentFilterTest {

    @Test
    fun `clients and Virtual Pump master accept meals without the hidden or separate switches`() {
        assertTrue(secondaryMealTreatmentsAccepted(false, isClient = true, isVirtualMaster = false))
        assertTrue(secondaryMealTreatmentsAccepted(false, isClient = false, isVirtualMaster = true))
    }

    @Test
    fun `real pump still requires the explicit receive switch`() {
        assertFalse(secondaryMealTreatmentsAccepted(false, isClient = false, isVirtualMaster = false))
        assertTrue(secondaryMealTreatmentsAccepted(true, isClient = false, isVirtualMaster = false))
    }

    @Test
    fun `a normal bolus is kept and an SMB is not`() {
        assertTrue(secondaryBolusAccepted(BS.Type.NORMAL))
        assertFalse(secondaryBolusAccepted(BS.Type.SMB))
        assertFalse(secondaryBolusAccepted(BS.Type.PRIMING))
    }

    @Test
    fun `a site change is kept and an ordinary note is not`() {
        assertTrue(secondaryTherapyEventAccepted(TE.Type.CANNULA_CHANGE, null))
        assertFalse(secondaryTherapyEventAccepted(TE.Type.NOTE, "lunch"))
        assertTrue(secondaryTherapyEventAccepted(TE.Type.NOTE, "MJ active"))
        assertTrue(secondaryTherapyEventAccepted(TE.Type.NOTE, " MJ active "))
        assertFalse(secondaryTherapyEventAccepted(TE.Type.NOTE, "MJ2"))
        assertFalse(secondaryTherapyEventAccepted(TE.Type.NOTE, "NOMJremains"))
    }

    @Test
    fun `steps stay on the primary site only while the secondary site is off`() {
        assertTrue(stepsFromPrimarySite(secondaryEnabled = false))
        assertFalse(stepsFromPrimarySite(secondaryEnabled = true))
    }

    @Test
    fun `the full app and a client read glucose from the second site when that switch is on`() {
        assertTrue(glucoseFromSecondarySite(secondaryEnabled = true, bgFromThisConnection = true))
        assertFalse(glucoseFromSecondarySite(secondaryEnabled = true, bgFromThisConnection = false))
        assertFalse(glucoseFromSecondarySite(secondaryEnabled = false, bgFromThisConnection = true))
    }

    // 2026-10-18: the Flip3 full app took BG from nsnewrsc with the NSClient BG source, and aapsfour never got any.
    @Test
    fun bgFromTheLiveSiteIsUploadedToTheMainSiteByAFullApp() {
        val four = "https://aapsfour.ns.example"
        val live = "https://nsnewrsc.ns.example"
        assertTrue(bgUploadAllowed(uploadOn = true, nsClientBgSource = false, bgFromLiveSite = false, isClient = false, primaryUrl = four, secondaryUrl = live))
        assertFalse(bgUploadAllowed(uploadOn = false, nsClientBgSource = false, bgFromLiveSite = false, isClient = false, primaryUrl = four, secondaryUrl = live))
        assertFalse(bgUploadAllowed(uploadOn = true, nsClientBgSource = true, bgFromLiveSite = false, isClient = false, primaryUrl = four, secondaryUrl = live))
        assertTrue(bgUploadAllowed(uploadOn = true, nsClientBgSource = true, bgFromLiveSite = true, isClient = false, primaryUrl = four, secondaryUrl = live))
        assertFalse(bgUploadAllowed(uploadOn = true, nsClientBgSource = true, bgFromLiveSite = true, isClient = true, primaryUrl = four, secondaryUrl = live))
        assertFalse(bgUploadAllowed(uploadOn = true, nsClientBgSource = true, bgFromLiveSite = true, isClient = false, primaryUrl = four, secondaryUrl = "http://AAPSFOUR.ns.example/"))
    }

    @Test
    fun sameSiteIgnoresSchemeCaseAndTrailingSlash() {
        assertTrue(sameNsSite("https://a.example/", "http://A.example"))
        assertFalse(sameNsSite("https://a.example", "https://b.example"))
        assertFalse(sameNsSite("", ""))
    }

    @Test
    fun sameSiteIgnoresAToken() {
        assertTrue(sameNsSite("https://a.example/?token=abc", "https://a.example"))
    }

    // 2026-10-30: a Virtual-pump master never uploads to the site it reads BG from
    @Test
    fun virtualMasterMustNotUploadToItsBgSite() {
        fun blocked(
            virtual: Boolean = true, nsSource: Boolean = true, secondary: Boolean = true, bgFromSecondary: Boolean = true,
            rawOnly: Boolean = false, main: String = "https://upload.example", second: String = "https://live.example"
        ) = uploadSiteIsBgSite(virtual, nsSource, secondary, bgFromSecondary, rawOnly, main, second)

        // The intended setup: BG from the second site, uploads to a separate main site
        assertFalse(blocked())
        // BG read from the main site
        assertTrue(blocked(secondary = false))
        assertTrue(blocked(bgFromSecondary = false))
        assertTrue(blocked(rawOnly = true))
        // The second site is the main site
        assertTrue(blocked(second = "https://UPLOAD.example/"))
        assertTrue(blocked(nsSource = false, second = "https://upload.example"))
        // A BG source on the phone itself, no second site
        assertFalse(blocked(nsSource = false, secondary = false))
        // A real master or a client is never stopped
        assertFalse(blocked(virtual = false, secondary = false))
    }

    /** 1 Nov: the site sent 498 of 500, and the read stopped there, half a day per sync */
    @Test
    fun `a short page still reads on`() {
        assertTrue(secondaryTreatmentsNextPage(code = 200, pageSize = 498, nextCursor = 2_000L, queryFrom = 1_000L))
        assertTrue(secondaryTreatmentsNextPage(code = 200, pageSize = 500, nextCursor = 2_000L, queryFrom = 1_000L))
    }

    @Test
    fun `the read stops when there is nothing new`() {
        // An empty page
        assertFalse(secondaryTreatmentsNextPage(code = 200, pageSize = 0, nextCursor = 2_000L, queryFrom = 1_000L))
        // Not modified
        assertFalse(secondaryTreatmentsNextPage(code = 304, pageSize = 498, nextCursor = 2_000L, queryFrom = 1_000L))
        // The place did not move on, so the same page would come back again and again
        assertFalse(secondaryTreatmentsNextPage(code = 200, pageSize = 498, nextCursor = 1_000L, queryFrom = 1_000L))
        assertFalse(secondaryTreatmentsNextPage(code = 200, pageSize = 498, nextCursor = null, queryFrom = 1_000L))
    }

    /** 1 Nov: a master runs its own roles and relays; of the other phone's notes it takes "MJ active" only */
    @Test
    fun `a master takes no other note from the other phone`() {
        listOf("SetRole autoisf_standard_profile_name=Profile130", "StLow 12", "StorageLow 3", "ADesk", "AckDesk", "AcNS1", "AcTT2 relay-TT receipt", "AcLT3", "MJs3", "Gntl5")
            .forEach { note -> assertFalse(secondaryTherapyEventAccepted(TE.Type.NOTE, note), note) }
    }

    @Test
    fun `a client still takes every note, and site and sensor changes still come in`() {
        assertTrue(secondaryTherapyEventAccepted(TE.Type.NOTE, "SetRole x=y", isClient = true))
        assertTrue(secondaryTherapyEventAccepted(TE.Type.NOTE, "Gntl5", isClient = true))
        assertTrue(secondaryTherapyEventAccepted(TE.Type.CANNULA_CHANGE, null))
        assertTrue(secondaryTherapyEventAccepted(TE.Type.SENSOR_CHANGE, null))
    }

    /** 1 Nov: of the other phone's device events a master takes the pod and sensor changes only */
    @Test
    fun `a master takes pod and sensor changes but not insulin or battery changes`() {
        assertTrue(secondaryTherapyEventAccepted(TE.Type.CANNULA_CHANGE, null))
        assertTrue(secondaryTherapyEventAccepted(TE.Type.SENSOR_CHANGE, null))
        assertTrue(secondaryTherapyEventAccepted(TE.Type.SENSOR_STARTED, null))
        assertFalse(secondaryTherapyEventAccepted(TE.Type.INSULIN_CHANGE, null))
        assertFalse(secondaryTherapyEventAccepted(TE.Type.PUMP_BATTERY_CHANGE, null))
        // A client still takes them
        assertTrue(secondaryTherapyEventAccepted(TE.Type.INSULIN_CHANGE, null, isClient = true))
        assertTrue(secondaryTherapyEventAccepted(TE.Type.PUMP_BATTERY_CHANGE, null, isClient = true))
    }
}
