package app.aaps.implementation.maintenance

/**
 * Folder and file name shared by the AutoISF exports.
 * Empty patient name means no phone is added, so old files keep their plain names.
 */
internal fun exportScopeName(patientName: String, phoneModel: String): String {
    val base = patientName.trim()
    if (base.isEmpty()) return ""
    val model = phoneModel.replace(Regex("[^A-Za-z0-9]"), "")
    return if (model.isEmpty()) base else "${base}_$model"
}

/** `AutoISF_KMPvirtual_SMF711B_20260928_121349.csv`, or without the scope when it is empty. */
internal fun exportNamedFile(prefix: String, scope: String, stamp: String, extension: String): String {
    val body = if (scope.isEmpty()) "${prefix}_$stamp" else "${prefix}_${scope}_$stamp"
    return "$body.$extension"
}
