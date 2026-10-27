package ziyao.huang.unzip.archive.core

/** 按文件头魔数识别压缩包格式。 */
object HeaderSniffer {

    private val ZIP_LOCAL_FILE_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK\03\04"
    private val ZIP_EMPTY_EOCD = byteArrayOf(0x50, 0x4B, 0x05, 0x06) // "PK\05\06"，空包
    private val RAR4_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00) // "Rar!\u001A\07\00"
    private val RAR5_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00) // "Rar!\u001A\07\01\00"
    private val SEVEN_ZIP_SIGNATURE = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C) // "7z\xBC\xAF'\x1C"

    /** 未知格式/字节不足 → null */
    fun sniff(header: ByteArray): ArchiveFormat? {
        if (header.startsWith(ZIP_LOCAL_FILE_HEADER) || header.startsWith(ZIP_EMPTY_EOCD)) {
            return ArchiveFormat.ZIP
        }
        if (header.startsWith(SEVEN_ZIP_SIGNATURE)) {
            return ArchiveFormat.SEVEN_ZIP
        }
        if (header.startsWith(RAR4_SIGNATURE) || header.startsWith(RAR5_SIGNATURE)) {
            return ArchiveFormat.RAR
        }
        return null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }
}
