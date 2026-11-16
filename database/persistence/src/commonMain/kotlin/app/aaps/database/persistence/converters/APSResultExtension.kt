package app.aaps.database.persistence.converters

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.aps.RT
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ArraySerializer
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
private val rtJson = Json {
    allowSpecialFloatingPointValues = true
    ignoreUnknownKeys = true
}

fun app.aaps.database.entities.APSResult.fromDb(apsResultProvider: () -> APSResult): APSResult =
    when (algorithm) {
        app.aaps.database.entities.APSResult.Algorithm.AMA,
        app.aaps.database.entities.APSResult.Algorithm.SMB      ->
            apsResultProvider().with(rtJson.decodeFromString(this.resultJson)).also { result ->
                result.date = this.timestamp
                result.glucoseStatus = this.glucoseStatusJson?.toGlucoseStatus(GlucoseStatusSMB.serializer())
                result.currentTemp = this.currentTempJson?.decodeOrNull()
                result.iobData = this.iobDataJson?.decodeOrNull()
                result.oapsProfile = this.profileJson?.decodeOrNull()
                result.mealData = this.mealDataJson?.decodeOrNull()
                result.autosensResult = this.autosensDataJson?.decodeOrNull()
            }

        app.aaps.database.entities.APSResult.Algorithm.AUTO_ISF ->
            apsResultProvider().with(rtJson.decodeFromString(this.resultJson)).also { result ->
                result.date = this.timestamp
                result.glucoseStatus = this.glucoseStatusJson?.toGlucoseStatus(GlucoseStatusAutoIsf.serializer())
                result.currentTemp = this.currentTempJson?.decodeOrNull()
                result.iobData = this.iobDataJson?.decodeOrNull()
                result.oapsProfileAutoIsf = this.profileJson?.decodeOrNull()
                result.mealData = this.mealDataJson?.decodeOrNull()
                result.autosensResult = this.autosensDataJson?.decodeOrNull()
            }

        else                                                    -> error("Unsupported")
    }

/**
 * Reads the glucose status with the same class that [toDb] wrote.
 *
 * `GlucoseStatus` is an interface, so decoding to it always failed and every result came back without
 * a glucose status. The APS plugins then loaded no variable sensitivity at start, and the 24 h average
 * ISF for the wizard and COB was lost on every restart (#4589). A row that cannot be read loses only
 * its glucose status, not the whole result.
 */
private fun <T : GlucoseStatus> String.toGlucoseStatus(serializer: KSerializer<T>): T? =
    try {
        rtJson.decodeFromString(serializer, this)
    } catch (_: SerializationException) {
        null
    }

/**
 * Reads one stored detail of a loop result, or null when it cannot be read (2026-11-16, per explicit request).
 *
 * A row written by v3 keeps `half_basal_exercise_target` as a decimal in the user's units (6.9 mmol/L), where v4 has a
 * whole number in mg/dL. One such row made every read of the loop results fail, so the overview graph crashed the Flip5
 * master at each start. Such a row now loses only that detail; its time and reason text, which the graph uses, stay.
 * Dosing never reads these details back: each loop builds them from its own settings.
 */
private inline fun <reified T> String.decodeOrNull(): T? =
    try {
        Json.decodeFromString<T>(this)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

@OptIn(ExperimentalSerializationApi::class)
fun APSResult.toDb(): app.aaps.database.entities.APSResult =
    when (algorithm) {
        APSResult.Algorithm.AMA,
        APSResult.Algorithm.SMB      ->
            app.aaps.database.entities.APSResult(
                timestamp = this.date,
                algorithm = this.algorithm.toDb(),
                glucoseStatusJson = this.glucoseStatus?.let { Json.encodeToString(GlucoseStatusSMB.serializer(), it as GlucoseStatusSMB) },
                currentTempJson = this.currentTemp?.let { Json.encodeToString(CurrentTemp.serializer(), it) },
                iobDataJson = this.iobData?.let { Json.encodeToString(ArraySerializer(IobTotal.serializer()), it) },
                profileJson = this.oapsProfile?.let { Json.encodeToString(OapsProfile.serializer(), it) },
                mealDataJson = this.mealData?.let { Json.encodeToString(MealData.serializer(), it) },
                autosensDataJson = this.autosensResult?.let { Json.encodeToString(AutosensResult.serializer(), it) },
                resultJson = rtJson.encodeToString(RT.serializer(), this.rawData() as RT)
            )

        APSResult.Algorithm.AUTO_ISF ->
            app.aaps.database.entities.APSResult(
                timestamp = this.date,
                algorithm = this.algorithm.toDb(),
                glucoseStatusJson = this.glucoseStatus?.let { Json.encodeToString(GlucoseStatusAutoIsf.serializer(), it as GlucoseStatusAutoIsf) },
                currentTempJson = this.currentTemp?.let { Json.encodeToString(CurrentTemp.serializer(), it) },
                iobDataJson = this.iobData?.let { Json.encodeToString(ArraySerializer(IobTotal.serializer()), it) },
                profileJson = this.oapsProfileAutoIsf?.let { Json.encodeToString(OapsProfileAutoIsf.serializer(), it) },
                mealDataJson = this.mealData?.let { Json.encodeToString(MealData.serializer(), it) },
                autosensDataJson = this.autosensResult?.let { Json.encodeToString(AutosensResult.serializer(), it) },
                resultJson = rtJson.encodeToString(RT.serializer(), this.rawData() as RT)
            )

        else                         -> error("Unsupported")
    }

fun app.aaps.database.entities.APSResult.Algorithm.fromDb(): APSResult.Algorithm =
    when (this) {
        app.aaps.database.entities.APSResult.Algorithm.AMA      -> APSResult.Algorithm.AMA
        app.aaps.database.entities.APSResult.Algorithm.SMB      -> APSResult.Algorithm.SMB
        app.aaps.database.entities.APSResult.Algorithm.AUTO_ISF -> APSResult.Algorithm.AUTO_ISF
        else                                                    -> error("Unsupported")
    }

fun APSResult.Algorithm.toDb(): app.aaps.database.entities.APSResult.Algorithm =
    when (this) {
        APSResult.Algorithm.AMA      -> app.aaps.database.entities.APSResult.Algorithm.AMA
        APSResult.Algorithm.SMB      -> app.aaps.database.entities.APSResult.Algorithm.SMB
        APSResult.Algorithm.AUTO_ISF -> app.aaps.database.entities.APSResult.Algorithm.AUTO_ISF
        else                         -> error("Unsupported")
    }