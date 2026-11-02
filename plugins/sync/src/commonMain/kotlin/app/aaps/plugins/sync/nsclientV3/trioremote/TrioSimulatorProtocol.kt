package app.aaps.plugins.sync.nsclientV3.trioremote

import app.aaps.core.nssdk.trioremote.TrioRemoteCrypto
import app.aaps.core.nssdk.trioremote.TrioRemoteMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Decodes the normal Trio encrypted push. This result is only a test record, never a pump command. */
internal object TrioSimulatorProtocol {
    const val REQUEST_KIND = "aaps-trio-simulator-command-v1"
    const val RECEIVER_KIND = "aaps-trio-simulator-receiver-v1"
    const val MAX_AGE_MS = 600_000L

    fun validId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9_-]{1,40}"))
    fun receiverId(id: String): String = "aaps-trio-sim-$id"

    /** Nightscout requires these fields; date must stay unchanged when a request becomes a reply. */
    fun document(content: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("date", 946684800001L)
        put("utcOffset", 0)
        put("app", "AAPS")
        content()
    }

    fun decode(push: String, secret: String, now: Long): Action {
        require(secret.isNotBlank()) { "Simulator secret is empty" }
        require(push.length <= 32_768) { "Test payload is too large" }
        val outer = Json.parseToJsonElement(push) as? JsonObject ?: error("Invalid test push")
        val encrypted = outer.text("encrypted_data") ?: error("Missing encrypted command")
        val plain = TrioRemoteCrypto.decrypt(secret, encrypted) ?: error("Simulator secret does not match")
        require(plain.length <= 8192) { "Test command is too large" }
        val command = Json.parseToJsonElement(plain) as? JsonObject ?: error("Invalid test command")
        val user = command.text("user")?.takeIf { it.isNotBlank() && it.length <= 128 } ?: error("Invalid sender")
        val timestamp = command["timestamp"]?.jsonPrimitive?.doubleOrNull ?: error("Missing command time")
        require(timestamp.isFinite() && timestamp * 1000 >= now - MAX_AGE_MS && timestamp * 1000 <= now + 30_000L) { "Command is expired or dated in the future" }
        val type = command.text("command_type") ?: error("Missing command type")
        val message: TrioRemoteMessage
        val summary: String
        fun number(key: String, minimum: Int = 0): Int =
            command[key]?.jsonPrimitive?.intOrNull?.takeIf { it in minimum..10_000 } ?: error("Invalid $key")
        when (type) {
            TrioRemoteMessage.BOLUS -> {
                val dose = command["bolus_amount"]?.jsonPrimitive?.doubleOrNull
                    ?.takeIf { it.isFinite() && it > 0 && it <= 1000 } ?: error("Invalid bolus")
                message = TrioRemoteMessage(type, user, timestamp, bolusAmount = dose)
                summary = "Bolus $dose U"
            }
            TrioRemoteMessage.TEMP_TARGET -> {
                val target = number("target", 1)
                val duration = number("duration", 1)
                message = TrioRemoteMessage(type, user, timestamp, target = target, duration = duration)
                summary = "Temp target $target mg/dL for $duration min"
            }
            TrioRemoteMessage.CANCEL_TEMP_TARGET -> {
                message = TrioRemoteMessage(type, user, timestamp)
                summary = "Cancel temp target"
            }
            TrioRemoteMessage.MEAL -> {
                fun grams(key: String) = if (command[key] == null) 0 else number(key)
                val carbs = grams("carbs")
                val protein = grams("protein")
                val fat = grams("fat")
                require(carbs + protein + fat > 0) { "Empty meal" }
                val eatenAt = command["scheduled_time"]?.jsonPrimitive?.doubleOrNull
                require(command["scheduled_time"] == null || (eatenAt != null && eatenAt.isFinite() && eatenAt >= 0)) { "Invalid meal time" }
                message = TrioRemoteMessage(type, user, timestamp, carbs = carbs.takeIf { it > 0 }, protein = protein.takeIf { it > 0 }, fat = fat.takeIf { it > 0 }, scheduledTimeSeconds = eatenAt)
                summary = "Meal: carbs $carbs g, protein $protein g, fat $fat g"
            }
            TrioRemoteMessage.START_OVERRIDE -> {
                val name = command.text("overrideName")?.takeIf { it.isNotBlank() && it.length <= 100 } ?: error("Invalid override name")
                message = TrioRemoteMessage(type, user, timestamp, overrideName = name)
                summary = "Start override $name"
            }
            TrioRemoteMessage.CANCEL_OVERRIDE -> {
                message = TrioRemoteMessage(type, user, timestamp)
                summary = "Cancel override"
            }
            else -> error("Unsupported test command")
        }
        return Action(TrioRemoteCrypto.digest(message.toJson().toString()), summary)
    }

    data class Action(val fingerprint: String, val summary: String)
}

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content
