package app.aaps.core.nssdk.networking

import app.aaps.core.nssdk.NSAndroidClientImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Instant

class TrioRecentDeviceStatusTest {
    private lateinit var server: MockWebServer
    private lateinit var client: NSAndroidClientImpl

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NSAndroidClientImpl(server.url("/").toString().trimEnd('/'), "token", false, { }, Dispatchers.Unconfined)
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `recent query is bounded and empty data stays empty`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"result":[]}""").build())
        assertThat(client.getRecentTrioDeviceStatus(0L)).isEmpty()
        assertThat(server.takeRequest().target).isEqualTo("/api/v3/devicestatus?sort\$desc=created_at&limit=20")
    }

    @Test
    fun `only recent Trio records are returned oldest first with AIV intact`() = runTest {
        val now = Clock.System.now().toEpochMilliseconds()
        fun iso(offset: Long) = Instant.fromEpochMilliseconds(now + offset).toString()
        server.enqueue(MockResponse.Builder().code(200).body(
            """{"result":[
                {"device":"Trio","identifier":"newer","created_at":"${iso(-60_000)}","openaps":{"suggested":{"ISF":129,"auto_ISFratio":1.2}}},
                {"device":"Trio","identifier":"older","created_at":"${iso(-120_000)}"},
                {"device":"Trio","identifier":"stale","created_at":"${iso(-600_000)}"},
                {"device":"openaps://master","identifier":"other","created_at":"${iso(-30_000)}"},
                {"device":"Trio","identifier":"bad-date","created_at":"invalid"},
                {"device":"Trio","identifier":"future","created_at":"${iso(600_000)}"}
            ]}"""
        ).build())
        val statuses = client.getRecentTrioDeviceStatus(now - 420_000)
        assertThat(statuses.map { it.identifier }).containsExactly("older", "newer").inOrder()
        assertThat(statuses.last().openaps?.suggested.toString()).contains("129")
        assertThat(statuses.last().openaps?.suggested.toString()).contains("auto_ISFratio")
    }
}
