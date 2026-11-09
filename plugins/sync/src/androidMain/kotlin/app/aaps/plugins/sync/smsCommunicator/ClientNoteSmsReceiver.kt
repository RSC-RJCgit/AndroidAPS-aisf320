package app.aaps.plugins.sync.smsCommunicator

import android.Manifest
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.plugin.PermissionGroup
import app.aaps.core.interfaces.plugin.PermissionProvider
import app.aaps.core.interfaces.smsCommunicator.Sms
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.notes.NoteSmsTexts
import app.aaps.plugins.sync.SyncStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<PermissionProvider>())
class ClientNoteSmsReceiver(
    private val config: Config,
    private val preferences: Preferences,
    private val dateUtil: DateUtil
) : PermissionProvider {

    override fun requiredPermissions(): List<PermissionGroup> =
        if (config.AAPSCLIENT && senderNumber() != null) {
            listOf(
                PermissionGroup(
                    permissions = listOf(Manifest.permission.RECEIVE_SMS),
                    rationaleTitle = SyncStrings.permission_sms_title,
                    rationaleDescription = SyncStrings.permission_client_note_sms_description
                )
            )
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

    private fun senderNumber(): String? = normaliseNumber(preferences.get(StringKey.ClientNoteSmsSender))

    private fun normaliseNumber(number: String): String? =
        number.filterNot { it.isWhitespace() || it in "-()" }.takeIf { PHONE_NUMBER.matches(it) }

    private companion object {
        val PHONE_NUMBER = Regex("""\+?[0-9]+""")
    }
}

internal fun joinSmsParts(parts: List<Sms>): Sms? {
    val first = parts.firstOrNull() ?: return null
    if (parts.size == 1) return first
    if (parts.any { it.phoneNumber != first.phoneNumber }) return null
    return Sms(first).apply { text = parts.joinToString("") { it.text } }
}
