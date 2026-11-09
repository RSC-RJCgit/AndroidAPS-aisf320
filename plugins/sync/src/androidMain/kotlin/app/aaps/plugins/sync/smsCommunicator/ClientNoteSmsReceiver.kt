package app.aaps.plugins.sync.smsCommunicator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.plugin.PermissionGroup
import app.aaps.core.interfaces.plugin.PermissionProvider
import app.aaps.core.interfaces.smsCommunicator.Sms
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.notes.NoteSmsTexts
import app.aaps.plugins.sync.SyncStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import java.time.Instant
import java.time.ZoneId

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<PermissionProvider>())
class ClientNoteSmsReceiver(
    private val config: Config,
    private val preferences: Preferences,
    private val dateUtil: DateUtil
) : PermissionProvider {

    override fun requiredPermissions(): List<PermissionGroup> =
        if (config.AAPSCLIENT && senderNumber() != null) buildList {
            add(
                PermissionGroup(
                    permissions = listOf(Manifest.permission.RECEIVE_SMS),
                    rationaleTitle = SyncStrings.permission_sms_title,
                    rationaleDescription = SyncStrings.permission_client_note_sms_description
                )
            )
            if (preferences.get(BooleanKey.ClientNoteSmsInboxRecovery)) {
                add(
                    PermissionGroup(
                        permissions = listOf(Manifest.permission.READ_SMS),
                        rationaleTitle = SyncStrings.permission_client_note_sms_inbox_title,
                        rationaleDescription = SyncStrings.permission_client_note_sms_inbox_description
                    )
                )
            }
        } else emptyList()

    /** A client consumes every broadcast here so none can reach the SMS command handler. */
    suspend fun handleIfClient(message: Sms?): Boolean {
        if (!config.AAPSCLIENT) return false
        val sender = senderNumber() ?: return true
        if (message != null && normaliseNumber(message.phoneNumber) == sender) {
            NoteSmsTexts.untag(message.text)?.let { (noteTimestamp, text) ->
                NoteSmsTexts.save(preferences, noteTimestamp, "", text, dateUtil.now())
            }
        }
        return true
    }

    suspend fun recoverFromInbox(context: Context) {
        val sender = inboxRecoverySender(context) ?: return
        val now = dateUtil.now()
        val receivedAt = Telephony.Sms.DATE
        val texts = mutableMapOf<Long, String>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
            "$receivedAt >= ? AND $receivedAt <= ?",
            arrayOf(inboxRecoveryStart(now, ZoneId.systemDefault()).toString(), now.toString()),
            "$receivedAt DESC, ${Telephony.Sms._ID} DESC"
        )?.use { rows ->
            while (rows.moveToNext()) {
                if (rows.getString(0)?.let(::normaliseNumber) != sender) continue
                val (time, text) = rows.getString(1)?.let(NoteSmsTexts::untag) ?: continue
                texts.putIfAbsent(time, text)
            }
        }
        // The setting or permission may have changed while Android was reading the inbox.
        if (inboxRecoverySender(context) == sender) NoteSmsTexts.recoverMissing(preferences, texts, now)
    }

    private fun inboxRecoverySender(context: Context): String? {
        if (!config.AAPSCLIENT || !preferences.get(BooleanKey.ClientNoteSmsInboxRecovery)) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return null
        return senderNumber()
    }

    private fun senderNumber(): String? = normaliseNumber(preferences.get(StringKey.ClientNoteSmsSender))

    private fun normaliseNumber(number: String): String? =
        number.filterNot { it.isWhitespace() || it in "-()" }.takeIf { PHONE_NUMBER.matches(it) }

    private companion object {
        val PHONE_NUMBER = Regex("""\+?[0-9]+""")
    }
}

internal fun inboxRecoveryStart(now: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

internal fun joinSmsParts(parts: List<Sms>): Sms? {
    val first = parts.firstOrNull() ?: return null
    if (parts.size == 1) return first
    if (parts.any { it.phoneNumber != first.phoneNumber }) return null
    return Sms(first).apply { text = parts.joinToString("") { it.text } }
}
