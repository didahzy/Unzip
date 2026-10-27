package ziyao.huang.unzip.archive.core

import java.io.OutputStream

/** 解压输出目标；[ExtractSink.createFile] 等的 [path] 与 [ArchiveEntry.path] 约定一致。 */
interface ExtractSink {
    fun createFile(path: String): OutputStream
    fun createDirectory(path: String)
    fun deletePartial(path: String)
    fun exists(path: String): Boolean
}
