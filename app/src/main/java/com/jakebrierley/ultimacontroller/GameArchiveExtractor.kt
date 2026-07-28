package com.jakebrierley.ultimacontroller

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

internal data class ExtractionLimits(
    val maxFileCount: Int = 1_024,
    val maxFileBytes: Long = 64L * 1024L * 1024L,
    val maxTotalBytes: Long = 256L * 1024L * 1024L,
    val maxPathLength: Int = 240,
)

internal data class ExtractionSummary(
    val fileCount: Int,
    val totalBytes: Long,
)

internal class GameImportException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

internal class GameArchiveExtractor(
    private val limits: ExtractionLimits = ExtractionLimits(),
) {
    fun extract(input: InputStream, destinationDirectory: File): ExtractionSummary {
        prepareDestination(destinationDirectory)
        val canonicalRoot = destinationDirectory.canonicalFile
        val seenFileNames = mutableSetOf<String>()
        var fileCount = 0
        var totalBytes = 0L
        var foundExecutable = false

        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val relativePath = validateEntryName(entry.name)
                val outputFile = safeDestination(canonicalRoot, relativePath)

                if (entry.isDirectory) {
                    createDirectory(outputFile)
                    zip.closeEntry()
                    continue
                }

                val caseInsensitiveName = relativePath.lowercase(Locale.ROOT)
                if (!seenFileNames.add(caseInsensitiveName)) {
                    throw GameImportException(
                        "Archive contains duplicate file names: $relativePath",
                    )
                }

                fileCount += 1
                if (fileCount > limits.maxFileCount) {
                    throw GameImportException(
                        "Archive contains more than ${limits.maxFileCount} files",
                    )
                }

                outputFile.parentFile?.let(::createDirectory)
                var currentFileBytes = 0L
                BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break

                        currentFileBytes += read
                        totalBytes += read
                        if (currentFileBytes > limits.maxFileBytes) {
                            throw GameImportException(
                                "Archive entry exceeds the per-file size limit: $relativePath",
                            )
                        }
                        if (totalBytes > limits.maxTotalBytes) {
                            throw GameImportException(
                                "Archive expands beyond the ${limits.maxTotalBytes} byte limit",
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                }

                if (relativePath.equals(REQUIRED_EXECUTABLE, ignoreCase = true) &&
                    currentFileBytes > 0L
                ) {
                    foundExecutable = true
                }
                zip.closeEntry()
            }
        }

        if (fileCount == 0) {
            throw GameImportException("The selected ZIP is empty")
        }
        if (!foundExecutable) {
            throw GameImportException(
                "$REQUIRED_EXECUTABLE must be at the root of the selected ZIP",
            )
        }

        return ExtractionSummary(fileCount = fileCount, totalBytes = totalBytes)
    }

    private fun prepareDestination(destinationDirectory: File) {
        if (destinationDirectory.exists()) {
            if (!destinationDirectory.isDirectory) {
                throw GameImportException("Import destination is not a directory")
            }
            if (!destinationDirectory.listFiles().isNullOrEmpty()) {
                throw GameImportException("Import destination is not empty")
            }
            return
        }
        createDirectory(destinationDirectory)
    }

    private fun validateEntryName(entryName: String): String {
        val normalized = entryName.replace('\\', '/').trimEnd('/')
        if (normalized.isBlank()) {
            throw GameImportException("Archive contains an empty path")
        }
        if (normalized.length > limits.maxPathLength) {
            throw GameImportException("Archive contains a path that is too long")
        }
        if (normalized.startsWith('/') ||
            (normalized.length >= 2 && normalized[1] == ':')
        ) {
            throw GameImportException("Archive contains an absolute path")
        }

        val segments = normalized.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw GameImportException("Archive contains an unsafe path: $entryName")
        }
        return segments.joinToString("/")
    }

    private fun safeDestination(canonicalRoot: File, relativePath: String): File {
        val destination = File(canonicalRoot, relativePath).canonicalFile
        val requiredPrefix = canonicalRoot.path + File.separator
        if (!destination.path.startsWith(requiredPrefix)) {
            throw GameImportException("Archive entry escapes the import directory")
        }
        return destination
    }

    private fun createDirectory(directory: File) {
        if (directory.isDirectory) return
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw GameImportException("Could not create an import directory")
        }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 32 * 1024
        const val REQUIRED_EXECUTABLE = "ULTIMA.EXE"
    }
}
