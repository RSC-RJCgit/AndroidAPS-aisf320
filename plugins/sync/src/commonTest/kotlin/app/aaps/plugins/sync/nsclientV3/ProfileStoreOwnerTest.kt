package app.aaps.plugins.sync.nsclientV3

import app.aaps.plugins.sync.nsclientV3.ProfileStoreOwner.Decision
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileStoreOwnerTest {

    private val store = buildJsonObject {
        put("defaultProfile", "Default")
        put("subject", "aaps-a15")
        put("app", "AAPS")
    }

    @Test
    fun forkTagWinsOverTokenAndApp() {
        assertEquals("Live-A36", ProfileStoreOwner.identify(ProfileStoreOwner.tag(store, "Live-A36")))
    }

    @Test
    fun tokenNameIsUsedWithoutForkTag() {
        assertEquals("ns:aaps-a15", ProfileStoreOwner.identify(store))
    }

    @Test
    fun appOrEnteredByIsUsedWithoutTokenName() {
        assertEquals("app:Trio", ProfileStoreOwner.identify(buildJsonObject { put("enteredBy", "Trio") }))
        assertEquals("app:AAPS", ProfileStoreOwner.identify(buildJsonObject { put("app", "AAPS"); put("enteredBy", "Trio") }))
    }

    @Test
    fun blankOrMissingFieldsGiveUnknown() {
        assertEquals(ProfileStoreOwner.UNKNOWN, ProfileStoreOwner.identify(JsonObject(emptyMap())))
        assertEquals(ProfileStoreOwner.UNKNOWN, ProfileStoreOwner.identify(buildJsonObject { put(ProfileStoreOwner.OWNER_FIELD, " ") }))
        assertEquals(ProfileStoreOwner.UNKNOWN, ProfileStoreOwner.identify(buildJsonObject { put("subject", 5) }))
    }

    @Test
    fun tagKeepsTheStoreAndReplacesAnOldTag() {
        val tagged = ProfileStoreOwner.tag(ProfileStoreOwner.tag(store, "old"), "new")
        assertEquals(JsonPrimitive("new"), tagged[ProfileStoreOwner.OWNER_FIELD])
        assertEquals(store["defaultProfile"], tagged["defaultProfile"])
    }

    @Test
    fun emptySettingLearnsStarTakesAnyOtherwiseOnlyTheSame() {
        assertEquals(Decision.ACCEPT_AND_LEARN, ProfileStoreOwner.decide("ns:aaps-a15", ""))
        assertEquals(Decision.ACCEPT_AND_LEARN, ProfileStoreOwner.decide("ns:aaps-a15", "  "))
        assertEquals(Decision.ACCEPT, ProfileStoreOwner.decide("ns:aaps-a15", "*"))
        assertEquals(Decision.ACCEPT, ProfileStoreOwner.decide("Live-A36", " Live-A36 "))
        assertEquals(Decision.IGNORE, ProfileStoreOwner.decide("ns:aaps-a15", "Live-A36"))
    }

    @Test
    fun newOwnerIdHasSixHexDigits() {
        repeat(20) { assertTrue(Regex("aaps-[0-9a-f]{6}").matches(ProfileStoreOwner.newOwnerId())) }
    }
}
