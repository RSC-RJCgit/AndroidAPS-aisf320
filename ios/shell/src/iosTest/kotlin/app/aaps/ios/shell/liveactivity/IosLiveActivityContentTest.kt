package app.aaps.ios.shell.liveactivity

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IosLiveActivityContentTest {

    private val glucose = LiveActivityContent("5.8", "→", "+0.1", "n/a", "n/a", 1_792_900_000_000L, 0)

    @Test
    fun `glucose is sent before either calculation starts`() = runTest {
        val sent = mutableListOf<LiveActivityContent>()
        sendLiveActivityContent(
            glucose, "n/a",
            calculateIob = {
                assertEquals(listOf(glucose), sent)
                "0.70 U"
            },
            calculateCob = {
                assertEquals(listOf(glucose), sent)
                "12 g"
            },
            onDetailFailure = { _, error -> throw AssertionError("Unexpected calculation failure: $error") },
            send = { sent.add(it) }
        )
        assertEquals(listOf(glucose, glucose.copy(iob = "0.70 U", cob = "12 g")), sent)
    }

    @Test
    fun `an IOB failure does not hide glucose or stop COB`() = runTest {
        val sent = mutableListOf<LiveActivityContent>()
        val failures = mutableListOf<String>()
        sendLiveActivityContent(
            glucose, "n/a",
            calculateIob = { error("Profile unavailable") },
            calculateCob = { "12 g" },
            onDetailFailure = { name, _ -> failures.add(name) },
            send = { sent.add(it) }
        )
        assertEquals(listOf("IOB"), failures)
        assertEquals(listOf(glucose, glucose.copy(cob = "12 g")), sent)
    }

    @Test
    fun `a COB failure does not discard IOB or glucose`() = runTest {
        val sent = mutableListOf<LiveActivityContent>()
        val failures = mutableListOf<String>()
        sendLiveActivityContent(
            glucose, "n/a",
            calculateIob = { "0.70 U" },
            calculateCob = { error("COB unavailable") },
            onDetailFailure = { name, _ -> failures.add(name) },
            send = { sent.add(it) }
        )
        assertEquals(listOf("COB"), failures)
        assertEquals(listOf(glucose, glucose.copy(iob = "0.70 U")), sent)
    }

    @Test
    fun `slow calculations time out after glucose has reached the bridge`() = runTest {
        val sent = mutableListOf<LiveActivityContent>()
        val failures = mutableListOf<String>()
        sendLiveActivityContent(
            glucose, "n/a",
            calculateIob = {
                assertEquals(listOf(glucose), sent)
                delay(10_000)
                "0.70 U"
            },
            calculateCob = {
                delay(10_000)
                "12 g"
            },
            onDetailFailure = { name, error ->
                assertNull(error)
                failures.add(name)
            },
            send = { sent.add(it) }
        )
        assertEquals(listOf("IOB", "COB"), failures)
        assertEquals(listOf(glucose, glucose), sent)
    }

    @Test
    fun `cancelling the updater stops calculations without a fallback update`() = runTest {
        val sent = mutableListOf<LiveActivityContent>()
        assertFailsWith<CancellationException> {
            sendLiveActivityContent(
                glucose, "n/a",
                calculateIob = { throw CancellationException("Updater stopped") },
                calculateCob = { error("COB must not run after cancellation") },
                onDetailFailure = { _, _ -> error("Cancellation must not be treated as a calculation failure") },
                send = { sent.add(it) }
            )
        }
        assertEquals(listOf(glucose), sent)
    }
}
