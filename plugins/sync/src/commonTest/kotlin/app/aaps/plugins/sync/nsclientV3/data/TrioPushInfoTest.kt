package app.aaps.plugins.sync.nsclientV3.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The profile below has the fields Trio's `NightscoutManager` uploads (2026-10-29) */
class TrioPushInfoTest {

    private val site = "https://trio.example.com/"

    private fun profile(extra: String = ""): JsonObject = Json.parseToJsonElement(
        """
        {"defaultProfile":"default","enteredBy":"Trio","units":"mmol",
         "bundleIdentifier":"org.nightscout.TEAM123456.trio","deviceToken":"abc123","isAPNSProduction":true,"teamID":"TEAM123456",
         "overridePresets":[{"name":"Exercise","duration":60,"percentage":80,"target":140},{"name":"Sick","percentage":120}]
         $extra}
        """.trimIndent()
    ).jsonObject

    @Test
    fun `the push details and presets are read from a Trio profile`() {
        val info = assertNotNull(trioPushInfo(assertNotNull(trioPushInfoRecord(profile(), site)), site))

        assertEquals("abc123", info.details.deviceToken)
        assertEquals("org.nightscout.TEAM123456.trio", info.details.bundleId)
        assertEquals("TEAM123456", info.details.teamId)
        assertEquals(true, info.details.production)
        assertEquals(listOf("Exercise", "Sick"), info.presets.map { it.name })
        assertEquals(60, info.presets[0].durationMinutes)
        assertEquals(80.0, info.presets[0].percentage)
        assertNull(info.presets[1].durationMinutes)
    }

    /** An AAPS profile has no device token */
    @Test
    fun `a profile without a device token gives nothing`() {
        assertNull(trioPushInfoRecord(Json.parseToJsonElement("""{"defaultProfile":"x"}""").jsonObject, site))
    }

    /** After the Nightscout URL is changed, the old Trio must not be the one that gets the commands */
    @Test
    fun `details from another site are not used`() {
        val record = assertNotNull(trioPushInfoRecord(profile(), site))

        assertNull(trioPushInfo(record, "https://other.example.com"))
        assertNotNull(trioPushInfo(record, "https://trio.example.com"))
    }

    @Test
    fun `an empty record gives nothing`() {
        assertNull(trioPushInfo("", site))
    }
}
