/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package app.grapheneos.backup.storage.restore

import app.grapheneos.backup.storage.api.RestoreObserver
import app.grapheneos.backup.storage.api.StoredSnapshot
import app.grapheneos.backup.storage.crypto.StreamCrypto
import app.grapheneos.seedvault.core.backends.FileBackupFileType.Blob
import app.grapheneos.seedvault.core.backends.IBackendManager
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException

internal abstract class AbstractChunkRestore(
    private val backendManager: IBackendManager,
    private val fileRestore: FileRestore,
    private val streamCrypto: StreamCrypto,
    private val streamKey: ByteArray,
) {

    private val backend get() = backendManager.backend

    @Throws(IOException::class, GeneralSecurityException::class)
    protected suspend fun getAndDecryptChunk(
        version: Int,
        storedSnapshot: StoredSnapshot,
        chunkId: String,
        streamReader: suspend (InputStream) -> Unit,
    ) {
        backend.load(Blob(storedSnapshot.androidId, chunkId)).use { inputStream ->
            inputStream.readVersion(version)
            val ad = streamCrypto.getAssociatedDataForChunk(chunkId, version.toByte())
            streamCrypto.newDecryptingStream(streamKey, inputStream, ad).use { decryptedStream ->
                streamReader(decryptedStream)
            }
        }
    }

    @Throws(IOException::class)
    protected suspend fun restoreFile(
        file: RestorableFile,
        observer: RestoreObserver?,
        tag: String,
        streamWriter: suspend (outputStream: OutputStream) -> Long,
    ) {
        fileRestore.restoreFile(file, observer, tag, streamWriter)
    }

}
