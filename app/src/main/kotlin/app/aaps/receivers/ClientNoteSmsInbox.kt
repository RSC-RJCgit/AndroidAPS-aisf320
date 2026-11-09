package app.aaps.receivers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony.Sms
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.notes.NoteSmsTexts
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** Recovers tagged Careportal text that arrived while the client missed SMS broadcasts. */
class ClientNoteSmsInbox @Inject constructor(
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val rxBus: RxBus,
    private val aapsLogger: AAPSLogger
) {

    fun recover(context: Context) {
        if (!preferences.get(BooleanKey.ClientNoteSmsInboxRecovery)) return
        val sender = NoteSmsTexts.normaliseSender(preferences.get(StringKey.ClientNoteSmsSender)) ?: return
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return

        val now = dateUtil.now()
        val zone = ZoneId.systemDefault()
        val from = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val recovered = linkedMapOf<Long, String>()
        try {
            val cursor = context.contentResolver.query(
                Sms.Inbox.CONTENT_URI,
                arrayOf(Sms.ADDRESS, Sms.BODY),
                "${Sms.DATE} >= ? AND ${Sms.DATE} <= ?",
                arrayOf(from.toString(), now.toString()),
                "${Sms.DATE} DESC, ${Sms._ID} DESC"
            ) ?: return
            cursor.use {
                while (it.moveToNext()) {
                    val address = it.getString(0) ?: continue
                    if (NoteSmsTexts.normaliseSender(address) != sender) continue
                    val body = it.getString(1) ?: continue
                    val (timestamp, text) = NoteSmsTexts.untag(body) ?: continue
                    recovered.putIfAbsent(timestamp, text)
                }
            }
        } catch (error: RuntimeException) {
            aapsLogger.warn(LTag.CORE, "Could not read Careportal notes from SMS inbox (${error.javaClass.simpleName})")
            return
        }
        if (!preferences.get(BooleanKey.ClientNoteSmsInboxRecovery) ||
            NoteSmsTexts.normaliseSender(preferences.get(StringKey.ClientNoteSmsSender)) != sender
        ) return
        if (NoteSmsTexts.saveMissing(preferences, recovered, now)) {
            rxBus.send(EventPreferenceChange(StringNonKey.CarePortalNoteSmsTexts.key))
        }
    }
}
