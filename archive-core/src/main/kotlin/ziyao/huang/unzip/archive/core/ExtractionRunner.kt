package ziyao.huang.unzip.archive.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * 解压被取消时抛出：本身是 [CancellationException]，额外携带重抛前已封口的
 * [ExtractResult]（[result.cancelled] 恒为 true）。调用方（各格式解压器）可捕获它
 * 取出结果后自行处理；不捕获则按普通取消继续向上传播，不破坏结构化并发。
 */
class ExtractionCancelledException(
    val result: ExtractResult,
    cause: CancellationException? = null,
) : CancellationException("extraction cancelled: ${result.succeeded.size} done, ${result.skipped.size} skipped, cause=$cause")

/**
 * 所有解压器共用的「逐条目执行 + 取消/半截文件统一语义」组件。
 *
 * 逐条目调用 [run] 的 [write]；单条目普通异常只记入 [ExtractResult.failed]，
 * 其余条目继续；[CancellationException] 视为整次解压取消：对当前条目调
 * [ExtractSink.deletePartial]，把当前条目与未开始条目记入 skipped，封口结果后
 * 以 [ExtractionCancelledException] 重抛（绝不吞掉取消）。
 */
class ExtractionRunner(private val sink: ExtractSink) {

    /** 逐条目执行 write；返回统一结果。取消/异常时对「当前条目」调 sink.deletePartial */
    suspend fun run(
        entries: List<ArchiveEntry>,
        onProgress: (ExtractProgress) -> Unit,
        write: suspend (ArchiveEntry) -> Unit,
    ): ExtractResult {
        val totalBytes = entries.sumOf { it.size }
        val succeeded = mutableListOf<ArchiveEntry>()
        val failed = mutableListOf<ArchiveEntry>()
        var processedBytes = 0L

        fun emit(currentFile: String?, doneCount: Int) {
            onProgress(
                ExtractProgress(
                    totalBytes = totalBytes,
                    processedBytes = processedBytes,
                    currentFile = currentFile,
                    doneCount = doneCount,
                    totalCount = entries.size,
                ),
            )
        }

        emit(currentFile = null, doneCount = 0)
        for ((index, entry) in entries.withIndex()) {
            emit(currentFile = entry.path, doneCount = index)
            try {
                write(entry)
            } catch (e: CancellationException) {
                deletePartialQuietly(entry.path)
                throw ExtractionCancelledException(
                    result = ExtractResult(
                        succeeded = succeeded.toList(),
                        skipped = entries.subList(index, entries.size),
                        failed = failed.toList(),
                        cancelled = true,
                    ),
                    cause = e,
                )
            } catch (e: Exception) {
                deletePartialQuietly(entry.path)
                failed += entry
                emit(currentFile = entry.path, doneCount = index + 1)
                continue
            }
            succeeded += entry
            processedBytes += entry.size
            emit(currentFile = entry.path, doneCount = index + 1)
        }
        return ExtractResult(
            succeeded = succeeded.toList(),
            skipped = emptyList(),
            failed = failed.toList(),
            cancelled = false,
        )
    }

    /** 清理尽力而为：清理失败不得掩盖取消或原始失败。 */
    private fun deletePartialQuietly(path: String) {
        try {
            sink.deletePartial(path)
        } catch (_: Exception) {
            // best-effort
        }
    }
}
