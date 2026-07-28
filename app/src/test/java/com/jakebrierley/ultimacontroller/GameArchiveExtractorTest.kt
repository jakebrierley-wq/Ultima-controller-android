package com.jakebrierley.ultimacontroller

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameArchiveExtractorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun extractsValidArchiveWithRootExecutable() {
        val executable = byteArrayOf(1, 2, 3)
        val data = byteArrayOf(4, 5)
        val destination = temporaryFolder.newFolder("valid")

        val summary = GameArchiveExtractor().extract(
            zipOf(
                "ULTIMA.EXE" to executable,
                "DATA/MAP.BIN" to data,
            ),
            destination,
        )

        assertEquals(2, summary.fileCount)
        assertEquals(5L, summary.totalBytes)
        assertArrayEquals(executable, File(destination, "ULTIMA.EXE").readBytes())
        assertArrayEquals(data, File(destination, "DATA/MAP.BIN").readBytes())
    }

    @Test
    fun rejectsPathTraversalWithoutWritingOutsideDestination() {
        val parent = temporaryFolder.newFolder("traversal")
        val destination = File(parent, "destination")
        val outside = File(parent, "outside.bin")

        assertThrows(GameImportException::class.java) {
            GameArchiveExtractor().extract(
                zipOf(
                    "ULTIMA.EXE" to byteArrayOf(1),
                    "../outside.bin" to byteArrayOf(2),
                ),
                destination,
            )
        }

        assertFalse(outside.exists())
    }

    @Test
    fun rejectsArchiveWithoutRootExecutable() {
        val destination = temporaryFolder.newFolder("missing-executable")

        assertThrows(GameImportException::class.java) {
            GameArchiveExtractor().extract(
                zipOf("GAME/ULTIMA.EXE" to byteArrayOf(1)),
                destination,
            )
        }
    }

    @Test
    fun rejectsEmptyRootExecutable() {
        val destination = temporaryFolder.newFolder("empty-executable")

        assertThrows(GameImportException::class.java) {
            GameArchiveExtractor().extract(
                zipOf("ULTIMA.EXE" to byteArrayOf()),
                destination,
            )
        }
    }

    @Test
    fun rejectsCaseInsensitiveDuplicateFileNames() {
        val destination = temporaryFolder.newFolder("duplicates")

        assertThrows(GameImportException::class.java) {
            GameArchiveExtractor().extract(
                zipOf(
                    "ULTIMA.EXE" to byteArrayOf(1),
                    "data.bin" to byteArrayOf(2),
                    "DATA.BIN" to byteArrayOf(3),
                ),
                destination,
            )
        }
    }

    @Test
    fun enforcesPerFileSizeLimitWhileStreaming() {
        val destination = temporaryFolder.newFolder("file-limit")
        val extractor = GameArchiveExtractor(
            ExtractionLimits(
                maxFileCount = 2,
                maxFileBytes = 3,
                maxTotalBytes = 10,
                maxPathLength = 100,
            ),
        )

        assertThrows(GameImportException::class.java) {
            extractor.extract(
                zipOf("ULTIMA.EXE" to byteArrayOf(1, 2, 3, 4)),
                destination,
            )
        }
    }

    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            files.forEach { (name, contents) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun GameArchiveExtractor.extract(
        archive: ByteArray,
        destination: File,
    ): ExtractionSummary = extract(ByteArrayInputStream(archive), destination)
}
