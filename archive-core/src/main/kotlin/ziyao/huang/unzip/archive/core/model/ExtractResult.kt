package ziyao.huang.unzip.archive.core

/** 一次解压的结果汇总。 */
data class ExtractResult(
    val succeeded: List<ArchiveEntry>,
    val skipped: List<ArchiveEntry>,
    val failed: List<ArchiveEntry>,
    val cancelled: Boolean,
)
