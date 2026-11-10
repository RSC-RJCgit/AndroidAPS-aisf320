package app.aaps.plugins.sync.nsShared

import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.notes.NoteSmsTexts
import org.json.JSONObject

/** Prepares optional display text for the Nightscout upload that already carries this device status. */
internal fun PersistenceLayer.fullCareportalNoteTextsForUpload(
    config: Config,
    preferences: Preferences,
    through: Long,
    logger: AAPSLogger
): JSONObject? {
    if (!config.appInitialized || !config.APS || config.AAPSCLIENT ||
        !preferences.get(BooleanKey.NsSyncFullCareportalNotes)
    ) return null

    return runCatching {
        val texts = NoteSmsTexts.all(preferences)
        if (texts.isEmpty()) return null
        val noteTimes = getTherapyEventDataFromTime(
            through - NoteSmsTexts.DEVICE_STATUS_WINDOW_MS, TE.Type.NOTE, ascending = false
        ).asSequence()
            .filter { it.isValid && !it.note.isNullOrBlank() && it.timestamp in texts }
            .map { it.timestamp }.toSet()
        NoteSmsTexts.forDeviceStatus(texts, noteTimes, through)
    }.onFailure { logger.error(LTag.NSCLIENT, "Could not include full note text in device status", it) }
        .getOrNull()
}
