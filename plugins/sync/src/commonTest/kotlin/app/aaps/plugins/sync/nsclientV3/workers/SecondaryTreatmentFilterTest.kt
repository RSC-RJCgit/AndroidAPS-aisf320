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
        assertTrue(secondaryTherapyEventAccepted(TE.Type.NOTE, "StLow 12"))
        assertTrue(secondaryTherapyEventAccepted(TE.Type.NOTE, "SetRole x=y"))
        assertTrue(secondaryTherapyEventAccepted(TE.Type.NOTE, "MJ active"))
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
}
