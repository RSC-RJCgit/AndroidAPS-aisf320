package app.aaps.implementation.maintenance

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Limit attachment sharing to apps that also handle email addresses. */
internal fun logEmailIntent(packageManager: PackageManager, attachment: Uri, recipient: String, subject: String, body: String): Intent {
    val emailPackages = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), PackageManager.MATCH_DEFAULT_ONLY
    ).map { it.activityInfo.packageName }.distinct()
    val targets = emailPackages.mapNotNull { packageName ->
        Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            setPackage(packageName)
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, attachment)
            clipData = ClipData.newRawUri("AAPS logs", attachment)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.takeIf { packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null }
    }
    if (targets.isEmpty()) throw ActivityNotFoundException("No email app can attach the log ZIP")
    return if (targets.size == 1) targets.single().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    else Intent.createChooser(targets.first(), null).apply {
        putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.drop(1).toTypedArray())
        clipData = ClipData.newRawUri("AAPS logs", attachment)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
