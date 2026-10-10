package app.aaps.implementation.maintenance

import kotlin.test.Test
import kotlin.test.assertEquals

class ExportNamesTest {

    @Test
    fun patientNameAndPhoneModelAreJoined() {
        assertEquals("KMPvirtual_SMF711B", exportScopeName("KMPvirtual", "SM-F711B"))
    }

    @Test
    fun aBlankPatientNameDropsThePhone() {
        assertEquals("", exportScopeName("  ", "SM-F711B"))
    }

    @Test
    fun theFileNameKeepsTheScopeBetweenThePrefixAndTheTime() {
        assertEquals(
            "AutoISF_KMPvirtual_SMF711B_20260928_121349.csv",
            exportNamedFile("AutoISF", "KMPvirtual_SMF711B", "20260928_121349", "csv"),
        )
        assertEquals(
            "AutoISF_20260928_121349.csv",
            exportNamedFile("AutoISF", "", "20260928_121349", "csv"),
        )
    }
}
