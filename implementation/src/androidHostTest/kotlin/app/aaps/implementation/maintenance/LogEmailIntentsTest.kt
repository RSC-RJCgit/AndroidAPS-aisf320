package app.aaps.implementation.maintenance

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogEmailIntentsTest {

    private val packageManager: PackageManager = mock()
    private val attachment = Uri.parse("content://test.fileprovider/cache/log-export/log.zip")

    private fun emailApp(name: String) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply { packageName = name; this.name = "$name.Compose" }
    }

    @Test
    fun outlookAloneGetsTheZipWithReadAccess() {
        val outlook = emailApp("com.microsoft.office.outlook")
        whenever(packageManager.queryIntentActivities(any<Intent>(), eq(PackageManager.MATCH_DEFAULT_ONLY))).thenAnswer {
            val query = it.arguments[0] as Intent
            assertThat(query.action).isEqualTo(Intent.ACTION_SENDTO)
            assertThat(query.data?.scheme).isEqualTo("mailto")
            listOf(outlook)
        }
        whenever(packageManager.resolveActivity(any<Intent>(), eq(PackageManager.MATCH_DEFAULT_ONLY))).thenReturn(outlook)

        val intent = logEmailIntent(packageManager, attachment, "test@example.com", "Logs", "Details")

        assertThat(intent.`package`).isEqualTo("com.microsoft.office.outlook")
        assertThat(intent.type).isEqualTo("application/zip")
        assertThat(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)).isEqualTo(attachment)
        assertThat(intent.clipData?.getItemAt(0)?.uri).isEqualTo(attachment)
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(intent.getStringArrayExtra(Intent.EXTRA_EMAIL)).asList().containsExactly("test@example.com")
    }

    @Test
    fun chooserContainsOnlyExplicitEmailPackages() {
        val apps = listOf(emailApp("mail.one"), emailApp("mail.two"))
        whenever(packageManager.queryIntentActivities(any<Intent>(), eq(PackageManager.MATCH_DEFAULT_ONLY))).thenReturn(apps)
        whenever(packageManager.resolveActivity(any<Intent>(), eq(PackageManager.MATCH_DEFAULT_ONLY))).thenReturn(apps.first())

        val chooser = logEmailIntent(packageManager, attachment, "test@example.com", "Logs", "Details")

        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        val first = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        val rest = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS).orEmpty().map { it as Intent }
        assertThat(listOfNotNull(first).plus(rest).map { it.`package` }).containsExactly("mail.one", "mail.two")
    }

    @Test
    fun noEmailAppDoesNotFallBackToSms() {
        whenever(packageManager.queryIntentActivities(any<Intent>(), eq(PackageManager.MATCH_DEFAULT_ONLY))).thenReturn(emptyList())
        assertThrows(ActivityNotFoundException::class.java) {
            logEmailIntent(packageManager, attachment, "test@example.com", "Logs", "Details")
        }
    }
}
