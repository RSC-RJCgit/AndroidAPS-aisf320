package app.aaps.implementation.maintenance

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Email preparation must work without an external or cloud document provider. */
internal fun createLogZip(cacheDir: File, name: String, logs: List<File>): File {
    val directory = File(cacheDir, "log-export")
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create log export cache")
    val output = File(directory, name)
    try {
        writeLogZip(output.outputStream(), logs)
    } catch (error: Exception) {
        output.delete()
        throw error
    }
    return output
}

internal fun writeLogZip(output: OutputStream, logs: List<File>) {
    ZipOutputStream(BufferedOutputStream(output)).use { zip ->
        logs.forEach { file ->
            zip.putNextEntry(ZipEntry(file.name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}
