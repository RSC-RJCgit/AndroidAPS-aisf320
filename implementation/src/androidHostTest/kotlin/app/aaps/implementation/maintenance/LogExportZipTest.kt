package app.aaps.implementation.maintenance

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LogExportZipTest {

    @TempDir lateinit var directory: File

    @Test
    fun localZipContainsTheLogsWithoutAnyDocumentProvider() {
        val log = File(directory, "aaps.log").apply { writeText("test log") }
        val cache = File(directory, "cache")

        val result = createLogZip(cache, "logs.zip", listOf(log))

        assertThat(result.parentFile).isEqualTo(File(cache, "log-export"))
        ZipFile(result).use { zip ->
            assertThat(zip.size()).isEqualTo(1)
            assertThat(zip.getInputStream(zip.getEntry("aaps.log")).bufferedReader().use { it.readText() }).isEqualTo("test log")
        }
    }

    @Test
    fun readFailureRemovesTheIncompleteZip() {
        val cache = File(directory, "cache")
        assertThrows(Exception::class.java) { createLogZip(cache, "logs.zip", listOf(File(directory, "missing.log"))) }
        assertThat(File(cache, "log-export/logs.zip").exists()).isFalse()
    }
}
