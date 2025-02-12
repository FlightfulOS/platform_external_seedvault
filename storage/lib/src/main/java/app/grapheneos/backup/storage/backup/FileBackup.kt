/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package app.grapheneos.backup.storage.backup

import android.content.ContentResolver
import android.util.Log
import app.grapheneos.backup.storage.api.BackupObserver
import app.grapheneos.backup.storage.content.ContentFile
import app.grapheneos.backup.storage.content.DocFile
import app.grapheneos.backup.storage.content.MediaFile
import app.grapheneos.backup.storage.db.CachedFile
import app.grapheneos.backup.storage.db.ChunksCache
import app.grapheneos.backup.storage.db.FilesCache
import app.grapheneos.seedvault.core.backends.saf.openInputStream
import java.io.IOException
import java.security.GeneralSecurityException

internal class FileBackup(
    private val contentResolver: ContentResolver,
    private val hasMediaAccessPerm: Boolean,
    private val filesCache: FilesCache,
    private val chunksCache: ChunksCache,
    private val chunker: Chunker,
    private val chunkWriter: ChunkWriter,
) {

    companion object {
        private const val TAG = "FileBackup"
    }

    suspend fun backupFiles(
        files: List<ContentFile>,
        availableChunkIds: Set<String>,
        backupObserver: BackupObserver?,
    ): BackupResult {
        val chunkIds = HashSet<String>()
        val backupMediaFiles = ArrayList<BackupMediaFile>()
        val backupDocumentFiles = ArrayList<BackupDocumentFile>()
        var bytesWritten = 0L
        files.forEach { file ->
            val result = try {
                backupFile(file, availableChunkIds)
            } catch (e: IOException) {
                backupObserver?.onFileBackupError(file, "L")
                Log.e(TAG, "Error backing up ${file.uri}", e)
                null
            } catch (e: GeneralSecurityException) {
                Log.e(TAG, "Error backing up ${file.uri}", e)
                throw AssertionError(e)
            } ?: return@forEach
            when (file) {
                is MediaFile -> backupMediaFiles.add(file.toBackupFile(result.chunkIds))
                is DocFile -> backupDocumentFiles.add(file.toBackupFile(result.chunkIds))
            }
            chunkIds.addAll(result.chunkIds)
            bytesWritten += result.bytesWritten
            backupObserver?.onFileBackedUp(
                file = file,
                wasUploaded = result.hasChanged,
                reusedChunks = result.savedChunks,
                bytesWritten = result.bytesWritten,
                tag = "L"
            )
        }
        return BackupResult(chunkIds, backupMediaFiles, backupDocumentFiles)
    }

    private class FileBackupResult(
        val chunkIds: List<String>,
        val savedChunks: Int,
        val bytesWritten: Long,
        val hasChanged: Boolean,
    )

    @Throws(IOException::class, GeneralSecurityException::class)
    private suspend fun backupFile(
        file: ContentFile,
        availableChunkIds: Set<String>,
    ): FileBackupResult {
        val cachedFile = filesCache.getByUri(file.uri)
        val missingChunkIds = cachedFile?.chunks?.minus(availableChunkIds) ?: emptyList()
        val hasCorruptedChunks = chunksCache.hasCorruptedChunks(cachedFile?.chunks ?: emptyList())
        if (missingChunkIds.isEmpty() && file.hasNotChanged(cachedFile) && !hasCorruptedChunks) {
            cachedFile as CachedFile // not null because hasNotChanged() returned true
            return FileBackupResult(cachedFile.chunks, cachedFile.chunks.size, 0L, false)
        }
        val uri = file.getOriginalUri(hasMediaAccessPerm)
        val chunks = uri.openInputStream(contentResolver).use { inputStream ->
            chunker.makeChunks(inputStream)
        }
        val chunkWriterResult = uri.openInputStream(contentResolver).use { inputStream ->
            chunkWriter.writeChunk(inputStream, chunks, missingChunkIds)
        }

        val chunkIds = chunks.map { it.id }
        // Attention: don't modify the same file concurrently or this will cause bugs
        filesCache.upsert(file.toCachedFile(chunkIds))

        return FileBackupResult(
            chunkIds = chunkIds,
            savedChunks = chunks.size - chunkWriterResult.numChunksWritten,
            bytesWritten = chunkWriterResult.bytesWritten,
            hasChanged = true,
        )
    }

}
