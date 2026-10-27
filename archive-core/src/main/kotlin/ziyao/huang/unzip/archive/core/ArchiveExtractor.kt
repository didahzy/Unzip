package ziyao.huang.unzip.archive.core

/**
 * 密码获取回调。[firstAttempt] 为 false 表示此前已尝试失败；
 * 返回 null 表示用户取消（实现方随后抛 [PasswordCancelledException]）。
 */
typealias PasswordPrompt = suspend (firstAttempt: Boolean) -> CharArray?

/** 各压缩格式的解压实现。 */
interface ArchiveExtractor {
    suspend fun listEntries(source: ArchiveSource, password: PasswordPrompt): List<ArchiveEntry>
    suspend fun extract(
        source: ArchiveSource,
        sink: ExtractSink,
        entries: List<ArchiveEntry>,
        password: PasswordPrompt,
        onProgress: (ExtractProgress) -> Unit,
    ): ExtractResult
}
