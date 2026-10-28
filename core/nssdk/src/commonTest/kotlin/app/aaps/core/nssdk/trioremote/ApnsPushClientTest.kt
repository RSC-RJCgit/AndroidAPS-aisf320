package app.aaps.core.nssdk.trioremote

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Apple's server cannot be reached from a test. Ktor's mock engine answers, so the request itself can be checked. */
class ApnsPushClientTest {

    private val pem = CryptographyProvider.Default.get(ECDSA).keyPairGenerator(EC.Curve.P256).generateKeyBlocking()
        .privateKey.encodeToByteArrayBlocking(EC.PrivateKey.Format.PEM).decodeToString()
    private val requests = mutableListOf<HttpRequestData>()
    private var now = 1_800_000_000L

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String = ""): ApnsPushClient {
        val engine = MockEngine { request ->
            requests += request
            respond(ByteReadChannel(body), status)
        }
        return ApnsPushClient(HttpClient(engine) { expectSuccess = false }, nowSeconds = { now })
    }

    private suspend fun ApnsPushClient.sendOne(production: Boolean = true) = send(
        deviceToken = "abc123",
        bundleId = "org.nightscout.trio",
        production = production,
        keyId = "ABCDE12345",
        teamId = "TEAM123456",
        apnsKey = pem,
        collapseId = "bolus",
        body = "{}"
    )

    @Test
    fun `the push goes to the device with LoopFollow's headers`() = runTest {
        assertIs<ApnsPushClient.Result.Sent>(client().sendOne())

        val request = requests.single()
        assertEquals("https://api.push.apple.com/3/device/abc123", request.url.toString())
        assertEquals("org.nightscout.trio", request.headers["apns-topic"])
        assertEquals("alert", request.headers["apns-push-type"])
        assertEquals("10", request.headers["apns-priority"])
        assertEquals("bolus", request.headers["apns-collapse-id"])
        assertEquals((now + 600).toString(), request.headers["apns-expiration"])
    }

    /** A Trio built in Xcode gets its pushes from Apple's development server */
    @Test
    fun `a development build is sent to the sandbox`() = runTest {
        client().sendOne(production = false)

        assertEquals("api.sandbox.push.apple.com", requests.single().url.host)
    }

    @Test
    fun `Apple's reason is passed on`() = runTest {
        val result = client(HttpStatusCode.BadRequest, """{"reason":"BadDeviceToken"}""").sendOne()

        assertEquals(ApnsPushClient.Result.Refused(400, "BadDeviceToken"), result)
    }

    /** Apple refuses a token that is renewed more often than every 20 minutes */
    @Test
    fun `the token is kept between pushes and renewed after 50 minutes`() = runTest {
        val apns = client()
        apns.sendOne()
        now += 10 * 60
        apns.sendOne()
        now += 45 * 60
        apns.sendOne()

        val tokens = requests.map { it.headers["authorization"] }
        assertEquals(tokens[0], tokens[1])
        assertEquals(2, tokens.toSet().size)
    }

    @Test
    fun `a key that is not a key is refused before anything is sent`() = runTest {
        val result = client().send("abc123", "b", true, "ABCDE12345", "TEAM123456", "not a key", "bolus", "{}")

        assertIs<ApnsPushClient.Result.Refused>(result)
        assertEquals(0, requests.size)
    }
}
