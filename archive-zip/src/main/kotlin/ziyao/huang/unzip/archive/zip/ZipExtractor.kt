package ziyao.huang.unzip.archive.zip

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import ziyao.huang.unzip.archive.core.ArchiveEntry
import ziyao.huang.unzip.archive.core.ArchiveExtractor
import ziyao.huang.unzip.archive.core.ArchiveFormatException
import ziyao.huang.unzip.archive.core.ArchiveSource
import ziyao.huang.unzip.archive.core.ExtractProgress
import ziyao.huang.unzip.archive.core.ExtractResult
import ziyao.huang.unzip.archive.core.ExtractSink
import ziyao.huang.unzip.archive.core.ExtractionCancelledException
import ziyao.huang.unzip.archive.core.ExtractionRunner
import ziyao.huang.unzip.archive.core.PasswordPrompt
import ziyao.huang.unzip.archive.core.SafePaths
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * 本 app 的 ZIP 解压引擎（zip4j）。
 *
 * 逐条目循环统一走 [ExtractionRunner]：write 回调里做 zip4j 的逐条目解压并拷贝到 sink。
 * - 密码：选区含加密条目时循环开始前先问一次；解压中遇 WRONG_PASSWORD 再以
 *   firstAttempt=false 重问；prompt 返回 null 视为用户取消（抛 CancellationException，
 *   由 runner 封口为 cancelled 结果，extract 捕获后返回其携带的 [ExtractResult]）。
 * - slip 防护：写盘一律先过 [SafePaths.isSafe]，不安全条目不写盘、直接进 failed。
 * - 编码：经 [ZipCharset] 自动 UTF-8/GBK 回退，或用 [charsetOverride] 手动指定。
 *
 * [ArchiveSource] 只提供流；zip4j 的随机访问（fileHeaders + 逐条目 getInputStream）
 * 需要真实文件，因此进入前先把 source 落到临时文件，结束时删除。
 */
class ZipExtractor(private val charsetOverride: Charset? = null) : ArchiveExtractor {

    override suspend fun listEntries(
        source: ArchiveSource,
        password: PasswordPrompt,
    ): List<ArchiveEntry> {
        // ZIP 中央目录不加密，列清单不需要密码
        return withSpooledZipFile(source) { zipFile ->
            zipFile.fileHeaders.map { toArchiveEntry(it) }
        }
    }

    override suspend fun extract(
        source: ArchiveSource,
        sink: ExtractSink,
        entries: List<ArchiveEntry>,
        password: PasswordPrompt,
        onProgress: (ExtractProgress) -> Unit,
    ): ExtractResult {
        val session = PasswordSession(password)
        try {
            return withSpooledZipFile(source) { zipFile ->
                val headersByPath = zipFile.fileHeaders.associateBy { normalizePath(it) }

                // 循环开始前裁决：选区里有加密条目就必须先拿到密码；null = 用户取消，
                // 全部条目直接进 skipped（与 runner 取消封口结果形状一致）
                if (entries.any { it.encrypted }) {
                    if (session.promptFor(firstAttempt = true) == null) {
                        return@withSpooledZipFile ExtractResult(
                            succeeded = emptyList(),
                            skipped = entries,
                            failed = emptyList(),
                            cancelled = true,
                        )
                    }
                }

                try {
                    ExtractionRunner(sink).run(entries, onProgress) { entry ->
                        writeEntry(zipFile, headersByPath, sink, entry, session)
                    }
                } catch (e: ExtractionCancelledException) {
                    // 取消不外抛：返回 runner 封口的结果（其中 cancelled=true）
                    e.result
                }
            }
        } finally {
            session.clear()
        }
    }

    // ---------- 单条目 ----------

    private suspend fun writeEntry(
        zipFile: ZipFile,
        headersByPath: Map<String, FileHeader>,
        sink: ExtractSink,
        entry: ArchiveEntry,
        session: PasswordSession,
    ) {
        // slip 防护：不安全条目不写盘，直接进 failed
        if (!SafePaths.isSafe(entry.path)) {
            throw IOException("unsafe entry path: ${entry.path}")
        }
        val header = headersByPath[entry.path]
            ?: throw IOException("file header not found for entry: ${entry.path}")

        if (entry.isDir || header.isDirectory) {
            sink.createDirectory(entry.path)
            return
        }

        // 加密条目但会话里还没有密码（选区判定与实际条目加密态不一致的兜底）
        if (header.isEncrypted && session.currentPassword == null) {
            if (session.promptFor(firstAttempt = !session.hasFailed) == null) {
                throw CancellationException("password prompt cancelled")
            }
        }

        while (true) {
            zipFile.setPassword(session.currentPassword)
            val output = sink.createFile(entry.path)
            try {
                copyEntry(zipFile, header, output)
                return
            } catch (e: ZipException) {
                if (e.type != ZipException.Type.WRONG_PASSWORD) throw e
                // 密码错误 → firstAttempt=false 再问；null = 用户取消，走 runner 取消路径
                if (session.onPasswordRejected() == null) {
                    throw CancellationException("password prompt cancelled")
                }
                // 重试：循环顶部重新 createFile，覆盖上一次失败尝试的半截输出
            } finally {
                closeQuietly(output)
            }
        }
    }

    /** zip4j 阻塞 IO 包在 runInterruptible(Dispatchers.IO) 里；分块间再查协程活跃性，取消能及时中断。 */
    private suspend fun copyEntry(zipFile: ZipFile, header: FileHeader, output: OutputStream) {
        val context = currentCoroutineContext()
        runInterruptible(Dispatchers.IO) {
            zipFile.getInputStream(header).use { input ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    context.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    // ---------- 头映射 ----------

    private fun toArchiveEntry(header: FileHeader): ArchiveEntry {
        val rawName = header.fileName.orEmpty()
        return ArchiveEntry(
            path = normalizePath(header),
            isDir = header.isDirectory || rawName.endsWith('/') || rawName.endsWith('\\'),
            size = header.uncompressedSize,
            packedSize = header.compressedSize,
            encrypted = header.isEncrypted,
            mtime = header.lastModifiedTimeEpoch,
        )
    }

    private fun normalizePath(header: FileHeader): String =
        header.fileName.orEmpty().replace('\\', '/').trimEnd('/')

    // ---------- source → 临时文件 ----------

    private suspend fun <T> withSpooledZipFile(source: ArchiveSource, block: suspend (ZipFile) -> T): T {
        val temp = File.createTempFile("unseal-zip-", ".zip")
        try {
            val context = currentCoroutineContext()
            runInterruptible(Dispatchers.IO) {
                source.open().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            context.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            val resolved = try {
                ZipCharset.resolve(temp, charsetOverride)
            } catch (e: ZipException) {
                throw ArchiveFormatException("invalid or corrupt zip archive: ${e.message}")
            }
            return resolved.zipFile.use { zipFile -> block(zipFile) }
        } finally {
            temp.delete()
        }
    }

    // ---------- 密码会话 ----------

    /**
     * 一次 extract 内的密码状态：缓存最近成功密码供后续加密条目复用；
     * 任何一次失败后 [hasFailed] 置位，后续 prompt 的 firstAttempt=false。
     */
    private class PasswordSession(private val prompt: PasswordPrompt) {
        var currentPassword: CharArray? = null
            private set
        var hasFailed: Boolean = false
            private set

        suspend fun promptFor(firstAttempt: Boolean): CharArray? {
            val next = prompt(firstAttempt) ?: return null
            currentPassword?.fill(CLEAR_CHAR)
            currentPassword = next
            return next
        }

        suspend fun onPasswordRejected(): CharArray? {
            hasFailed = true
            return promptFor(firstAttempt = false)
        }

        fun clear() {
            currentPassword?.fill(CLEAR_CHAR)
            currentPassword = null
        }
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024

        /** 用后清零密码数组的填充值。 */
        const val CLEAR_CHAR = ' '
    }
}

private fun closeQuietly(stream: OutputStream?) {
    try {
        stream?.close()
    } catch (_: Exception) {
        // best-effort：关流失败不得掩盖原始异常
    }
}
