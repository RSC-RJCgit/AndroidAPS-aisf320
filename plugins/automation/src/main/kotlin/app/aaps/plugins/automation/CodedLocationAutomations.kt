package app.aaps.plugins.automation

import android.content.Context
import android.location.Geocoder
import android.location.Location
import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.smsCommunicator.Sms
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.NoteTimestampAllocator
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.automation.keys.AutomationStringKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Optional, fixed-slot location automations for carers who need the same small set of arrival/exit
 * messages on every phone. Native Automation rules remain available for ad-hoc locations.
 *
 * A new slot already occupied on its first usable fix sends its arrival outputs. Slot state is
 * persisted so ordinary restarts do not repeat that arrival; later messages require a genuine
 * transition, with exit hysteresis and a per-direction cooldown to suppress GPS flapping.
 */
@Singleton
class CodedLocationAutomations @Inject constructor(
    private val context: Context,
    private val preferences: Preferences,
    private val smsCommunicator: SmsCommunicator,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val dateUtil: DateUtil,
    private val rxBus: RxBus,
    private val aapsLogger: AAPSLogger
) {

    internal data class Spec(
        val id: String,
        val label: String,
        val locationText: String,
        val radiusMetres: Float,
        val arrivalNote: String,
        val exitNote: String,
        val cooldownMinutes: Long
    )

    private data class Point(val latitude: Double, val longitude: Double)
    private data class SlotState(
        var signature: String,
        var inside: Boolean,
        var lastArrival: Long = 0,
        var lastExit: Long = 0
    )

    private val disposables = CompositeDisposable()
    private val states = mutableMapOf<String, SlotState>()
    private val pointCache = mutableMapOf<String, Point?>()
    private var statesLoaded = false

    private val slotKeys = listOf(
        StringKey.AutomationAirport1,
        StringKey.AutomationAirport2,
        StringKey.AutomationAirport3,
        StringKey.AutomationAirport4,
        StringKey.AutomationAirport5,
        StringKey.AutomationAddress1,
        StringKey.AutomationAddress2,
        StringKey.AutomationAddress3,
        StringKey.AutomationAddress4,
        StringKey.AutomationAddress5
    )

    fun reset() {
        states.clear()
        pointCache.clear()
        statesLoaded = false
    }

    fun stop() {
        disposables.clear()
        reset()
    }

    fun onLocation(location: Location) {
        if (!preferences.get(BooleanKey.AutomationCodedLocationsEnabled)) {
            reset()
            return
        }
        // A very poor fix can put a phone kilometres across a geofence. Wait for a useful fix.
        if (location.hasAccuracy() && location.accuracy > 1_000f) return
        loadStates()

        slotKeys.forEach { key ->
            val raw = preferences.get(key)
            val spec = parseSpec(key.key, raw) ?: return@forEach
            val point = resolvePoint(spec) ?: return@forEach
            evaluate(spec, point, location)
        }
    }

    private fun evaluate(spec: Spec, point: Point, location: Location) {
        val result = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, point.latitude, point.longitude, result)
        val distance = result[0]
        val signature = spec.signature()
        val prior = states[spec.id]
        if (prior == null || prior.signature != signature) {
            val inside = distance <= spec.radiusMetres
            val initial = SlotState(signature = signature, inside = inside)
            states[spec.id] = initial
            // A new installation/configured slot that is already occupied counts as an arrival. State
            // is persisted first so a process restart cannot repeat the notification.
            if (inside && spec.arrivalNote.isNotBlank()) {
                initial.lastArrival = dateUtil.now()
                persistStates()
                send(spec, spec.arrivalNote, arriving = true)
            } else persistStates()
            return
        }

        val exitRadius = spec.radiusMetres + max(75f, spec.radiusMetres * 0.20f)
        val nowInside = if (prior.inside) distance <= exitRadius else distance <= spec.radiusMetres
        if (nowInside == prior.inside) return
        prior.inside = nowInside

        val note = if (nowInside) spec.arrivalNote else spec.exitNote
        if (note.isBlank()) {
            persistStates()
            return
        }
        val now = dateUtil.now()
        val lastRun = if (nowInside) prior.lastArrival else prior.lastExit
        if (lastRun != 0L && now - lastRun < spec.cooldownMinutes * 60_000L) {
            persistStates()
            return
        }
        if (nowInside) prior.lastArrival = now else prior.lastExit = now
        persistStates()
        send(spec, note, nowInside)
    }

    private fun send(spec: Spec, note: String, arriving: Boolean) {
        val movement = if (arriving) "arrival" else "exit"
        val text = "$note: ${spec.label} $movement"
        smsCommunicator.sendNotificationToAllNumbers(text)
        preferences.get(StringKey.AutomationLocationSmsNumbers)
            .split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .forEach { number -> smsCommunicator.sendSMS(Sms(number, text)) }

        val therapyEvent = TE(
            timestamp = NoteTimestampAllocator.next(dateUtil.now()),
            type = TE.Type.NOTE,
            glucoseUnit = profileFunction.getUnits()
        ).also {
            it.enteredBy = "AAPS"
            it.note = note
            // Same 1-min duration addCarePortalNote() writes. Duration 0 used Shape.GENERAL and was
            // Y-culled on graph4; Treatments still listed the note. Existing duration=0 rows are
            // covered separately by TherapyEventDataPoint treating every TE.Type.NOTE as
            // GENERAL_WITH_DURATION.
            it.duration = TimeUnit.MINUTES.toMillis(1)
        }
        disposables += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = therapyEvent,
            action = Action.CAREPORTAL,
            source = Sources.Automation,
            note = "Coded location: ${spec.label}",
            listValues = listOf(ValueWithUnit.TEType(TE.Type.NOTE), ValueWithUnit.SimpleString(note))
        ).subscribe(
            {
                aapsLogger.info(LTag.AUTOMATION, "Coded location fired: $text")
                rxBus.send(EventRefreshOverview("Coded location note", true))
            },
            { aapsLogger.error(LTag.AUTOMATION, "Coded location note failed: ${it.message}") }
        )
    }

    private fun resolvePoint(spec: Spec): Point? {
        pointCache[spec.locationText]?.let { return it }
        val point = parsePoint(spec.locationText) ?: geocode(spec.locationText)
        // Do not permanently cache a transient network/geocoder failure; a later location update may
        // resolve it successfully without requiring an app restart.
        if (point != null) pointCache[spec.locationText] = point
        if (point == null) aapsLogger.error(LTag.AUTOMATION, "Could not resolve coded location '${spec.label}'")
        return point
    }

    private fun geocode(address: String): Point? = try {
        @Suppress("DEPRECATION")
        Geocoder(context, Locale.getDefault()).getFromLocationName(address, 1)
            ?.firstOrNull()
            ?.let { Point(it.latitude, it.longitude) }
    } catch (e: Exception) {
        aapsLogger.error(LTag.AUTOMATION, "Geocoder failed for '$address': ${e.message}")
        null
    }

    private fun parsePoint(text: String): Point? {
        if (!text.startsWith('@')) return null
        val parts = text.drop(1).split(',')
        if (parts.size != 2) return null
        val latitude = parts[0].trim().toDoubleOrNull() ?: return null
        val longitude = parts[1].trim().toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return Point(latitude, longitude)
    }

    internal fun parseSpec(id: String, raw: String): Spec? {
        if (raw.isBlank() || raw.trim() == "-") return null
        val fields = raw.split('|')
        // Format: label|location|radius|arrivalNote|exitNote|cooldownMinutes  (6 fields).
        // Was `!= 7` with cooldown read from fields[6] -- an off-by-one: no field 5 was ever used, and
        // every shipped default (airports and addresses) is 6 fields, so nothing parsed and no coded
        // location ever fired. Fixed 2026-09-01.
        if (fields.size != 6) return null
        val radius = fields[2].trim().toFloatOrNull()?.takeIf { it in 50f..10_000f } ?: return null
        val cooldown = fields[5].trim().toLongOrNull()?.takeIf { it in 1..1_440 } ?: return null
        val label = fields[0].trim()
        val locationText = fields[1].trim()
        val arrival = fields[3].trim()
        val exit = fields[4].trim()
        if (label.isBlank() || locationText.isBlank() || (arrival.isBlank() && exit.isBlank())) return null
        return Spec(id, label, locationText, radius, arrival, exit, cooldown)
    }

    private fun Spec.signature(): String = "$label|$locationText|$radiusMetres|$arrivalNote|$exitNote|$cooldownMinutes"

    private fun loadStates() {
        if (statesLoaded) return
        statesLoaded = true
        val json = preferences.get(AutomationStringKey.CodedLocationStates)
        if (json.isBlank()) return
        try {
            val type = object : TypeToken<Map<String, SlotState>>() {}.type
            states.putAll(Gson().fromJson<Map<String, SlotState>>(json, type).orEmpty())
        } catch (e: Exception) {
            aapsLogger.error(LTag.AUTOMATION, "Could not restore coded location state: ${e.message}")
        }
    }

    private fun persistStates() {
        preferences.put(AutomationStringKey.CodedLocationStates, Gson().toJson(states))
    }
}
