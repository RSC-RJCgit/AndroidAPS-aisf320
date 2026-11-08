package app.aaps.plugins.sync.nsclientV3

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random

/**
 * Who uploaded a Nightscout profile store, and whether this phone takes it (2026-11-08, per explicit request).
 *
 * Nightscout keeps one profile store per site and the newest upload wins. A second master on the same site (a plain
 * upstream phone, whose upload switch hardened mode can force on) once replaced every client's profile list with its
 * "Default" profile. A store is therefore taken only from one identity, learned from the first store this phone loads.
 *
 * The identity, first match wins:
 * 1. [OWNER_FIELD], written by this fork's masters ([tag]),
 * 2. `subject`, the access-token name the Nightscout server stamps on every v3 upload,
 * 3. `app` or `enteredBy` (Trio and other uploaders that carry neither of the above),
 * 4. [UNKNOWN].
 */
object ProfileStoreOwner {

    const val OWNER_FIELD = "aisfOwner"
    const val ANY = "*"
    const val UNKNOWN = "unknown"

    fun identify(json: JsonObject): String {
        json.text(OWNER_FIELD)?.let { return it }
        json.text("subject")?.let { return "ns:$it" }
        (json.text("app") ?: json.text("enteredBy"))?.let { return "app:$it" }
        return UNKNOWN
    }

    /** A copy of [json] carrying this master's [ownerId]. */
    fun tag(json: JsonObject, ownerId: String): JsonObject = JsonObject(json + (OWNER_FIELD to JsonPrimitive(ownerId)))

    /** A new owner ID for a master that has none yet, for example "aaps-3f9c1a". The user can rename it. */
    fun newOwnerId(): String = "aaps-" + Random.nextInt(0x100000, 0x1000000).toString(16)

    enum class Decision {

        /** Take it, and remember [identify] as the only identity from now on. */
        ACCEPT_AND_LEARN,
        ACCEPT,
        IGNORE
    }

    /** [acceptFrom] is the "Accept profile store only from" setting: empty learns, [ANY] takes every store. */
    fun decide(identity: String, acceptFrom: String): Decision {
        val wanted = acceptFrom.trim()
        return when {
            wanted.isEmpty()   -> Decision.ACCEPT_AND_LEARN
            wanted == ANY      -> Decision.ACCEPT
            wanted == identity -> Decision.ACCEPT
            else               -> Decision.IGNORE
        }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
