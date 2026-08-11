package app.aaps.core.interfaces.aps

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@OptIn(InternalSerializationApi::class)
@Serializable
data class RT(
    var algorithm: APSResult.Algorithm = APSResult.Algorithm.UNKNOWN,
    var runningDynamicIsf: Boolean? = false,
    var runningAutoIsf: Boolean? = false,
    @Serializable(with = TimestampToIsoSerializer::class)
    var timestamp: Long? = null,
    val temp: String = "absolute",
    var bg: Double? = null,
    var tick: String? = null,
    var eventualBG: Double? = null,
    var targetBG: Double? = null,
    var snoozeBG: Double? = null, // AMA only
    var insulinReq: Double? = null,
    var carbsReq: Int? = null,
    var carbsReqWithin: Int? = null,
    var units: Double? = null, // micro bolus
    @Serializable(with = TimestampToIsoSerializer::class)
    var deliverAt: Long? = null, // The time at which the micro bolus should be delivered
    var sensitivityRatio: Double? = null, // autosens ratio (fraction of normal basal)
    @Serializable(with = StringBuilderSerializer::class)
    var reason: StringBuilder = StringBuilder(),
    var duration: Int? = null,
    var rate: Double? = null,
    var predBGs: Predictions? = null,
    var COB: Double? = null,
    var IOB: Double? = null,
    var variable_sens: Double? = null,
    var isfMgdlForCarbs: Double? = null, // used to pass to AAPS client
    var autoIsfAcce: Double? = null,
    var autoIsfBg: Double? = null,
    var autoIsfPp: Double? = null,
    var autoIsfDura: Double? = null,
    var autoIsfFinal: Double? = null,
    // UAM Carb Impact (uci) -- deviation-derived carbs-equivalent, grams per 5min (converted from uci's
    // native mg/dL/5min BG-impact via csf, at the point of computation -- see DetermineBasalAutoISF.kt).
    // Unlike the autoIsfAcce/Bg/Pp/Dura/Final fields above (populated FROM autoIsfValues in
    // OpenAPSAutoISFPlugin.kt, for outbound NS sync), this one flows the OTHER way: set directly in
    // DetermineBasalAutoISF.kt (where uci is computed) and read back into autoIsfValues.uamCarbImpact
    // afterward, since uci has no
    // independent computation elsewhere.
    var autoIsfUamCarbImpact: Double? = null,
    // UKF-smoothed Raw BG, mg/dL. Same direction as autoIsfAcce/Bg/Pp/Dura/Final above (computed in
    // OpenAPSAutoISFPlugin.kt, copied INTO rt here for outbound NS sync, then autoIsfValues.ukfRawBgl
    // gets persisted locally from that same computed value) -- unlike autoIsfUamCarbImpact, which flows
    // the other way since its source computation lives in DetermineBasalAutoISF.kt instead.
    var autoIsfUkfRawBgl: Double? = null,
    // Authoritative AutoISF preference snapshot from the APS-running phone. AAPSClient stores and
    // exports this mirror instead of presenting its unrelated local/default preferences as pump data.
    var autoIsfSettingsSnapshot: String? = null,


    var consoleLog: MutableList<String>? = null,
    var consoleError: MutableList<String>? = null
) {

    fun serialize() = Json.encodeToString(serializer(), this)

    object StringBuilderSerializer : KSerializer<StringBuilder> {

        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StringBuilder", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: StringBuilder) {
            encoder.encodeString(value.toString())
        }

        override fun deserialize(decoder: Decoder): StringBuilder {
            return StringBuilder().append(decoder.decodeString())
        }
    }

    object TimestampToIsoSerializer : KSerializer<Long> {

        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LongToIso", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: Long) {
            encoder.encodeString(toISOString(value))
        }

        override fun deserialize(decoder: Decoder): Long {
            return fromISODateString(decoder.decodeString())
        }

        fun fromISODateString(isoDateString: String): Long {
            val parser = ISODateTimeFormat.dateTimeParser()
            val dateTime = DateTime.parse(isoDateString, parser)
            return dateTime.toDate().time
        }

        fun toISOString(date: Long): String {
            @Suppress("SpellCheckingInspection", "LocalVariableName")
            val FORMAT_DATE_ISO_OUT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
            val f: DateFormat = SimpleDateFormat(FORMAT_DATE_ISO_OUT, Locale.getDefault())
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f.format(date)
        }
    }

    companion object {

        private val serializer = Json { ignoreUnknownKeys = true }
        fun deserialize(jsonString: String) = serializer.decodeFromString(serializer(), jsonString)
    }
}
