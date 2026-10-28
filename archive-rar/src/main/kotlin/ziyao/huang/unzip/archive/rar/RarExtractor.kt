package ziyao.huang.unzip.archive.rar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import ziyao.huang.unzip.archive.core.ArchiveEntry
import ziyao.huang.unzip.archive.core.ArchiveExtractor
import ziyao.huang.unzip.archive.core.ArchiveFormatException
import ziyao.huang.unzip.archive.core.ArchiveSource
import ziyao.huang.unzip.archive.core.ExtractProgress
import ziyao.huang.unzip.archive.core.ExtractResult
import ziyao.huang.unzip.archive.core.ExtractSink
import ziyao.huang.unzip.archive.core.ExtractionCancelledException
import ziyao.huang.unzip.archive.core.ExtractionRunner
import ziyao.huang.unzip.archive.core.PasswordCancelledException
import ziyao.huang.unzip.archive.core.PasswordPrompt
import ziyao.huang.unzip.archive.core.SafePaths
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files

/**
 * RAR 解压引擎（Java 侧；native 经 [RarNative] 接缝，真身 JNI 在 Task 6）。
 *
 * unrar 的 native 层只能把文件解到真实目录，而目标是抽象 [ExtractSink]，因此
 * extract 的流程是：为本次解压建临时目录 → 用 [ExtractionRunner] 逐条目驱动，
 * 每个条目的 write 回调里 `native.extract(wanted=[该条目])` 解到临时目录 →
 * 找到产出文件流式拷入 sink → 删除临时文件。逐条目调用 native（而非整包一次
 * 解完）换取与 ZIP 一致的取消/半截语义；solid 大包因此有重复解压的性能代价，
 * v1 接受（规格已记账）。
 *
 * - 密码：header 加密（-hp）在列出清单/解压开始前就必须拿到密码（技术上无法
 *   无密码列目），prompt 返回 null = 用户取消 → [PasswordCancelledException]；
 *   解压中途密码错误 → prompt(firstAttempt=false) 重试，null → 抛
 *   CancellationException 走 runner 取消路径（全部剩余条目 skipped）。
 * - slip 防护：写盘前先过 [SafePaths.isSafe]，不安全条目不碰 native、直接进 failed；
 *   native 侧（Task 6 JNI 胶水）另有一层 C++ 校验兜底。
 * - [realFiles] 由 app 层（Task 7）提供 source 物化后的真实文件路径（分卷为
 *   全部卷、按序）；[workDir] 供 app 层显式传 cache 目录（真机 java.io.tmpdir
 *   行为存疑，见 Task 4 ruling），缺省落到系统临时目录。
 */
class RarExtractor(
    private val native: RarNative,
    private val realFiles: (ArchiveSource) -> List<String>,
    private val workDir: File? = null,
) : ArchiveExtractor {

    override suspend fun listEntries(
        source: ArchiveSource,
        password: PasswordPrompt,
    ): List<ArchiveEntry> {
        val paths = realFiles(source)
        val session = PasswordSession(password)
        try {
            val info = openArchive(paths)
            if (info.isHeaderEncrypted && session.promptFor(firstAttempt = true) == null) {
                throw PasswordCancelledException("header-encrypted rar: password prompt cancelled")
            }
            return listWithRetry(paths, session).map { toArchiveEntry(it) }
        } finally {
            session.clear()
        }
    }

    override suspend fun extract(
        source: ArchiveSource,
        sink: ExtractSink,
        entries: List<ArchiveEntry>,
        password: PasswordPrompt,
        onProgress: (ExtractProgress) -> Unit,
    ): ExtractResult {
        val paths = realFiles(source)
        val session = PasswordSession(password)
        try {
            val info = openArchive(paths)
            // 循环前裁决：header 加密必须先有密码；null = 用户取消，直接抛
            if (info.isHeaderEncrypted && session.promptFor(firstAttempt = true) == null) {
                throw PasswordCancelledException("header-encrypted rar: password prompt cancelled")
            }
            // 条目路径 → native list 索引；wanted=[索引] 逐条目驱动 native
            val nativeIndexByPath = listWithRetry(paths, session)
                .mapIndexed { index, entry -> normalizePath(entry.name) to index }
                .toMap() // 重名取末尾（v1，与 ZIP 一致）

            val tempDir = createTempDir()
            try {
                return try {
                    ExtractionRunner(sink).run(entries, onProgress) { entry ->
                        writeEntry(paths, tempDir, sink, entry, session, nativeIndexByPath)
                    }
                } catch (e: ExtractionCancelledException) {
                    // 取消不外抛：返回 runner 封口的结果（其中 cancelled=true）
                    e.result
                }
            } finally {
                tempDir.deleteRecursively()
            }
        } finally {
            session.clear()
        }
    }

    // ---------- 单条目 ----------

    private suspend fun writeEntry(
        paths: List<String>,
        tempDir: File,
        sink: ExtractSink,
        entry: ArchiveEntry,
        session: PasswordSession,
        nativeIndexByPath: Map<String, Int>,
    ) {
        currentCoroutineContext().ensureActive() // 条目间活跃性检查
        // slip 防护：不安全条目不写盘、不碰 native，直接进 failed
        if (!SafePaths.isSafe(entry.path)) {
            throw IOException("unsafe entry path: ${entry.path}")
        }
        if (entry.isDir) {
            sink.createDirectory(entry.path)
            return
        }
        val nativeIndex = nativeIndexByPath[entry.path]
            ?: throw IOException("entry not present in rar: ${entry.path}")

        // 加密条目但会话里还没有密码：先问一次，避免拿 null 密码白跑一趟 native
        if (entry.encrypted && session.currentPassword == null) {
            if (session.promptFor(firstAttempt = !session.hasFailed) == null) {
                throw CancellationException("password prompt cancelled")
            }
        }

        while (true) {
            // 清掉上一次失败尝试可能残留的半截文件，避免捞到陈旧产出
            clearTempDir(tempDir)
            val produced = try {
                val pw = session.currentPassword
                runInterruptible(Dispatchers.IO) {
                    native.extract(paths, tempDir.absolutePath, pw, intArrayOf(nativeIndex)) { }
                    findProducedFile(tempDir)
                }
            } catch (e: UnrarException) {
                if (e.reason != UnrarException.Reason.WRONG_PASSWORD) throw e
                // 密码错误 → firstAttempt=false 再问；null = 用户取消，走 runner 取消路径
                if (session.onPasswordRejected() == null) {
                    throw CancellationException("password prompt cancelled")
                }
                continue
            }
            copyIntoSink(produced, sink, entry.path)
            produced.delete()
            return
        }
    }

    /** 把 native 解出的临时文件流式拷入 sink；分块间查协程活跃性，取消能及时中断。 */
    private suspend fun copyIntoSink(produced: File, sink: ExtractSink, path: String) {
        val output = sink.createFile(path)
        try {
            val context = currentCoroutineContext()
            runInterruptible(Dispatchers.IO) {
                produced.inputStream().use { input ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        context.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        } finally {
            closeQuietly(output)
        }
    }

    /** 单条目解压只允许产出一个文件；目录不算（目录条目根本不走 native）。 */
    private fun findProducedFile(tempDir: File): File {
        val produced = tempDir.walkTopDown().filter { it.isFile }.toList()
        if (produced.isEmpty()) throw IOException("native extract produced no file")
        if (produced.size > 1) {
            throw IOException("native extract produced ${produced.size} files for a single entry")
        }
        return produced[0]
    }

    // ---------- native 调用（阻塞 JNI → runInterruptible） ----------

    private suspend fun openArchive(paths: List<String>): NativeRarInfo =
        try {
            runInterruptible(Dispatchers.IO) { native.open(paths) }
        } catch (e: UnrarException) {
            throw translate(e)
        }

    /**
     * 列条目并处理错密码重试：WRONG_PASSWORD → prompt(firstAttempt=false)；
     * null = 用户取消（发生在 runner 循环之前 → [PasswordCancelledException]）。
     */
    private suspend fun listWithRetry(paths: List<String>, session: PasswordSession): List<NativeRarEntry> {
        while (true) {
            val pw = session.currentPassword
            try {
                return runInterruptible(Dispatchers.IO) { native.list(paths, pw) }
            } catch (e: UnrarException) {
                if (e.reason != UnrarException.Reason.WRONG_PASSWORD) throw translate(e)
                if (session.onPasswordRejected() == null) {
                    throw PasswordCancelledException("rar list: password prompt cancelled")
                }
            }
        }
    }

    /** 畸形/损坏包统一转译为 [ArchiveFormatException]；IO 等其余原因原样上抛。 */
    private fun translate(e: UnrarException): Exception =
        if (e.reason == UnrarException.Reason.BAD_ARCHIVE) {
            ArchiveFormatException("invalid or corrupt rar archive: ${e.message}")
        } else {
            e
        }

    // ---------- 头映射 ----------

    private fun toArchiveEntry(entry: NativeRarEntry): ArchiveEntry = ArchiveEntry(
        path = normalizePath(entry.name),
        isDir = entry.isDir,
        size = entry.size,
        packedSize = entry.packedSize,
        encrypted = entry.isEncrypted,
        mtime = entry.mtimeEpochSec?.times(1000L), // 秒 → 毫秒
    )

    private fun normalizePath(name: String): String = name.replace('\\', '/').trimEnd('/')

    // ---------- 临时目录 ----------

    private fun createTempDir(): File {
        val prefix = "unseal-rar-"
        return if (workDir != null) {
            Files.createTempDirectory(workDir.toPath(), prefix).toFile()
        } else {
            Files.createTempDirectory(prefix).toFile()
        }
    }

    private fun clearTempDir(tempDir: File) {
        tempDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    // ---------- 密码会话 ----------

    /**
     * 一次 list/extract 内的密码状态：缓存最近成功密码供后续加密条目复用；
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
        const val CLEAR_CHAR = ' '
    }
}

private fun closeQuietly(stream: OutputStream?) {
    try {
        stream?.close()
    } catch (_: Exception) {
        // best-effort：关流失败不得掩盖原始异常
    }
}
