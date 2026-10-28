package app.aaps.core.interfaces.maintenance

typealias PrefMetadataMap = Map<PrefsMetadataKey, PrefMetadata>

data class Prefs(
    val values: Map<String, String>,
    var metadata: PrefMetadataMap,
    /** True only when an intact encrypted file rejected the supplied password. */
    val passwordRejected: Boolean = false
)
