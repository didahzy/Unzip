package ziyao.huang.unzip.archive.core

/** 解压进度快照。 */
data class ExtractProgress(
    val totalBytes: Long,
    val processedBytes: Long,
    val currentFile: String?,
    val doneCount: Int,
    val totalCount: Int,
)
