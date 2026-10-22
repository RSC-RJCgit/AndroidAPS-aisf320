package app.aaps.implementation.logging

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That a full log is moved aside rather than thrown away.
 *
 * It used to be truncated: `file.writeText("")` the moment it passed the size bound, which discarded
 * the **whole** history at once. That is the wrong half to lose. A user is asked for logs after
 * something has gone wrong, and the run that went wrong is the run that filled the file - so the
 * evidence was reliably deleted by the act of producing it.
 *
 * A tiny `maxBytes` here so the bound is crossed in a few lines instead of five megabytes.
 */
class AAPSLoggerDesktopRotationTest {

    private val directory: File = Files.createTempDirectory("aaps-log-test").toFile()
    private val logFile = File(directory, "aaps.log")
    private val rotated = File(directory, "aaps.log.1")

    private fun logger(maxBytes: Long = 200L) = AAPSLoggerDesktop(file = logFile, maxBytes = maxBytes)

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `a small log is left alone`() {
        logger().error("one line")

        assertTrue(logFile.exists())
        assertTrue(!rotated.exists(), "nothing should have been rotated yet")
    }

    @Test
    fun `crossing the bound moves the old log aside instead of emptying it`() {
        val sut = logger()
        repeat(20) { sut.error("line $it padded out to make the file grow reasonably quickly") }

        assertTrue(rotated.exists(), "the previous log should have been kept as aaps.log.1")
        assertTrue(rotated.length() > 0, "the rotated file must hold the old lines, not be empty")
    }

    /**
     * The whole point: what was written before a rotation is still readable after it.
     *
     * Exactly one rotation, which is what the promise covers. Keeping a single previous file means
     * enough further logging does eventually push old lines out - that is inherent to the design and
     * the same on iOS, not a fault. What must not happen is the old lines vanishing the *instant*
     * the bound is crossed, which is what truncating did.
     */
    @Test
    fun `the old lines survive a rotation`() {
        val sut = logger()
        sut.error("the-incident-line padded out to make the file grow reasonably quickly")
        sut.error("second line padded out to make the file grow reasonably quickly")

        // The append that crosses the bound is the one that rotates, so this third line lands in a
        // fresh file and the two above are moved aside together.
        sut.error("third line padded out to make the file grow reasonably quickly")

        assertTrue(rotated.exists(), "one rotation should have happened by now")
        assertTrue(rotated.readText().contains("the-incident-line"), "the first line was lost")
        assertTrue(!logFile.readText().contains("the-incident-line"), "the live log should have started fresh")
    }

    /** Exactly one previous file, so the worst case on disk stays about twice the bound. */
    @Test
    fun `only one previous log is kept`() {
        val sut = logger()
        repeat(120) { sut.error("line $it padded out to make the file grow reasonably quickly") }

        val logs = directory.listFiles()?.map { it.name }?.sorted().orEmpty()
        assertEquals(listOf("aaps.log", "aaps.log.1"), logs, "rotation should not accumulate files")
    }

    /**
     * Many threads crossing the bound together rotate once, and no line is lost (2026-10-22).
     *
     * Without the lock, two threads both rotated: the second deleted the full `aaps.log.1` and moved the new, nearly
     * empty log onto it. A Windows client showed exactly that after a full sync: an empty `aaps.log.1` and a log of
     * three seconds. The bound is set so the lines written are about 1.3 times it: exactly one rotation is right.
     */
    @Test
    fun `threads crossing the bound together lose no lines`() {
        val threads = 8
        val linesEach = 40
        val sut = logger(maxBytes = 20_000L)
        val start = CountDownLatch(1)
        val workers = (0 until threads).map { t ->
            thread {
                start.await()
                repeat(linesEach) { sut.error("thread-$t-line-$it padded out to make the file grow reasonably quickly") }
            }
        }
        start.countDown()
        workers.forEach { it.join() }

        assertTrue(rotated.exists(), "one rotation should have happened")
        assertTrue(rotated.length() > 20_000L, "aaps.log.1 must hold the full old log, not a nearly empty one")
        val written = logFile.readLines() + rotated.readLines()
        for (t in 0 until threads) for (i in 0 until linesEach)
            assertTrue(written.any { it.contains("thread-$t-line-$i ") }, "thread-$t-line-$i was lost")
    }

    /** Writing continues into a fresh file rather than appending to the rotated one. */
    @Test
    fun `logging carries on after a rotation`() {
        val sut = logger()
        repeat(20) { sut.error("filler $it padded out to make the file grow reasonably quickly") }

        sut.error("after-the-rotation")

        assertTrue(logFile.readText().contains("after-the-rotation"))
    }
}
