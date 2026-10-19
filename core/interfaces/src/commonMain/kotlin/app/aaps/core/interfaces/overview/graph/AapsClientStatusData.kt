package app.aaps.core.interfaces.overview.graph

data class AapsClientStatusData(
    val pump: AapsClientStatusItem? = null,
    val openAps: AapsClientStatusItem? = null,
    /** AutoISF 3.2.1: the master's algorithm, with the script debug lines of its last loop in the dialog. */
    val algorithm: AapsClientStatusItem? = null,
    val uploader: AapsClientStatusItem? = null
)

data class AapsClientStatusItem(
    val label: String,
    val value: String,
    val level: AapsClientLevel,
    val dialogTitle: String,
    val dialogText: String
)

enum class AapsClientLevel {
    INFO,
    WARN,
    URGENT
}