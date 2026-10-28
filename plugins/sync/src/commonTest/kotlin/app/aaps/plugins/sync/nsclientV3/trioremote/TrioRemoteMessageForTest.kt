package app.aaps.plugins.sync.nsclientV3.trioremote

import app.aaps.core.interfaces.trio.TrioRemoteCommand
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrioRemoteMessageForTest {

    private fun json(command: TrioRemoteCommand) = TrioRemoteControlImpl.messageFor(command, "Dad", 1_800_000_000.0).toJson()

    @Test
    fun `a bolus is sent with two decimals`() {
        assertEquals("1.2", json(TrioRemoteCommand.Bolus(0.1 + 1.1))["bolus_amount"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a temp target is sent in mg per dL and minutes`() {
        val json = json(TrioRemoteCommand.TempTarget(targetMgdl = 144, durationMinutes = 90))

        assertEquals("temp_target", json["command_type"]?.jsonPrimitive?.content)
        assertEquals("144", json["target"]?.jsonPrimitive?.content)
        assertEquals("90", json["duration"]?.jsonPrimitive?.content)
    }

    /** As in LoopFollow: a value of 0 is left out, not sent as 0 */
    @Test
    fun `a meal leaves out what is zero`() {
        val json = json(TrioRemoteCommand.Meal(carbs = 30, fat = 0, protein = 0, eatenAt = null))

        assertEquals("30", json["carbs"]?.jsonPrimitive?.content)
        assertNull(json["fat"])
        assertNull(json["protein"])
        assertNull(json["scheduled_time"])
    }

    @Test
    fun `a meal eaten earlier is sent in seconds`() {
        val json = json(TrioRemoteCommand.Meal(carbs = 30, fat = 0, protein = 0, eatenAt = 1_799_999_400_000))

        assertEquals(1_799_999_400.0, json["scheduled_time"]?.jsonPrimitive?.content?.toDouble())
    }

    @Test
    fun `an override is started by its name`() {
        val json = json(TrioRemoteCommand.StartOverride("Exercise"))

        assertEquals("start_override", json["command_type"]?.jsonPrimitive?.content)
        assertEquals("Exercise", json["overrideName"]?.jsonPrimitive?.content)
    }
}
