package app.aaps.core.nssdk.trioremote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One command for Trio's remote control (2026-10-29, per explicit request), with the field names Trio reads. They are
 * LoopFollow's names, because Trio was written to receive from LoopFollow: see LoopFollow `PushMessage.swift` and Trio
 * `CommandPayload.swift`.
 *
 * A field left null is not sent, as Swift does for an empty optional.
 */
data class TrioRemoteMessage(
    /** Trio's name for the command: "bolus", "temp_target", "cancel_temp_target", "meal", "start_override", "cancel_override" */
    val commandType: String,
    /** The name Trio shows as the sender */
    val user: String,
    /** When it was sent, in seconds. Trio refuses a command older than 10 minutes. */
    val timestampSeconds: Double,
    val bolusAmount: Double? = null,
    /** Always mg/dL */
    val target: Int? = null,
    /** Minutes */
    val duration: Int? = null,
    val carbs: Int? = null,
    val protein: Int? = null,
    val fat: Int? = null,
    val overrideName: String? = null,
    /** When the meal was eaten, in seconds, when it was not now */
    val scheduledTimeSeconds: Double? = null
) {

    /** The command before encryption */
    fun toJson(): JsonObject = buildJsonObject {
        put("user", user)
        put("command_type", commandType)
        put("timestamp", timestampSeconds)
        bolusAmount?.let { put("bolus_amount", it) }
        target?.let { put("target", it) }
        duration?.let { put("duration", it) }
        carbs?.let { put("carbs", it) }
        protein?.let { put("protein", it) }
        fat?.let { put("fat", it) }
        // Trio's own name, not snake case
        overrideName?.let { put("overrideName", it) }
        scheduledTimeSeconds?.let { put("scheduled_time", it) }
    }

    /**
     * What is sent to Apple: the encrypted command, and a visible alert so iOS wakes Trio for it.
     *
     * @param title the alert text, which Trio shows. "Remote Command: Bolus" in LoopFollow.
     */
    fun toPush(sharedSecret: String, title: String): String = buildJsonObject {
        putJsonObject("aps") {
            put("content-available", 1)
            put("interruption-level", "time-sensitive")
            put("alert", title)
        }
        put("encrypted_data", TrioRemoteCrypto.encrypt(sharedSecret, toJson().toString()))
    }.toString()

    companion object {

        const val BOLUS = "bolus"
        const val TEMP_TARGET = "temp_target"
        const val CANCEL_TEMP_TARGET = "cancel_temp_target"
        const val MEAL = "meal"
        const val START_OVERRIDE = "start_override"
        const val CANCEL_OVERRIDE = "cancel_override"
    }
}
