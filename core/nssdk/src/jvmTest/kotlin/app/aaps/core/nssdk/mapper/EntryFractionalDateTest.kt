package app.aaps.core.nssdk.mapper

import app.aaps.core.nssdk.nsSdkJson
import app.aaps.core.nssdk.remotemodel.NSResponse
import app.aaps.core.nssdk.remotemodel.RemoteEntry
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class EntryFractionalDateTest {
    @Test
    fun `Trio fractional milliseconds decode before the glucose mapper`() {
        val reading = """{"type":"sgv","sgv":123,"date":1793111073955.087,
            "dateString":"2026-10-27T14:24:33.955Z","id":"trio-uuid","identifier":"nightscout-id"}""".toNSSgvV3()
        assertThat(reading?.date).isEqualTo(1793111073955L)
        assertThat(reading?.identifier).isEqualTo("nightscout-id")
    }

    @Test
    fun `fractional numeric date remains authoritative over ISO text`() {
        val reading = """{"type":"sgv","sgv":123,"date":1793110775596.6428,
            "dateString":"2026-10-27T14:19:35.597Z"}""".toNSSgvV3()
        assertThat(reading?.date).isEqualTo(1793110775596L)
    }

    @Test
    fun `an API v3 page can contain integer fractional and quoted dates together`() {
        val response = """{"result":[
            {"type":"sgv","sgv":123,"date":1793111073955},
            {"type":"sgv","sgv":124,"date":1793110775596.6428},
            {"type":"sgv","sgv":125,"date":"1793110475695.5132"}
        ]}"""
        val page = nsSdkJson.decodeFromString(NSResponse.serializer(ListSerializer(RemoteEntry.serializer())), response)
        assertThat(page.result?.mapNotNull { it.toSgv()?.date })
            .containsExactly(1793111073955L, 1793110775596L, 1793110475695L).inOrder()
    }

    @Test
    fun `null date still allows the ISO fallback`() {
        val reading = """{"type":"sgv","sgv":123,"date":null,"dateString":"2026-10-27T14:24:33.955Z"}""".toNSSgvV3()
        assertThat(reading?.date).isEqualTo(1793111073955L)
    }

    @Test
    fun `large integer milliseconds are not rounded through double precision`() {
        val entry = nsSdkJson.decodeFromString(RemoteEntry.serializer(), """{"date":9007199254740993}""")
        assertThat(entry.date).isEqualTo(9007199254740993L)
        val encoded = nsSdkJson.encodeToString(RemoteEntry.serializer(), entry)
        assertThat(nsSdkJson.parseToJsonElement(encoded).jsonObject.getValue("date").jsonPrimitive.content)
            .isEqualTo("9007199254740993")
    }
}
