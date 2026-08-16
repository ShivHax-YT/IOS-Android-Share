package com.nearpair.app.files

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.nearpair.app.model.ReceivedFile
import com.nearpair.app.model.StagedFile
import com.nearpair.app.protocol.ProtocolErrorCode
import com.nearpair.app.protocol.TransferMetadata
import com.nearpair.app.protocol.isAllowedMimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.max

class FileStore(private val context: Context) {
    private val outgoingDirectory = File(context.cacheDir, "outgoing")
    private val inboxDirectory = File(context.filesDir, "inbox")

    init {
        val staleBefore = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        outgoingDirectory.listFiles()?.filter { it.isFile && it.lastModified() < staleBefore }?.forEach(File::delete)
        // V1 intentionally has no transfer history. A verified inbox file is
        // offered only by the live receive screen, so after process death it
        // is orphaned and must not remain hidden in private storage.
        inboxDirectory.listFiles()?.filter(File::isFile)?.forEach(File::delete)
    }

    suspend fun stageOutgoing(uri: Uri): Result<StagedFile> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val document = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                    name to size
                }

            val rawName = document?.first ?: "selected-file"
            val displayName = sanitizeFileName(rawName)
            val mimeType = resolver.getType(uri)
                ?: mimeTypeFromName(displayName)
                ?: error("The selected file has no recognized type")
            require(isAllowedMimeType(mimeType)) { "Only PDFs, images, and videos are supported" }

            val expectedSize = document?.second?.takeIf { it > 0L }
            if (expectedSize != null && expectedSize > 0L) {
                require(hasCapacity(outgoingDirectory, expectedSize)) { "Not enough free space to stage this file" }
            }

            outgoingDirectory.mkdirs()
            val destination = File(outgoingDirectory, "${UUID.randomUUID()}-$displayName")
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            try {
                resolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "The selected file cannot be opened" }
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            written += count
                        }
                    }
                }
                require(written > 0L) { "Empty files are not supported" }
                if (expectedSize != null) require(written == expectedSize) { "The selected file changed while it was staged" }
                StagedFile(
                    file = destination,
                    displayName = displayName,
                    mimeType = mimeType,
                    sizeBytes = written,
                    sha256 = digest.digest().toHex(),
                )
            } catch (error: Throwable) {
                destination.delete()
                throw error
            }
        }
    }

    fun hasCapacityForIncomingTransfer(metadata: TransferMetadata): Boolean =
        hasCapacity(inboxDirectory, metadata.sizeBytes, copies = 2)

    suspend fun commitIncoming(uri: Uri, metadata: TransferMetadata): Result<ReceivedFile> =
        withContext(Dispatchers.IO) {
            runCatching {
                try {
                    require(hasCapacity(inboxDirectory, metadata.sizeBytes)) {
                        ProtocolErrorCode.INSUFFICIENT_STORAGE.name
                    }
                    inboxDirectory.mkdirs()
                    val safeName = uniqueInboxName(sanitizeFileName(metadata.fileName))
                    val partial = File(inboxDirectory, ".$safeName.${UUID.randomUUID()}.part")
                    val destination = File(inboxDirectory, safeName)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var written = 0L

                    try {
                        context.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "The received temporary file cannot be opened" }
                            FileOutputStream(partial).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    written += count
                                }
                            }
                        }

                        require(written == metadata.sizeBytes) { ProtocolErrorCode.SIZE_MISMATCH.name }
                        val actualHash = digest.digest().toHex()
                        require(actualHash == metadata.sha256) { ProtocolErrorCode.CHECKSUM_MISMATCH.name }
                        check(partial.renameTo(destination)) { "Could not commit the verified inbox file" }

                        ReceivedFile(
                            file = destination,
                            displayName = destination.name,
                            mimeType = metadata.mimeType,
                            sizeBytes = written,
                            sha256 = actualHash,
                        )
                    } catch (error: Throwable) {
                        partial.delete()
                        destination.delete()
                        throw error
                    }
                } finally {
                    deleteTemporary(uri)
                }
            }
        }

    suspend fun copyReceivedTo(receivedFile: ReceivedFile, destination: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(destination, "w").use { output ->
                    requireNotNull(output) { "The save destination cannot be opened" }
                    FileInputStream(receivedFile.file).use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 8) }
                }
                Unit
            }
        }

    fun delete(receivedFile: ReceivedFile): Boolean = receivedFile.file.delete()

    fun delete(stagedFile: StagedFile): Boolean = stagedFile.file.delete()

    fun deleteTemporary(uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun hasCapacity(directory: File, sizeBytes: Long, copies: Int = 1): Boolean {
        directory.mkdirs()
        val margin = max(32L * 1024L * 1024L, sizeBytes / 20L)
        val contentBytes = if (sizeBytes > Long.MAX_VALUE / copies) Long.MAX_VALUE else sizeBytes * copies
        val required = if (Long.MAX_VALUE - contentBytes < margin) Long.MAX_VALUE else contentBytes + margin
        return StatFs(directory.absolutePath).availableBytes >= required
    }

    private fun uniqueInboxName(preferred: String): String {
        val first = File(inboxDirectory, preferred)
        if (!first.exists()) return preferred
        val dot = preferred.lastIndexOf('.')
        val stem = if (dot > 0) preferred.substring(0, dot) else preferred
        val extension = if (dot > 0) preferred.substring(dot) else ""
        var suffix = 2
        while (File(inboxDirectory, "$stem ($suffix)$extension").exists()) suffix += 1
        return "$stem ($suffix)$extension"
    }

    companion object {
        fun sanitizeFileName(raw: String): String {
            val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
            val sanitized = buildString {
                leaf.forEach { character ->
                    when {
                        character.code < 32 || character.code == 127 -> append('_')
                        character in listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|') -> append('_')
                        else -> append(character)
                    }
                }
            }.trim().trimStart('.')
            val nonBlank = sanitized.ifBlank { "received-file" }
            val limited = StringBuilder()
            nonBlank.codePoints().forEach { codePoint ->
                val next = String(Character.toChars(codePoint))
                if ((limited.toString() + next).toByteArray(Charsets.UTF_8).size <= 180) limited.append(next)
            }
            return limited.toString().ifBlank { "received-file" }
        }

        private fun mimeTypeFromName(name: String): String? = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            else -> null
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
