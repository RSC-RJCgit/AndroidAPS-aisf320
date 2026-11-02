package app.aaps.plugins.sync.nsclientV3.trioremote

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.trio.TrioRemoteResult
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.nssdk.trioremote.TrioRemoteCrypto
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/** Test transport only. This class has no pump executor, treatment writer, or AAPS command dispatcher. */
@SingleIn(AppScope::class)
@Inject
class TrioSimulator(
    private val preferences: Preferences,
    private val config: Config,
    private val activePlugin: ActivePlugin,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger,
    private val nsClientV3Plugin: () -> NSClientV3Plugin
) {
    private var job: Job? = null
    private val sendMutex = Mutex()
    private var namespace = ""
    private var cursor = 0L
    private var publishedAt = 0L
    private var enabledBefore = false

    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch {
            while (isActive) {
                try {
                    pollOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    status("Simulator connection failed. Check Nightscout settings read/write permissions.")
                }
                delay(5_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        namespace = ""
        publishedAt = 0L
    }

    private fun receiverAllowed(): Boolean = config.APS && !config.AAPSCLIENT &&
        preferences.get(BooleanKey.TrioSimulatorReceiver) && activePlugin.activePump is VirtualPump

    /** Public within the module for tests that advance one receiver poll at a time. */
    internal suspend fun pollOnce() {
        val enabled = preferences.get(BooleanKey.TrioSimulatorReceiver)
        if (!enabled) {
            if (enabledBefore) status("Simulator receiver stopped.")
            enabledBefore = false
            namespace = ""
            publishedAt = 0L
            return
        }
        enabledBefore = true
        if (!receiverAllowed()) {
            status("Simulator blocked: use a full v4 master with VirtualPump.")
            publishedAt = 0L
            return
        }
        val id = preferences.get(StringKey.TrioSimulatorId).trim()
        val secret = preferences.get(StringKey.TrioSimulatorSecret)
        if (!TrioSimulatorProtocol.validId(id) || secret.isBlank()) {
            status("Enter a simulator ID and shared secret on both devices.")
            return
        }
        val plugin = nsClientV3Plugin()
        val client = plugin.nsAndroidClient ?: run {
            status("Simulator waiting for Nightscout.")
            return
        }
        val site = preferences.get(StringKey.NsClientUrl).trim().trimEnd('/')
        val currentNamespace = TrioRemoteCrypto.digest("$site|$id")
        val now = dateUtil.now()
        if (namespace != currentNamespace) {
            namespace = currentNamespace
            cursor = now - TrioSimulatorProtocol.MAX_AGE_MS
            publishedAt = 0L
        }
        if (publishedAt == 0L || now - publishedAt >= 10_000L) {
            val response = client.updateSettings(TrioSimulatorProtocol.receiverId(id), TrioSimulatorProtocol.document {
                put("kind", TrioSimulatorProtocol.RECEIVER_KIND)
                put("receiver", id)
                put("virtualOnly", true)
                put("seenAt", now)
            })
            check(response.response in 200..299) { "Descriptor not published" }
            publishedAt = now
            val currentStatus = preferences.get(StringKey.TrioSimulatorStatus)
            if (currentStatus.isBlank() || currentStatus.startsWith("Simulator waiting") ||
                currentStatus.startsWith("Simulator connection failed") || currentStatus.startsWith("Simulator blocked") ||
                currentStatus.startsWith("Simulator receiver stopped") || currentStatus.startsWith("Enter a simulator")
            )
                status("Simulator ready: $id. No Apple push or pump actions.")
        }
        val response = client.getSettingsModifiedSince(cursor, 500)
        check(response.code in 200..299) { "Mailbox not read" }
        var nextCursor = cursor
        for (record in response.values) {
            val modified = (record["srvModified"] as? JsonPrimitive)?.longOrNull
            if (modified != null) nextCursor = maxOf(nextCursor, modified - 1)
            if (record.text("kind") != TrioSimulatorProtocol.REQUEST_KIND || record.text("receiver") != id) continue
            if ((record["completed"] as? JsonPrimitive)?.booleanOrNull == true) continue
            val identifier = record.text("identifier") ?: continue
            if (!identifier.startsWith("${TrioSimulatorProtocol.receiverId(id)}-")) continue
            if (!receiverAllowed()) return
            val payload = record.text("push") ?: continue
            val action = try {
                TrioSimulatorProtocol.decode(payload, secret, dateUtil.now())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                status("Simulator rejected an invalid, expired or unauthenticated command.")
                continue
            }
            val receiptKey = TrioRemoteCrypto.digest("$currentNamespace|${action.fingerprint}")
            val receipts = runCatching {
                Json.parseToJsonElement(preferences.get(StringNonKey.TrioSimulatorReceipts)) as JsonObject
            }.getOrDefault(JsonObject(emptyMap()))
            val seen = receipts[receiptKey] != null
            // Persist before acknowledging, so retries and restarts cannot record the same action twice.
            if (!seen) {
                val recent = receipts.filterValues { value ->
                    (value as? JsonPrimitive)?.longOrNull?.let { it >= now - TrioSimulatorProtocol.MAX_AGE_MS } == true
                }.toList().takeLast(1999).toMap()
                preferences.put(StringNonKey.TrioSimulatorReceipts, JsonObject(recent + (receiptKey to JsonPrimitive(now))).toString())
                status("SIMULATED ${action.summary}. No action applied. At ${dateUtil.dateAndTimeString(now)}")
                aapsLogger.info(LTag.NSCLIENT, "Trio simulator recorded ${action.summary}; no pump or treatment action")
            }
            val reply = buildJsonObject {
                put("identifier", identifier)
                put("fingerprint", action.fingerprint)
                put("summary", action.summary)
                put("recorded", true)
                put("at", dateUtil.now())
            }
            val updated = client.updateSettings(identifier, TrioSimulatorProtocol.document {
                put("kind", TrioSimulatorProtocol.REQUEST_KIND)
                put("receiver", id)
                put("completed", true)
                put("reply", TrioRemoteCrypto.encrypt(secret, reply.toString()))
            })
            check(updated.response in 200..299) { "Reply not saved" }
        }
        cursor = nextCursor
    }

    /** No fallback to APNs, even if the receiver is unavailable or a setting changes while sending. */
    internal suspend fun send(push: String): TrioRemoteResult = sendMutex.withLock {
        try {
            val id = preferences.get(StringKey.TrioSimulatorId).trim()
            val secret = preferences.get(StringKey.TrioSimulatorSecret)
            if (!TrioSimulatorProtocol.validId(id) || secret.isBlank()) return@withLock TrioRemoteResult.Failed("Simulator ID or secret missing")
            val action = TrioSimulatorProtocol.decode(push, secret, dateUtil.now())
            val client = nsClientV3Plugin().nsAndroidClient ?: return@withLock TrioRemoteResult.Failed("Nightscout is not connected")
            val descriptor = client.getSettings(TrioSimulatorProtocol.receiverId(id)).values
            val seenAt = (descriptor?.get("seenAt") as? JsonPrimitive)?.longOrNull ?: 0L
            if (descriptor?.text("kind") != TrioSimulatorProtocol.RECEIVER_KIND || descriptor.text("receiver") != id ||
                (descriptor["virtualOnly"] as? JsonPrimitive)?.booleanOrNull != true ||
                seenAt <= 0 || seenAt > dateUtil.now() || dateUtil.now() - seenAt > 30_000L
            ) return@withLock TrioRemoteResult.Failed("No active Virtual master simulator with this ID")
            val identifier = "${TrioSimulatorProtocol.receiverId(id)}-${Uuid.random()}"
            val created = client.updateSettings(identifier, TrioSimulatorProtocol.document {
                put("kind", TrioSimulatorProtocol.REQUEST_KIND)
                put("receiver", id)
                put("push", push)
            })
            if (created.response !in 200..299) return@withLock TrioRemoteResult.Failed("Simulator command could not be uploaded")
            repeat(20) {
                delay(1_000L)
                val record = client.getSettings(identifier).values ?: return@repeat
                val sealed = record.text("reply") ?: return@repeat
                val plain = TrioRemoteCrypto.decrypt(secret, sealed) ?: return@withLock TrioRemoteResult.Failed("Simulator reply could not be authenticated")
                val reply = Json.parseToJsonElement(plain) as? JsonObject ?: return@repeat
                val at = (reply["at"] as? JsonPrimitive)?.longOrNull ?: 0L
                if (reply.text("identifier") == identifier && reply.text("fingerprint") == action.fingerprint &&
                    (reply["recorded"] as? JsonPrimitive)?.booleanOrNull == true && at > 0 && at <= dateUtil.now() + 30_000L && dateUtil.now() - at <= 60_000L
                ) return@withLock TrioRemoteResult.Simulated(reply.text("summary") ?: action.summary)
            }
            TrioRemoteResult.Failed("No simulator confirmation yet. Check receiver status; the test message may still be pending.")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            TrioRemoteResult.Failed("Simulator connection failed. Check Nightscout settings permissions and the test secret.")
        }
    }

    private fun status(message: String) {
        if (preferences.get(StringKey.TrioSimulatorStatus) != message) preferences.put(StringKey.TrioSimulatorStatus, message)
    }
}
