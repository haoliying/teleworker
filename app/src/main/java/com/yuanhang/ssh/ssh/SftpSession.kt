package com.yuanhang.ssh.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class RemoteEntry(
    val filename: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
    val permissions: Int
)

/**
 * Thin coroutine friendly facade over JSch's SFTP channel.
 */
class SftpSession(
    val sessionId: String,
    val displayName: String,
    private val ssh: Session
) {

    private val channel: ChannelSftp = ssh.openChannel("sftp") as ChannelSftp

    suspend fun open() = withContext(Dispatchers.IO) {
        channel.connect(25_000)
    }

    fun home(): String = try {
        channel.realpath("")
    } catch (t: Throwable) {
        "."
    }

    suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val target = if (path.isEmpty()) home() else path
        @Suppress("UNCHECKED_CAST")
        val raw = channel.ls(target) as List<ChannelSftp.LsEntry>
        raw.asSequence()
            .filter { it.filename != "." && it.filename != ".." }
            .map { e ->
                val abs = concat(target, e.filename)
                RemoteEntry(
                    filename = e.filename,
                    path = abs,
                    isDirectory = e.attrs.isDir,
                    size = e.attrs.size,
                    modifiedAt = e.attrs.getMTime().toLong() * 1000,
                    permissions = e.attrs.permissions
                )
            }
            .sortedWith(compareBy({ !it.isDirectory }, { it.filename.lowercase() }))
            .toList()
    }

    fun concat(parent: String, child: String): String =
        parent.trimEnd('/') + "/" + child

    fun parentOf(path: String): String {
        val trimmed = path.trimEnd('/')
        val idx = trimmed.lastIndexOf('/')
        return if (idx <= 0) "/" else trimmed.substring(0, idx)
    }

    suspend fun mkdir(path: String, name: String): Result<Unit> = runCatchingIo {
        channel.mkdir(concat(path, name))
    }

    suspend fun delete(path: String, isDirectory: Boolean): Result<Unit> = runCatchingIo {
        if (isDirectory) channel.rmdir(path) else channel.rm(path)
    }

    suspend fun rename(path: String, newName: String): Result<Unit> = runCatchingIo {
        channel.rename(path, concat(parentOf(path), newName))
    }

    suspend fun chmod(path: String, permissions: Int): Result<Unit> = runCatchingIo {
        channel.chmod(permissions, path)
    }

    /**
     * Upload a local file into [remoteDir]. Progress is reported on a 0..100 scale.
     */
    suspend fun upload(
        local: File,
        remoteDir: String,
        remoteName: String = local.name,
        onProgress: (Int) -> Unit = {}
    ): Result<String> = runCatchingIoValue {
        val target = concat(remoteDir, remoteName)
        channel.put(local.absolutePath, target, progressMonitor(onProgress, local.length()))
        onProgress(100)
        target
    }

    /**
     * Download a remote file into [localDir]. Returns the resulting local file.
     */
    suspend fun download(
        remote: RemoteEntry,
        localDir: File,
        localName: String = remote.filename,
        onProgress: (Int) -> Unit = {}
    ): Result<File> = runCatchingIoValue {
        if (!localDir.exists()) localDir.mkdirs()
        val destination = File(localDir, localName)
        channel.get(
            remote.path,
            destination.absolutePath,
            progressMonitor(onProgress, remote.size)
        )
        onProgress(100)
        destination
    }

    private fun progressMonitor(onProgress: (Int) -> Unit, total: Long): SftpProgressMonitor =
        object : SftpProgressMonitor {
            private var transferred = 0L
            private var lastPercent = -1

            override fun init(op: Int, src: String, dest: String, max: Long) = Unit

            override fun count(count: Long): Boolean {
                transferred += count
                val percent =
                    if (total <= 0L) 100 else ((transferred * 100) / total).toInt().coerceIn(0, 100)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
                return true
            }

            override fun end() {
                onProgress(100)
            }
        }

    fun close() {
        try { channel.disconnect() } catch (t: Throwable) { /* ignore */ }
    }

    private inline fun runCatchingIo(block: () -> Unit): Result<Unit> =
        try {
            Result.success(block())
        } catch (e: SftpException) {
            Result.failure(IllegalStateException(sftpMessage(e), e))
        } catch (e: Throwable) {
            Result.failure(e)
        }

    private inline fun <T> runCatchingIoValue(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: SftpException) {
            Result.failure(IllegalStateException(sftpMessage(e), e))
        } catch (e: Throwable) {
            Result.failure(e)
        }

    private fun sftpMessage(e: SftpException): String =
        when (e.id) {
            ChannelSftp.SSH_FX_NO_SUCH_FILE -> "文件或目录不存在"
            ChannelSftp.SSH_FX_PERMISSION_DENIED -> "权限不足"
            ChannelSftp.SSH_FX_FAILURE -> "操作失败"
            ChannelSftp.SSH_FX_OP_UNSUPPORTED -> "服务端不支持该操作"
            else -> e.message ?: "SFTP 操作失败"
        }
}
