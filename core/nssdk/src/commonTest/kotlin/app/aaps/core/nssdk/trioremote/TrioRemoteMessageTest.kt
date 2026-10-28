package app.aaps.core.nssdk.trioremote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The field names are Trio's. A wrong one is not an error in Trio, the value is simply not there. */
class TrioRemoteMessageTest {

    @Test
    fun `a bolus has the names Trio reads`() {
        val json = TrioRemoteMessage(TrioRemoteMessage.BOLUS, "Dad", 1_800_000_000.5, bolusAmount = 1.5).toJson()

        assertEquals(setOf("user", "command_type", "timestamp", "bolus_amount"), json.keys)
        assertEquals("bolus", json["command_type"]?.jsonPrimitive?.content)
        assertEquals("1.5", json["bolus_amount"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a temp target sends the target and the minutes`() {
        val json = TrioRemoteMessage(TrioRemoteMessage.TEMP_TARGET, "Dad", 1.0, target = 140, duration = 60).toJson()

        assertEquals(setOf("user", "command_type", "timestamp", "target", "duration"), json.keys)
    }

    @Test
    fun `a meal sends only what was given`() {
        val json = TrioRemoteMessage(TrioRemoteMessage.MEAL, "Dad", 1.0, carbs = 20, scheduledTimeSeconds = 2.0).toJson()

        assertEquals(setOf("user", "command_type", "timestamp", "carbs", "scheduled_time"), json.keys)
    }

    @Test
    fun `an override is named the way Trio names it`() {
        val json = TrioRemoteMessage(TrioRemoteMessage.START_OVERRIDE, "Dad", 1.0, overrideName = "Exercise").toJson()

        assertEquals("Exercise", json["overrideName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the push carries the command encrypted and an alert`() {
        val message = TrioRemoteMessage(TrioRemoteMessage.CANCEL_TEMP_TARGET, "Dad", 1.0)
        val push = Json.parseToJsonElement(message.toPush("secret", "Remote Command: Cancel Temp Target")).jsonObject

        val aps = push["aps"] as JsonObject
        assertEquals(JsonPrimitive(1), aps["content-available"])
        assertEquals("time-sensitive", aps["interruption-level"]?.jsonPrimitive?.content)
        val encrypted = assertNotNull(push["encrypted_data"]?.jsonPrimitive?.content)
        assertEquals(message.toJson().toString(), TrioRemoteCrypto.decrypt("secret", encrypted))
    }
}
