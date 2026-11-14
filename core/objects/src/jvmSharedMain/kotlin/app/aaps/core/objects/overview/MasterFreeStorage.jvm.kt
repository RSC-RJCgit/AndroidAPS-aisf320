package app.aaps.core.objects.overview

import java.io.File
import kotlin.math.roundToInt

/** Android's data partition (/data, as Android Settings counts it); the home folder off Android. */
actual fun localFreeStorageGb(): Int? = runCatching {
    val data = File("/data")
    val dir = if (data.isDirectory) data else File(System.getProperty("user.home"))
    (dir.usableSpace / 1_000_000_000.0).roundToInt()
}.getOrNull()
