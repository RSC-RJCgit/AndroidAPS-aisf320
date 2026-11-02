package app.aaps.plugins.sync.nsclientV3.trioremote

import app.aaps.core.nssdk.trioremote.TrioRemoteCrypto
import app.aaps.core.nssdk.trioremote.TrioRemoteMessage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TrioSimulatorProtocolTest {
    private val now = 1_700_000_000_000L
    private val secret = "test-secret"

    @Test
    fun allSixNormalTrioCommandsAreDecoded() {
        val messages = listOf(
            TrioRemoteMessage(TrioRemoteMessage.BOLUS, "Tester", now / 1000.0, bolusAmount = 1.25),
            TrioRemoteMessage(TrioRemoteMessage.TEMP_TARGET, "Tester", now / 1000.0, target = 110, duration = 60),
            TrioRemoteMessage(TrioRemoteMessage.CANCEL_TEMP_TARGET, "Tester", now / 1000.0),
            TrioRemoteMessage(TrioRemoteMessage.MEAL, "Tester", now / 1000.0, carbs = 20, fat = 5, protein = 10, scheduledTimeSeconds = now / 1000.0 - 300),
            TrioRemoteMessage(TrioRemoteMessage.START_OVERRIDE, "Tester", now / 1000.0, overrideName = "Test override"),
            TrioRemoteMessage(TrioRemoteMessage.CANCEL_OVERRIDE, "Tester", now / 1000.0)
        )
        val actions = messages.map { TrioSimulatorProtocol.decode(it.toPush(secret, "Test"), secret, now) }
        assertEquals(6, actions.map { it.fingerprint }.toSet().size)
        assertEquals("Bolus 1.25 U", actions.first().summary)
        assertEquals("Start override Test override", actions[4].summary)
    }

    @Test
    fun reencryptingTheSameCommandStillHasTheSameReplayFingerprint() {
        val message = TrioRemoteMessage(TrioRemoteMessage.BOLUS, "Tester", now / 1000.0, bolusAmount = 1.25)
        val first = message.toPush(secret, "Test")
        val second = message.toPush(secret, "Test")
        assertNotEquals(first, second)
        assertEquals(TrioSimulatorProtocol.decode(first, secret, now).fingerprint, TrioSimulatorProtocol.decode(second, secret, now).fingerprint)
    }

    @Test
    fun wrongSecretsAndChangedCiphertextAreRejected() {
        val push = TrioRemoteMessage(TrioRemoteMessage.CANCEL_OVERRIDE, "Tester", now / 1000.0).toPush(secret, "Test")
        assertFails { TrioSimulatorProtocol.decode(push, "wrong-secret", now) }
        assertFails { TrioSimulatorProtocol.decode("""{"encrypted_data":"broken"}""", secret, now) }
    }

    @Test
    fun expiredAndFutureCommandsAreRejected() {
        val push = TrioRemoteMessage(TrioRemoteMessage.CANCEL_OVERRIDE, "Tester", now / 1000.0).toPush(secret, "Test")
        assertFails { TrioSimulatorProtocol.decode(push, secret, now + 600_001) }
        assertFails { TrioSimulatorProtocol.decode(push, secret, now - 30_001) }
        assertTrue(TrioSimulatorProtocol.decode(push, secret, now + 600_000).summary.isNotBlank())
    }

    @Test
    fun invalidCommandsAndDosesAreRejected() {
        for (message in listOf(
            TrioRemoteMessage("unknown", "Tester", now / 1000.0),
            TrioRemoteMessage(TrioRemoteMessage.BOLUS, "Tester", now / 1000.0, bolusAmount = -1.0),
            TrioRemoteMessage(TrioRemoteMessage.TEMP_TARGET, "Tester", now / 1000.0, target = 100, duration = 0),
            TrioRemoteMessage(TrioRemoteMessage.MEAL, "Tester", now / 1000.0),
            TrioRemoteMessage(TrioRemoteMessage.START_OVERRIDE, "Tester", now / 1000.0, overrideName = "")
        )) assertFails { TrioSimulatorProtocol.decode(message.toPush(secret, "Test"), secret, now) }
        val malformed = buildJsonObject { put("encrypted_data", TrioRemoteCrypto.encrypt(secret, "[]")) }.toString()
        assertFails { TrioSimulatorProtocol.decode(malformed, secret, now) }
    }

    @Test
    fun mailboxIdsCannotBeUrlsOrPaths() {
        assertTrue(TrioSimulatorProtocol.validId("virtual-test_1"))
        for (id in listOf("", "../live", "https://example.com", "a".repeat(41))) assertFalse(TrioSimulatorProtocol.validId(id))
    }
}
