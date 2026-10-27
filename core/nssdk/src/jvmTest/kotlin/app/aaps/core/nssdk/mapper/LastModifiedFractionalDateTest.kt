package app.aaps.core.nssdk.mapper

import app.aaps.core.nssdk.nsSdkJson
import app.aaps.core.nssdk.remotemodel.LastModified
import app.aaps.core.nssdk.remotemodel.NSResponse
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LastModifiedFractionalDateTest {
    @Test
    fun `observed fractional entry timestamp does not reject lastModified response`() {
        val response = nsSdkJson.decodeFromString(
            NSResponse.serializer(LastModified.serializer()),
            """{"status":200,"result":{"srvDate":1793115420443,"collections":{
                "devicestatus":1793115276244,"entries":1793115274887.459,
                "food":1677701859531,"profile":1792239729837,
                "settings":1784606114978,"treatments":6079311787300
            }}}"""
        )
        val collections = checkNotNull(response.result).collections
        assertThat(collections.entries).isEqualTo(1793115274887L)
        assertThat(collections.devicestatus).isEqualTo(1793115276244L)
        assertThat(collections.profile).isEqualTo(1792239729837L)
        assertThat(collections.settings).isEqualTo(1784606114978L)
        assertThat(collections.treatments).isEqualTo(6079311787300L)
        assertThat(collections.foods).isEqualTo(0L)
    }

    @Test
    fun `every collection accepts fractions and omitted collections retain zero`() {
        val modified = nsSdkJson.decodeFromString(
            LastModified.serializer(),
            """{"collections":{"devicestatus":1000.75,"entries":2000.25,
                "profile":3000.5,"treatments":4000.9,"foods":5000.1,"settings":6000.8}}"""
        )
        assertThat(modified.collections).isEqualTo(LastModified.Collections(1000L, 2000L, 3000L, 4000L, 5000L, 6000L))
        val empty = nsSdkJson.decodeFromString(LastModified.serializer(), """{"collections":{}}""")
        assertThat(empty.collections).isEqualTo(LastModified.Collections())
    }

    @Test
    fun `integer timestamps retain exact precision on round trip`() {
        val modified = nsSdkJson.decodeFromString(
            LastModified.serializer(), """{"collections":{"entries":9007199254740993}}"""
        )
        assertThat(modified.collections.entries).isEqualTo(9007199254740993L)
        val encoded = nsSdkJson.encodeToString(LastModified.serializer(), modified)
        assertThat(encoded).contains("9007199254740993")
    }

    @Test
    fun `invalid and overflowing timestamps still fail rather than becoming zero`() {
        for (value in listOf("\"invalid\"", "1e30")) {
            assertThrows<SerializationException> {
                nsSdkJson.decodeFromString(LastModified.serializer(), """{"collections":{"entries":$value}}""")
            }
        }
    }
}
