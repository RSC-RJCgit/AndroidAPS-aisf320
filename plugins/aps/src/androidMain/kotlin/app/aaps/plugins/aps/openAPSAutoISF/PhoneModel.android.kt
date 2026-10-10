package app.aaps.plugins.aps.openAPSAutoISF

import android.os.Build

internal actual fun thisPhoneModel(): String = Build.MODEL.orEmpty().trim()
