package com.jakebrierley.ultimacontroller

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class GameImportSummary(
    val fileCount: Int,
    val totalBytes: Long,
    val archiveSha256: String,
)

object GameStorage {
    private val operationInProgress = AtomicBoolean(false)

    fun isOperationInProgress(): Boolean = operationInProgress.get()

    fun currentSummary(context: Context): GameImportSummary? {
        if (!operationInProgress.get()) {
            recoverInterruptedReplace(context.filesDir)
        }
        val container = File(context.filesDir, IMPORT_CONTAINER_NAME)
        val gameDirectory = File(container, GAME_FILES_DIRECTORY_NAME)
        val metadataFile = File(container, METADATA_FILE_NAME)
        if (!gameDirectory.isDirectory || !metadataFile.isFile) return null
        if (!containsRequiredExecutable(gameDirectory)) return null

        return runCatching {
            val properties = Properties()
            FileInputStream(metadataFile).use(properties::load)
            require(
                properties.getProperty(KEY_FORMAT_VERSION) == METADATA_FORMAT_VERSION,
            )
            val fileCount = properties.getProperty(KEY_FILE_COUNT).toInt()
            val totalBytes = properties.getProperty(KEY_TOTAL_BYTES).toLong()
            val archiveSha256 = properties.getProperty(KEY_ARCHIVE_SHA256)
            require(fileCount > 0)
            require(totalBytes >= 0L)
            require(archiveSha256.matches(SHA_256_PATTERN))
            GameImportSummary(fileCount, totalBytes, archiveSha256)
        }.getOrNull()
    }

    fun importedGameDirectory(context: Context): File? =
        currentSummary(context)?.let {
            File(
                File(context.filesDir, IMPORT_CONTAINER_NAME),
                GAME_FILES_DIRECTORY_NAME,
            )
        }

    fun importArchive(context: Context, source: Uri): GameImportSummary {
        beginOperation()
        val appContext = context.applicationContext
        val operationId = UUID.randomUUID().toString()
        val archiveCopy = File(appContext.cacheDir, "$TEMP_ARCHIVE_PREFIX$operationId.zip")
        val stagingContainer =
            File(appContext.filesDir, "$STAGING_CONTAINER_PREFIX$operationId")

        return try {
            recoverInterruptedReplace(appContext.filesDir)
            cleanupStaleStaging(appContext.filesDir)
            cleanupStaleArchives(appContext.cacheDir)

            val archiveSha256 = copyArchive(appContext, source, archiveCopy)
            val gameFiles = File(stagingContainer, GAME_FILES_DIRECTORY_NAME)
            val extracted = FileInputStream(archiveCopy).use { archive ->
                GameArchiveExtractor().extract(archive, gameFiles)
            }
            val summary = GameImportSummary(
                fileCount = extracted.fileCount,
                totalBytes = extracted.totalBytes,
                archiveSha256 = archiveSha256,
            )
            writeMetadata(File(stagingContainer, METADATA_FILE_NAME), summary)
            replaceCurrentImport(appContext.filesDir, stagingContainer, operationId)
            summary
        } catch (error: GameImportException) {
            throw error
        } catch (error: Exception) {
            throw GameImportException(
                error.message ?: "Could not import the selected ZIP",
                error,
            )
        } finally {
            archiveCopy.delete()
            stagingContainer.deleteRecursively()
            operationInProgress.set(false)
        }
    }

    fun removeImport(context: Context): Boolean {
        beginOperation()
        return try {
            recoverInterruptedReplace(context.filesDir)
            val current = File(context.filesDir, IMPORT_CONTAINER_NAME)
            if (!current.exists()) {
                false
            } else if (!current.deleteRecursively()) {
                throw GameImportException("Could not remove the imported game files")
            } else {
                true
            }
        } finally {
            operationInProgress.set(false)
        }
    }

    private fun beginOperation() {
        if (!operationInProgress.compareAndSet(false, true)) {
            throw GameImportException("Another game-file operation is already running")
        }
    }

    private fun copyArchive(context: Context, source: Uri, destination: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        val sourceStream = context.contentResolver.openInputStream(source)
            ?: throw GameImportException("Android could not open the selected file")

        sourceStream.use { input ->
            BufferedInputStream(input).use { bufferedInput ->
                BufferedOutputStream(FileOutputStream(destination)).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = bufferedInput.read(buffer)
                        if (read < 0) break

                        totalBytes += read
                        if (totalBytes > MAX_ARCHIVE_BYTES) {
                            throw GameImportException(
                                "Selected ZIP exceeds the $MAX_ARCHIVE_BYTES byte limit",
                            )
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        }

        if (totalBytes == 0L) {
            throw GameImportException("The selected file is empty")
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }
    }

    private fun writeMetadata(metadataFile: File, summary: GameImportSummary) {
        val properties = Properties().apply {
            setProperty(KEY_FORMAT_VERSION, METADATA_FORMAT_VERSION)
            setProperty(KEY_FILE_COUNT, summary.fileCount.toString())
            setProperty(KEY_TOTAL_BYTES, summary.totalBytes.toString())
            setProperty(KEY_ARCHIVE_SHA256, summary.archiveSha256)
        }
        FileOutputStream(metadataFile).use { output ->
            properties.store(output, "Ultima Controller runtime import")
        }
    }

    private fun replaceCurrentImport(
        filesDirectory: File,
        stagingContainer: File,
        operationId: String,
    ) {
        val current = File(filesDirectory, IMPORT_CONTAINER_NAME)
        val backup = File(filesDirectory, "$BACKUP_CONTAINER_PREFIX$operationId")
        var movedCurrentToBackup = false

        if (current.exists()) {
            if (!current.renameTo(backup)) {
                throw GameImportException("Could not prepare the existing import for replacement")
            }
            movedCurrentToBackup = true
        }

        if (!stagingContainer.renameTo(current)) {
            if (movedCurrentToBackup && !backup.renameTo(current)) {
                throw GameImportException(
                    "Import failed and the previous import could not be restored",
                )
            }
            throw GameImportException("Could not activate the imported game files")
        }

        backup.deleteRecursively()
    }

    private fun recoverInterruptedReplace(filesDirectory: File) {
        val current = File(filesDirectory, IMPORT_CONTAINER_NAME)
        val backups = filesDirectory.listFiles()
            .orEmpty()
            .filter { it.name.startsWith(BACKUP_CONTAINER_PREFIX) }
            .sortedByDescending(File::lastModified)

        if (!current.exists()) {
            val newestBackup = backups.firstOrNull() ?: return
            if (!newestBackup.renameTo(current)) return
        }
        backups.filter { it.exists() }.forEach { it.deleteRecursively() }
    }

    private fun cleanupStaleStaging(filesDirectory: File) {
        filesDirectory.listFiles()
            .orEmpty()
            .filter { it.name.startsWith(STAGING_CONTAINER_PREFIX) }
            .forEach { it.deleteRecursively() }
    }

    private fun cleanupStaleArchives(cacheDirectory: File) {
        cacheDirectory.listFiles()
            .orEmpty()
            .filter { it.name.startsWith(TEMP_ARCHIVE_PREFIX) }
            .forEach(File::delete)
    }

    private fun containsRequiredExecutable(gameDirectory: File): Boolean =
        gameDirectory.listFiles()
            .orEmpty()
            .any { it.isFile && it.name.equals(REQUIRED_EXECUTABLE, ignoreCase = true) }

    private const val IMPORT_CONTAINER_NAME = "imported-game"
    private const val GAME_FILES_DIRECTORY_NAME = "files"
    private const val METADATA_FILE_NAME = "metadata.properties"
    private const val STAGING_CONTAINER_PREFIX = ".imported-game-staging-"
    private const val BACKUP_CONTAINER_PREFIX = ".imported-game-backup-"
    private const val TEMP_ARCHIVE_PREFIX = ".game-import-"
    private const val REQUIRED_EXECUTABLE = "ULTIMA.EXE"
    private const val COPY_BUFFER_BYTES = 32 * 1024
    private const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
    private const val METADATA_FORMAT_VERSION = "1"
    private const val KEY_FORMAT_VERSION = "formatVersion"
    private const val KEY_FILE_COUNT = "fileCount"
    private const val KEY_TOTAL_BYTES = "totalBytes"
    private const val KEY_ARCHIVE_SHA256 = "archiveSha256"
    private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
}
