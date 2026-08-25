package app.aaps.plugins.aps.openAPSAutoISF

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.TimeZone

/**
 * JSON-process adapter for replaying captured AutoISF determine-basal boundaries.
 *
 * This deliberately lives in the JVM test source set: it invokes the unchanged production
 * [DetermineBasalAutoISF] class but cannot be packaged into, or called by, the Android application.
 * It reads one request envelope on stdin and writes one response envelope on stdout.
 */
object AutoIsfReplayAdapterMain {

    private val gson = Gson()

    @JvmStatic
    fun main(args: Array<String>) {
        val requestText = generateSequence(::readLine).joinToString("\n")
        require(requestText.isNotBlank()) { "AutoISF replay request is empty" }
        val envelope = gson.fromJson(requestText, JsonObject::class.java)
        val sourceSha256 = verifySourceManifest(envelope.objectValue("manifest"))
        val requests = envelope.arrayValue("requests")
        val results = JsonArray()
        requests.forEach { element ->
            results.add(runCatching { evaluate(element.asJsonObject) }
                .fold(
                    onSuccess = { rt -> JsonObject().apply {
                        addProperty("ok", true)
                        add("rt", gson.fromJson(rt.serialize(), JsonObject::class.java))
                    } },
                    onFailure = { error -> JsonObject().apply {
                        addProperty("ok", false)
                        addProperty("error", error.stackTraceToString())
                    } }
                ))
        }
        print(gson.toJson(JsonObject().apply {
            add("source_sha256", gson.toJsonTree(sourceSha256))
            add("results", results)
        }))
    }

    private fun evaluate(record: JsonObject) = record.objectValue("inputs").let { inputs ->
        val profile = gson.fromJson(inputs.get("profile"), OapsProfileAutoIsf::class.java)
        val snapshot = parsePreferenceSnapshot(record.stringValue("preference_snapshot"))
        requirePreferences(snapshot)
        val parameters = inputs.objectValue("parameters")
        val currentTime = parameters.longValue("currentTime")
        configureReplayProcess(currentTime, profile.now)
        val determine = DetermineBasalAutoISF(profileUtil(profile.out_units)).also {
            it.preferences = preferences(snapshot)
            it.config = config(record.objectValue("build").stringValue("version"))
            val state = inputs.objectValue("determine_state")
            it.tddRatio = state.doubleValue("tddRatio")
            it.tdd7D = state.doubleValue("tdd7D")
        }
        determine.determine_basal(
            glucose_status = gson.fromJson(inputs.get("glucose_status"), GlucoseStatusAutoIsf::class.java),
            currenttemp = gson.fromJson(inputs.get("currenttemp"), CurrentTemp::class.java),
            iob_data_array = gson.fromJson(inputs.get("iob_data_array"), Array<IobTotal>::class.java),
            profile = profile,
            autosens_data = gson.fromJson(inputs.get("autosens_data"), AutosensResult::class.java),
            meal_data = gson.fromJson(inputs.get("meal_data"), MealData::class.java),
            microBolusAllowed = parameters.booleanValue("microBolusAllowed"),
            currentTime = currentTime,
            flatBGsDetected = parameters.booleanValue("flatBGsDetected"),
            autoIsfMode = parameters.booleanValue("autoIsfMode"),
            loop_wanted_smb = parameters.stringValue("loop_wanted_smb"),
            profile_percentage = parameters.intValue("profile_percentage"),
            smb_ratio = parameters.doubleValue("smb_ratio"),
            smb_max_range_extension = parameters.doubleValue("smb_max_range_extension"),
            iob_threshold_percent = parameters.intValue("iob_threshold_percent"),
            activity_consoleLog = parameters.stringValue("activity_consoleLog"),
            auto_isf_consoleError = parameters.stringList("auto_isf_consoleError"),
            auto_isf_consoleLog = parameters.stringList("auto_isf_consoleLog"),
            bg_acce = parameters.doubleValue("bg_acce"),
            steps180M = parameters.intValue("steps180M"),
            steps15M = parameters.intValue("steps15M"),
            steps5M = parameters.intValue("steps5M"),
            smbInt5Sec = parameters.doubleValue("smbInt5Sec"),
            smbBoostRecent = parameters.booleanValue("smbBoostRecent"),
            rawDelta5Mgdl = parameters.doubleValue("rawDelta5Mgdl"),
            immediateRawDelta5Mgdl = parameters.doubleValue("immediateRawDelta5Mgdl"),
            rawDelta1Mgdl = parameters.doubleValue("rawDelta1Mgdl"),
            aapsDelta1Mgdl = parameters.doubleValue("aapsDelta1Mgdl"),
            rawDelta15Mgdl = parameters.doubleValue("rawDelta15Mgdl"),
            recentLowActive = parameters.booleanValue("recentLowActive"),
            smbSum10Min = parameters.doubleValue("smbSum10Min"),
            smbSum30Min = parameters.doubleValue("smbSum30Min"),
            sub75HeavyDeliveryCooldown = parameters.booleanValue("sub75HeavyDeliveryCooldown"),
            basalUpOffsetZeroActive = parameters.booleanValue("basalUpOffsetZeroActive"),
            fastRiseSlopeCompensationRatio = parameters.doubleValue("fastRiseSlopeCompensationRatio"),
            lastBolusMinutes = parameters.intValue("lastBolusMinutes"),
            lastCarbMinutes = parameters.intValue("lastCarbMinutes"),
            iobChange5Min = parameters.doubleValue("iobChange5Min"),
            recentLowBG = parameters.doubleValue("recentLowBG"),
            bmildBasicCriteriaMet = parameters.booleanValue("bmildBasicCriteriaMet"),
            acceIsfValue = parameters.doubleValue("acceIsfValue")
        )
    }

    private fun configureReplayProcess(currentTime: Long, capturedLocalHour: Int?) {
        Locale.setDefault(Locale.US)
        val localHour = capturedLocalHour
            ?: error("Replay profile did not capture the controller's local hour")
        require(localHour in 0..23) { "Invalid captured local hour: $localHour" }
        val utcHour = Instant.ofEpochMilli(currentTime).atZone(ZoneOffset.UTC).hour
        val offsetHours = (-12..14).firstOrNull { Math.floorMod(utcHour + it, 24) == localHour }
            ?: error("Cannot reconstruct replay timezone for local hour $localHour")
        // Fixed 2026-08-26: the original fictional ID "AutoIsfReplay" is accepted by
        // java.util.SimpleTimeZone (any string is a legal display ID there) but crashes the first
        // time ANY code calls modern java.time.LocalDateTime.now() -- which needs
        // TimeZone.getDefault().toZoneId(), and java.time refuses to resolve a non-IANA-recognized
        // ID (ZoneRulesException: Unknown time-zone ID: AutoIsfReplay). DetermineBasalAutoISF.kt
        // does call LocalDateTime.now(), so every replay record failed on this. A "GMT+HH:MM"
        // style ID is specifically recognized by both java.util.TimeZone AND java.time.ZoneId.of()
        // as a valid fixed-offset zone without needing a real tzdata entry, so it works both ways.
        val offsetId = "GMT%+03d:00".format(offsetHours)
        TimeZone.setDefault(SimpleTimeZone(offsetHours * 60 * 60 * 1000, offsetId))
    }

    private fun parsePreferenceSnapshot(snapshot: String): Map<String, String> =
        snapshot.lineSequence()
            .filter { it.isNotBlank() }
            .associate { line ->
                val separator = line.indexOf(" = ")
                require(separator > 0) { "Malformed preference snapshot line: $line" }
                line.substring(0, separator) to line.substring(separator + 3)
            }

    private val requiredPreferenceKeys = setOf(
        "activity_ratio",
        "steps_activity_detected",
        "steps_inactivity_detected",
        "autoisf_tdd_factor_fallback",
        "autoisf_tdd_factor",
        "openapsama_enable_autoISF",
        "autoisf_smb_offset_override_enabled",
        "autoisf_smb_offset_override",
        "autoisf_tod_offset_0002",
        "autoisf_tod_offset_0204",
        "autoisf_tod_offset_0406",
        "autoisf_tod_offset_0609",
        "autoisf_tod_offset_0912",
        "autoisf_tod_offset_1218",
        "autoisf_tod_offset_1822",
        "autoisf_tod_offset_2200",
        "autoisf_mild_offset_zero_active",
        "autoisf_smb_stack_start_ts"
    )

    private fun requirePreferences(snapshot: Map<String, String>) {
        val missing = requiredPreferenceKeys - snapshot.keys
        require(missing.isEmpty()) { "Replay preference snapshot is missing: ${missing.sorted().joinToString()}" }
    }

    private fun preferences(snapshot: Map<String, String>): Preferences {
        val handler = InvocationHandler { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "ReplayPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                "get" -> {
                    val key = arguments?.firstOrNull() as? NonPreferenceKey
                        ?: error("Unsupported replay preference call: $method")
                    val raw = snapshot[key.key]
                        ?: error("Replay preference '${key.key}' was not captured")
                    when (method.returnType) {
                        java.lang.Boolean.TYPE -> raw.toBooleanStrict()
                        java.lang.Double.TYPE -> raw.toDouble()
                        java.lang.Integer.TYPE -> raw.toInt()
                        java.lang.Long.TYPE -> raw.toLong()
                        String::class.java -> raw
                        else -> error("Unsupported replay preference type for '${key.key}': ${method.returnType}")
                    }
                }
                else -> error("Unexpected Preferences method during replay: $method")
            }
        }
        return Proxy.newProxyInstance(
            Preferences::class.java.classLoader,
            arrayOf(Preferences::class.java),
            handler
        ) as Preferences
    }

    private fun profileUtil(outUnits: String): ProfileUtil {
        val units = if (outUnits.contains("mmol", ignoreCase = true)) GlucoseUnit.MMOL else GlucoseUnit.MGDL
        val handler = InvocationHandler { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "ReplayProfileUtil($units)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                "getUnits" -> units
                "fromMgdlToUnits" -> {
                    val value = (arguments?.get(0) as Number).toDouble()
                    val target = arguments?.getOrNull(1) as? GlucoseUnit ?: units
                    if (target == GlucoseUnit.MMOL) value / GlucoseUnit.MMOLL_TO_MGDL else value
                }
                else -> error("Unexpected ProfileUtil method during replay: $method")
            }
        }
        return Proxy.newProxyInstance(
            ProfileUtil::class.java.classLoader,
            arrayOf(ProfileUtil::class.java),
            handler
        ) as ProfileUtil
    }

    private fun config(version: String): Config {
        val handler = InvocationHandler { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "ReplayConfig($version)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                "getVERSION_NAME" -> version
                else -> error("Unexpected Config method during replay: $method")
            }
        }
        return Proxy.newProxyInstance(
            Config::class.java.classLoader,
            arrayOf(Config::class.java),
            handler
        ) as Config
    }

    private fun verifySourceManifest(manifest: JsonObject): Map<String, String> {
        require(manifest.stringValue("controller") == "aaps-autoisf-ukf3426") {
            "Unsupported AutoISF replay controller"
        }
        val root = Path.of(manifest.stringValue("source_root")).toAbsolutePath().normalize()
        val result = linkedMapOf<String, String>()
        manifest.arrayValue("files").forEach { element ->
            val item = element.asJsonObject
            val relative = item.stringValue("path")
            val source = root.resolve(relative).normalize()
            require(source.startsWith(root) && Files.isRegularFile(source)) { "Invalid replay source: $relative" }
            val actual = sha256(source)
            require(actual == item.stringValue("sha256")) { "Replay source hash mismatch: $relative" }
            result[relative] = actual
        }
        require(result.size == 2) { "AutoISF replay requires both pinned controller sources" }
        return result
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun JsonObject.objectValue(name: String): JsonObject =
        get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?: error("Replay record is missing object '$name'")

    private fun JsonObject.arrayValue(name: String): JsonArray =
        get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            ?: error("Replay record is missing array '$name'")

    private fun JsonObject.stringValue(name: String): String =
        get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asString
            ?: error("Replay record is missing string '$name'")

    private fun JsonObject.booleanValue(name: String): Boolean =
        get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asBoolean
            ?: error("Replay record is missing boolean '$name'")

    private fun JsonObject.doubleValue(name: String): Double =
        get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asDouble
            ?: error("Replay record is missing number '$name'")

    private fun JsonObject.longValue(name: String): Long =
        get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asLong
            ?: error("Replay record is missing long '$name'")

    private fun JsonObject.intValue(name: String): Int =
        get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asInt
            ?: error("Replay record is missing integer '$name'")

    private fun JsonObject.stringList(name: String): MutableList<String> =
        arrayValue(name).map { it.asString }.toMutableList()
}
