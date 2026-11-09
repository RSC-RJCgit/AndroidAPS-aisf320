package app.aaps.plugins.sync.smsCommunicator

import android.Manifest
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.smsCommunicator.Sms
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.notes.NoteSmsTexts
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ClientNoteSmsReceiverTest {

    private val config: Config = mock()
    private val preferences: Preferences = mock()
    private val dateUtil: DateUtil = mock()
    private val receiver = ClientNoteSmsReceiver(config, preferences, dateUtil)
    private val sender = "+61400000000"
    private val now = 1_750_000_000_000L

    @Test
    fun `client saves the full text of a tagged multipart SMS from the loop phone`() = runTest {
        whenever(config.AAPSCLIENT).thenReturn(true)
        whenever(preferences.get(StringKey.ClientNoteSmsSender)).thenReturn("+61 (400) 000-000")
        whenever(preferences.get(StringNonKey.CarePortalNoteSmsTexts)).thenReturn("{}")
        whenever(dateUtil.now()).thenReturn(now)
        val text = "Shower12: g=7.8"
        val sms = NoteSmsTexts.tagged(text, now)
        val message = joinSmsParts(listOf(Sms(sender, sms.take(10)), Sms(sender, sms.drop(10))))

        assertThat(receiver.handleIfClient(message)).isTrue()
        val stored = argumentCaptor<String>()
        verify(preferences).put(eq(StringNonKey.CarePortalNoteSmsTexts), stored.capture())
        assertThat(NoteSmsTexts.parse(stored.firstValue)).containsExactly(now, text)
        assertThat(receiver.requiredPermissions().single().permissions).containsExactly(Manifest.permission.RECEIVE_SMS)
    }

    @Test
    fun `client consumes commands and other senders without saving any notes`() = runTest {
        whenever(config.AAPSCLIENT).thenReturn(true)
        whenever(preferences.get(StringKey.ClientNoteSmsSender)).thenReturn(sender)
        val tagged = NoteSmsTexts.tagged("Loop note", now)
        val otherSender = "+61400000001"

        assertThat(receiver.handleIfClient(Sms(sender, "BOLUS 1"))).isTrue()
        assertThat(receiver.handleIfClient(Sms(otherSender, tagged))).isTrue()
        assertThat(joinSmsParts(listOf(Sms(otherSender, "Text"), Sms(sender, tagged)))).isNull()
        verify(preferences, never()).put(eq(StringNonKey.CarePortalNoteSmsTexts), any<String>())
    }

    @Test
    fun `full AAPS keeps its existing SMS route`() = runTest {
        whenever(config.AAPSCLIENT).thenReturn(false)

        assertThat(receiver.handleIfClient(Sms(sender, "BG"))).isFalse()
        assertThat(receiver.requiredPermissions()).isEmpty()
    }
}
