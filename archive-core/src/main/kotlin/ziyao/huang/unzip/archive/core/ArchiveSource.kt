package ziyao.huang.unzip.archive.core

import java.io.InputStream

/** 压缩包数据源；[open] 每次返回新流。 */
interface ArchiveSource {
    fun open(): InputStream
    val size: Long
}
