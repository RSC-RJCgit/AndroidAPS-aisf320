package app.aaps.plugins.automation

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.VirtualPump
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.smsCommunicator.Sms
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.Inject
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Arrival and exit texts for a few saved places.
 * Places start as "-", which means off. No home address is stored here.
 * Only airport places send a text and a note. Other places are remembered but stay quiet.
 * A virtual pump does nothing. A phone sends texts only when its model matches the saved model.
 */
@Inject
class CodedLocations(
    private val preferences: Preferences,
    private val smsCommunicator: SmsCommunicator,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val dateUtil: DateUtil,
    private val rxBus: RxBus,
    private val aapsLogger: AAPSLogger,
    private val config: Config,
    private val activePlugin: ActivePlugin,
    private val places: PlaceLookup,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val states = mutableMapOf<String, SlotState>()
    private val pointCache = mutableMapOf<String, Pair<Double, Double>?>()
    private var statesLoaded = false

    private val slots = listOf(
        StringKey.AutomationAirport1,
        StringKey.AutomationAirport2,
        StringKey.AutomationAirport3,
        StringKey.AutomationAirport4,
        StringKey.AutomationAirport5,
        StringKey.AutomationAddress1,
        StringKey.AutomationAddress2,
        StringKey.AutomationAddress3,
        StringKey.AutomationAddress4,
        StringKey.AutomationAddress5,
    )

    suspend fun onFix(latitude: Double, longitude: Double, accuracyMetres: Float?) {
        if (!config.AAPSCLIENT && activePlugin.activePump is VirtualPump) return
        if (!preferences.get(BooleanKey.AutomationCodedLocationsEnabled)) {
            states.clear()
            pointCache.clear()
            statesLoaded = false
            return
        }
        if (accuracyMetres != null && accuracyMetres > 1_000f) return
        loadStates()
        slots.forEach { key ->
            val spec = parseCodedLocation(key.key, preferences.get(key)) ?: return@forEach
            val point = resolve(spec) ?: return@forEach
            evaluate(spec, point, latitude, longitude)
        }
    }

    private suspend fun evaluate(spec: LocationSpec, point: Pair<Double, Double>, latitude: Double, longitude: Double) {
        val distance = metresBetween(latitude, longitude, point.first, point.second)
        val signature = spec.signature()
        val prior = states[spec.id]
        if (prior == null || prior.signature != signature) {
            val inside = distance <= spec.radiusMetres
            val initial = SlotState(signature = signature, inside = inside)
            states[spec.id] = initial
            if (inside && thisPhoneSends()) {
                states[spec.id] = initial.copy(lastArrival = dateUtil.now())
                persist()
                if (spec.arrivalNote.isNotBlank()) send(spec, spec.arrivalNote, arriving = true)
            } else {
                persist()
            }
            return
        }
        val exitRadius = spec.radiusMetres + max(75f, spec.radiusMetres * 0.20f)
        val nowInside = if (prior.inside) distance <= exitRadius else distance <= spec.radiusMetres
        if (nowInside == prior.inside) return
        val now = dateUtil.now()
        val lastRun = if (nowInside) prior.lastArrival else prior.lastExit
        val next = if (nowInside) prior.copy(inside = true, lastArrival = now) else prior.copy(inside = false, lastExit = now)
        states[spec.id] = next
        if (lastRun != 0L && now - lastRun < spec.cooldownMinutes * 60_000L) {
            persist()
            return
        }
        persist()
        if (!thisPhoneSends()) return
        val note = if (nowInside) spec.arrivalNote else spec.exitNote
        if (note.isNotBlank()) send(spec, note, nowInside)
    }

    private fun thisPhoneSends(): Boolean {
        val designated = preferences.get(StringKey.AutomationLocationSmsDeviceModel).trim()
        val model = places.model()
        if (designated.isEmpty() || model.isEmpty()) return false
        return designated.equals(model, ignoreCase = true)
    }

    private suspend fun send(spec: LocationSpec, note: String, arriving: Boolean) {
        if (!spec.id.startsWith("automation_airport_")) return
        val movement = if (arriving) "arrival" else "exit"
        val text = "$note: ${spec.label} $movement"
        smsCommunicator.sendNotificationToAllNumbers(text)
        preferences.get(StringKey.AutomationLocationSmsNumbers)
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { number -> smsCommunicator.sendSMS(Sms(number, text)) }
        val units = runCatching { profileFunction.getUnits() }.getOrDefault(GlucoseUnit.MGDL)
        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE(
                timestamp = dateUtil.now(),
                type = TE.Type.NOTE,
                note = note,
                duration = 60_000L,
                glucoseUnit = units,
            ),
            timestamp = dateUtil.now(),
            action = Action.CAREPORTAL,
            source = Sources.Automation,
            note = "Coded location: ${spec.label}",
            listValues = listOf(ValueWithUnit.SimpleString(note)),
        )
        aapsLogger.info(LTag.AUTOMATION, "Coded location fired: $text")
        rxBus.send(EventRefreshOverview("Coded location note", true))
    }

    private fun resolve(spec: LocationSpec): Pair<Double, Double>? {
        pointCache[spec.locationText]?.let { return it }
        val point = parseLatLon(spec.locationText) ?: places.find(spec.locationText)
        if (point != null) pointCache[spec.locationText] = point
        else aapsLogger.error(LTag.AUTOMATION, "Could not resolve coded location '${spec.label}'")
        return point
    }

    private fun loadStates() {
        if (statesLoaded) return
        statesLoaded = true
        val raw = preferences.get(StringNonKey.CodedLocationStates)
        if (raw.isBlank()) return
        runCatching {
            json.decodeFromString<Map<String, SlotState>>(raw)
        }.onSuccess { states.putAll(it) }
            .onFailure { aapsLogger.error(LTag.AUTOMATION, "Could not restore coded location state: ${it.message}") }
    }

    private fun persist() {
        preferences.put(StringNonKey.CodedLocationStates, json.encodeToString(states))
    }
}

internal data class LocationSpec(
    val id: String,
    val label: String,
    val locationText: String,
    val radiusMetres: Float,
    val arrivalNote: String,
    val exitNote: String,
    val cooldownMinutes: Long,
) {
    fun signature(): String = "$label|$locationText|$radiusMetres|$arrivalNote|$exitNote|$cooldownMinutes"
}

@Serializable
private data class SlotState(
    val signature: String,
    val inside: Boolean,
    val lastArrival: Long = 0,
    val lastExit: Long = 0,
)

internal fun parseCodedLocation(id: String, raw: String): LocationSpec? {
    if (raw.isBlank() || raw.trim() == "-") return null
    val fields = raw.split('|')
    if (fields.size != 6) return null
    val radius = fields[2].trim().toFloatOrNull()?.takeIf { it in 50f..10_000f } ?: return null
    val cooldown = fields[5].trim().toLongOrNull()?.takeIf { it in 1L..1_440L } ?: return null
    val label = fields[0].trim()
    val locationText = fields[1].trim()
    val arrival = fields[3].trim()
    val exit = fields[4].trim()
    if (label.isEmpty() || locationText.isEmpty() || (arrival.isEmpty() && exit.isEmpty())) return null
    return LocationSpec(id, label, locationText, radius, arrival, exit, cooldown)
}

internal fun parseLatLon(text: String): Pair<Double, Double>? {
    if (!text.startsWith('@')) return null
    val parts = text.drop(1).split(',')
    if (parts.size != 2) return null
    val latitude = parts[0].trim().toDoubleOrNull() ?: return null
    val longitude = parts[1].trim().toDoubleOrNull() ?: return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return latitude to longitude
}

internal fun metresBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val radius = 6_371_000.0
    val p1 = lat1 * PI / 180.0
    val p2 = lat2 * PI / 180.0
    val dLat = (lat2 - lat1) * PI / 180.0
    val dLon = (lon2 - lon1) * PI / 180.0
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(p1) * cos(p2) * sin(dLon / 2) * sin(dLon / 2)
    return (2 * radius * atan2(sqrt(a), sqrt(1 - a))).toFloat()
}

interface PlaceLookup {
    fun find(query: String): Pair<Double, Double>?
    fun model(): String
}
