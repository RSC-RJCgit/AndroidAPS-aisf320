package app.aaps.plugins.sync.nsclientV3.trioremote

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.trio.TrioRemoteCommand
import app.aaps.core.interfaces.trio.TrioRemoteResult
import app.aaps.core.interfaces.trio.TrioRemoteSetup
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringNonPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.nssdk.interfaces.NSAndroidClient
import app.aaps.core.nssdk.localmodel.treatment.CreateUpdateResponse
import app.aaps.core.nssdk.trioremote.TrioRemoteMessage
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockingDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class TrioSimulatorTest {
    private abstract class TestVirtualPump : Pump, VirtualPump
    private val preferences: Preferences = mock()
    private val config: Config = mock()
    private val activePlugin: ActivePlugin = mock()
    private val dateUtil: DateUtil = mock()
    private val logger: AAPSLogger = mock()
    private val plugin: NSClientV3Plugin = mock()
    private val api: NSAndroidClient = mock()
    private val pump: TestVirtualPump = mock()
    private val strings = mutableMapOf<String, String>()
    private val flags = mutableMapOf<BooleanKey, Boolean>()
    private val mailbox = mutableMapOf<String, JsonObject>()
    private var modified = 1_700_000_000_000L
    private var rejectNextReply = false
    private val secret = "simulator-test-only"
    private val id = "test-master"

    private fun simulator() = TrioSimulator(preferences, config, activePlugin, dateUtil, logger) { plugin }

    private suspend fun TestScope.prepare(): TrioSimulator {
        strings[StringKey.TrioSimulatorId.key] = id
        strings[StringKey.TrioSimulatorSecret.key] = secret
        strings[StringKey.TrioRemoteUser.key] = "Tester"
        strings[StringKey.NsClientUrl.key] = "https://test.example.com"
        flags[BooleanKey.TrioSimulatorReceiver] = true
        flags[BooleanKey.TrioSimulatorSender] = true
        flags[BooleanKey.TrioRemoteEnabled] = true
        whenever(preferences.get(any<StringPreferenceKey>())).thenAnswer {
            val key = it.arguments[0] as StringKey
            strings[key.key] ?: key.defaultValue
        }
        whenever(preferences.get(any<StringNonPreferenceKey>())).thenAnswer {
            val key = it.arguments[0] as StringNonKey
            strings[key.key] ?: key.defaultValue
        }
        whenever(preferences.get(any<BooleanPreferenceKey>())).thenAnswer {
            flags[it.arguments[0] as BooleanKey] ?: false
        }
        doAnswer {
            strings[(it.arguments[0] as StringNonPreferenceKey).key] = it.arguments[1] as String
            null
        }.whenever(preferences).put(any<StringNonPreferenceKey>(), any<String>())
        whenever(config.APS).thenReturn(true)
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(dateUtil.now()).thenAnswer { 1_700_000_000_000L + testScheduler.currentTime }
        whenever(dateUtil.dateAndTimeString(any())).thenReturn("test time")
        whenever(plugin.nsAndroidClient).thenReturn(api)
        whenever(api.getSettings(any())).thenAnswer {
            NSAndroidClient.ReadResponse(200, null, mailbox[it.arguments[0] as String])
        }
        whenever(api.updateSettings(any(), any())).thenAnswer {
            val identifier = it.arguments[0] as String
            val record = it.arguments[1] as JsonObject
            require(record["date"] == JsonPrimitive(946684800001L) && record["utcOffset"] == JsonPrimitive(0) && record.text("app") == "AAPS")
            if (rejectNextReply && (record["completed"] as? JsonPrimitive)?.booleanOrNull == true) {
                rejectNextReply = false
                return@thenAnswer CreateUpdateResponse(503, null)
            }
            mailbox[identifier] = JsonObject(record + mapOf("identifier" to JsonPrimitive(identifier), "srvModified" to JsonPrimitive(++modified)))
            CreateUpdateResponse(200, identifier)
        }
        whenever(api.getSettingsModifiedSince(any(), any())).thenAnswer {
            val from = it.arguments[0] as Long
            NSAndroidClient.ReadResponse(200, modified, mailbox.values.filter { record ->
                (record["srvModified"] as? JsonPrimitive)?.longOrNull?.let { at -> at >= from } == true
            })
        }
        return simulator()
    }

    private fun push(type: String = TrioRemoteMessage.BOLUS): String =
        TrioRemoteMessage(type, "Tester", dateUtil.now() / 1000.0, bolusAmount = 1.25).toPush(secret, "Test")

    private suspend fun post(identifier: String, payload: String) {
        api.updateSettings(identifier, TrioSimulatorProtocol.document {
            put("kind", TrioSimulatorProtocol.REQUEST_KIND)
            put("receiver", id)
            put("push", payload)
        })
    }

    private fun recordedCount(): Int = mockingDetails(logger).invocations.count {
        it.method.name == "info" && it.arguments[1].toString().startsWith("Trio simulator recorded")
    }

    @Test
    fun encryptedRoundTripRecordsTheActionAndReturnsAnAuthenticatedReply() = runTest {
        val receiver = prepare()
        receiver.pollOnce()
        backgroundScope.launch {
            while (isActive) {
                receiver.pollOnce()
                delay(1_000)
            }
        }
        val result = receiver.send(push())
        assertThat(result).isEqualTo(TrioRemoteResult.Simulated("Bolus 1.25 U"))
        assertThat(strings[StringKey.TrioSimulatorStatus.key]).contains("SIMULATED Bolus 1.25 U")
        assertThat(recordedCount()).isEqualTo(1)
        assertThat(mailbox.values.joinToString()).doesNotContain(secret)
        verifyNoInteractions(pump)
    }

    @Test
    fun replayIsAcknowledgedWithoutRecordingAgainEvenAfterReceiverRestart() = runTest {
        val receiver = prepare()
        val payload = push()
        post("${TrioSimulatorProtocol.receiverId(id)}-first", payload)
        receiver.pollOnce()
        assertThat(recordedCount()).isEqualTo(1)
        post("${TrioSimulatorProtocol.receiverId(id)}-replay", payload)
        simulator().pollOnce()
        assertThat(recordedCount()).isEqualTo(1)
        assertThat(mailbox["${TrioSimulatorProtocol.receiverId(id)}-replay"]?.text("reply")).isNotEmpty()
        verifyNoInteractions(pump)
    }

    @Test
    fun realPumpsAndClientBuildsCannotRunTheReceiver() = runTest {
        val receiver = prepare()
        val realPump: Pump = mock()
        whenever(activePlugin.activePump).thenReturn(realPump)
        receiver.pollOnce()
        assertThat(mailbox).isEmpty()
        assertThat(strings[StringKey.TrioSimulatorStatus.key]).contains("blocked")
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(config.AAPSCLIENT).thenReturn(true)
        receiver.pollOnce()
        assertThat(mailbox).isEmpty()
        verifyNoInteractions(realPump, pump)
    }

    @Test
    fun disabledReceiverAndWrongSecretDoNotRecordActions() = runTest {
        val receiver = prepare()
        post("${TrioSimulatorProtocol.receiverId(id)}-test", push())
        flags[BooleanKey.TrioSimulatorReceiver] = false
        receiver.pollOnce()
        assertThat(recordedCount()).isEqualTo(0)
        flags[BooleanKey.TrioSimulatorReceiver] = true
        strings[StringKey.TrioSimulatorSecret.key] = "wrong"
        receiver.pollOnce()
        assertThat(recordedCount()).isEqualTo(0)
        assertThat(strings[StringKey.TrioSimulatorStatus.key]).contains("rejected")
        verifyNoInteractions(pump)
    }

    @Test
    fun anUnavailableSimulatorFailsWithoutFallingBackToApplePush() = runTest {
        val receiver = prepare()
        val control = TrioRemoteControlImpl(preferences, dateUtil, logger, receiver)
        val result = control.send(TrioRemoteCommand.Bolus(1.25), expectedSimulator = true)
        assertThat((result as TrioRemoteResult.Failed).reason).contains("No active Virtual master")
        assertThat(mailbox).isEmpty()
        verifyNoInteractions(pump)
    }

    @Test
    fun failedReplyUploadCanBeRetriedWithoutRecordingTwice() = runTest {
        val receiver = prepare()
        post("${TrioSimulatorProtocol.receiverId(id)}-retry", push())
        rejectNextReply = true
        assertFailsWith<IllegalStateException> { receiver.pollOnce() }
        assertThat(recordedCount()).isEqualTo(1)
        receiver.pollOnce()
        assertThat(recordedCount()).isEqualTo(1)
        assertThat(mailbox["${TrioSimulatorProtocol.receiverId(id)}-retry"]?.text("reply")).isNotEmpty()
        verifyNoInteractions(pump)
    }

    @Test
    fun simulatorSetupNeedsNoAppleCredentialsButRealTrioStillDoes() = runTest {
        val receiver = prepare()
        val control = TrioRemoteControlImpl(preferences, dateUtil, logger, receiver)
        assertThat(control.setup().ready).isTrue()
        assertThat(control.setup().simulator).isTrue()
        flags[BooleanKey.TrioSimulatorSender] = false
        val real = control.setup()
        assertThat(real.simulator).isFalse()
        assertThat(real.missing).containsAtLeast(TrioRemoteSetup.Missing.KEY_ID, TrioRemoteSetup.Missing.APNS_KEY, TrioRemoteSetup.Missing.TRIO_PROFILE)
        val changed = control.send(TrioRemoteCommand.Bolus(1.0), expectedSimulator = true)
        assertThat((changed as TrioRemoteResult.Failed).reason).contains("mode changed")
        assertThat(mailbox).isEmpty()
    }
}
