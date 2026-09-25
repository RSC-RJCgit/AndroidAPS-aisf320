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
    fun `a normal bolus is kept and an SMB is not`() {
        assertTrue(secondaryBolusAccepted(BS.Type.NORMAL))
        assertFalse(secondaryBolusAccepted(BS.Type.SMB))
    }

    @Test
    fun `a site change is kept and an ordinary note is not`() {
        assertTrue(secondaryTherapyEventAccepted(TE.Type.CANNULA_CHANGE, null))
        assertFalse(secondaryTherapyEventAccepted(TE.Type.NOTE, "lunch"))
        assertTrue(secondaryTherapyEventAccepted(TE.Type.NOTE, "StLow 12"))
        assertTrue(secondaryTherapyEventAccepted(TE.Type.NOTE, "SetRole x=y"))
    }
}
