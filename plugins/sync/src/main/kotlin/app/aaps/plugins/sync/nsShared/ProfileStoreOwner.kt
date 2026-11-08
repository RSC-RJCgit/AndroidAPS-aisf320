package app.aaps.plugins.sync.nsShared

import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONObject
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
 *
 * Same rules as `ProfileStoreOwner` in the v4 line, so a v4 client and a v3 client agree on an identity.
 */
object ProfileStoreOwner {

    const val OWNER_FIELD = "aisfOwner"
    const val ANY = "*"
    const val UNKNOWN = "unknown"

    fun identify(json: JSONObject): String {
        json.text(OWNER_FIELD)?.let { return it }
        json.text("subject")?.let { return "ns:$it" }
        (json.text("app") ?: json.text("enteredBy"))?.let { return "app:$it" }
        return UNKNOWN
    }

    /** A copy of [json] carrying this master's [ownerId]; [json] itself is left alone. */
    fun tag(json: JSONObject, ownerId: String): JSONObject = JSONObject(json.toString()).put(OWNER_FIELD, ownerId)

    /** A new owner ID for a master that has none yet, for example "aaps-3f9c1a". The user can rename it. */
    fun newOwnerId(): String = "aaps-" + Random.nextInt(0x100000, 0x1000000).toString(16)

    /** [json] tagged with this master's owner ID, which is made and saved on the first upload when the setting is empty. */
    fun tagForUpload(json: JSONObject, preferences: Preferences): JSONObject {
        val ownerId = preferences.get(StringKey.NsClientProfileOwnerId).trim().ifEmpty {
            newOwnerId().also { preferences.put(StringKey.NsClientProfileOwnerId, it) }
        }
        return tag(json, ownerId)
    }

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

    private fun JSONObject.text(key: String): String? =
        (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
}
