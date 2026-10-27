package ziyao.huang.unzip.archive.core

/**
 * 压缩包内单个条目。
 *
 * [path] 约定：'/' 分隔、无前导 '/'、不以 '/' 结尾。
 */
data class ArchiveEntry(
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val packedSize: Long,
    val encrypted: Boolean,
    val mtime: Long?,
)
