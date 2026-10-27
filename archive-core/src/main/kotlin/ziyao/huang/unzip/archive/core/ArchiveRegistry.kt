package ziyao.huang.unzip.archive.core

/** 格式识别后按格式取实现；SEVEN_ZIP 等未注册格式 → 抛本异常。 */
class ArchiveRegistry(extractors: Map<ArchiveFormat, ArchiveExtractor>) {

    private val extractors: Map<ArchiveFormat, ArchiveExtractor> = extractors.toMap()

    fun sniffFormat(header: ByteArray): ArchiveFormat? = HeaderSniffer.sniff(header)

    fun extractorFor(format: ArchiveFormat): ArchiveExtractor =
        extractors[format]
            ?: throw UnsupportedOperationException("No extractor registered for $format")
}
