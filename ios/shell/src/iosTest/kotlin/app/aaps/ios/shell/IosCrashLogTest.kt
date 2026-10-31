package app.aaps.ios.shell

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.stringWithContentsOfFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The crash file (2026-11-01): what is written, and that it holds only the last crash.
 *
 * The hook itself cannot be tested without crashing the test binary, so this checks the two parts it calls.
 */
@OptIn(ExperimentalForeignApi::class)
class IosCrashLogTest {

    private val path = NSTemporaryDirectory() + "last-crash-" + NSUUID().UUIDString() + ".txt"

    @AfterTest
    fun removeFile() = run { NSFileManager.defaultManager.removeItemAtPath(path, null); Unit }

    private fun read(): String? = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

    @Test
    fun `the report names the error and when it happened`() {
        val report = IosCrashLog.crashReport(IllegalStateException("No pump selected"), "2026-10-31T12:43:31Z")

        assertTrue(report.startsWith("2026-10-31T12:43:31Z\nCRASH: uncaught IllegalStateException: No pump selected\n"))
        assertTrue(report.contains("IllegalStateException"))
    }

    @Test
    fun `the file holds the report`() {
        IosCrashLog.writeCrashFile(path, "first crash\n")

        assertEquals("first crash\n", read())
    }

    /** One file, the newest crash: an older one must not be read as the current one. */
    @Test
    fun `a later crash replaces the earlier one`() {
        IosCrashLog.writeCrashFile(path, "first crash\n")
        IosCrashLog.writeCrashFile(path, "second crash\n")

        val text = read().orEmpty()
        assertEquals("second crash\n", text)
        assertFalse(text.contains("first"))
    }
}
