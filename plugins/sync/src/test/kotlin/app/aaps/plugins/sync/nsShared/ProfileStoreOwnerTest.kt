package app.aaps.plugins.sync.nsShared

import app.aaps.plugins.sync.nsShared.ProfileStoreOwner.Decision
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.Test

class ProfileStoreOwnerTest {

    private fun store() = JSONObject().put("defaultProfile", "Default").put("subject", "aaps-a15").put("app", "AAPS")

    @Test
    fun `fork tag wins over token and app`() {
        assertThat(ProfileStoreOwner.identify(ProfileStoreOwner.tag(store(), "Live-A36"))).isEqualTo("Live-A36")
    }

    @Test
    fun `token name is used without fork tag`() {
        assertThat(ProfileStoreOwner.identify(store())).isEqualTo("ns:aaps-a15")
    }

    @Test
    fun `app or enteredBy is used without token name`() {
        assertThat(ProfileStoreOwner.identify(JSONObject().put("enteredBy", "Trio"))).isEqualTo("app:Trio")
        assertThat(ProfileStoreOwner.identify(JSONObject().put("app", "AAPS").put("enteredBy", "Trio"))).isEqualTo("app:AAPS")
    }

    @Test
    fun `blank or missing fields give unknown`() {
        assertThat(ProfileStoreOwner.identify(JSONObject())).isEqualTo(ProfileStoreOwner.UNKNOWN)
        assertThat(ProfileStoreOwner.identify(JSONObject().put(ProfileStoreOwner.OWNER_FIELD, " "))).isEqualTo(ProfileStoreOwner.UNKNOWN)
        assertThat(ProfileStoreOwner.identify(JSONObject().put("subject", 5))).isEqualTo(ProfileStoreOwner.UNKNOWN)
    }

    @Test
    fun `tag leaves the original store alone`() {
        val original = store()
        val tagged = ProfileStoreOwner.tag(original, "Live-A36")
        assertThat(original.has(ProfileStoreOwner.OWNER_FIELD)).isFalse()
        assertThat(tagged.getString("defaultProfile")).isEqualTo("Default")
    }

    @Test
    fun `empty setting learns, star takes any, otherwise only the same`() {
        assertThat(ProfileStoreOwner.decide("ns:aaps-a15", "")).isEqualTo(Decision.ACCEPT_AND_LEARN)
        assertThat(ProfileStoreOwner.decide("ns:aaps-a15", "*")).isEqualTo(Decision.ACCEPT)
        assertThat(ProfileStoreOwner.decide("Live-A36", " Live-A36 ")).isEqualTo(Decision.ACCEPT)
        assertThat(ProfileStoreOwner.decide("ns:aaps-a15", "Live-A36")).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `new owner id has six hex digits`() {
        repeat(20) { assertThat(ProfileStoreOwner.newOwnerId()).matches("aaps-[0-9a-f]{6}") }
    }
}
