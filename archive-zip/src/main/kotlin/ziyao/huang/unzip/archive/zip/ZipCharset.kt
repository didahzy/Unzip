package ziyao.huang.unzip.archive.zip

import net.lingala.zip4j.ZipFile
import java.io.File
import java.nio.charset.Charset

/**
 * ZIP 文件名编码裁决（简报规则）：
 * 先用 UTF-8 列文件头，任一名称含 U+FFFD（替换符）→ 整体用 GBK 重列。
 * 返回值带 [Resolved.usedCharset]，供 UI 展示与手动切换。
 *
 * zip4j 细节：ZipFile.setCharset 显式指定后，无论 EFS 标志是否置位，
 * 所有文件名都按该 charset 解码（见 zip4j HeaderUtil.decodeStringWithCharset）。
 * 因此 GBK 字节名在 UTF-8 显式解码下必然出现 U+FFFD，可作回退判据。
 */
object ZipCharset {

    /** 回退编码；Android / JVM 均内置。 */
    val GBK: Charset = Charset.forName("GBK")

    private const val REPLACEMENT_CHAR = '�'

    /** [zipFile] 已按裁决结果配置好 charset，由调用方负责 close。 */
    data class Resolved(val zipFile: ZipFile, val usedCharset: Charset)

    /**
     * 打开压缩包并裁决编码。
     * [override] 非 null 时直接采用（UI 手动切换），跳过自动探测。
     * 压缩包损坏时 zip4j 抛 [net.lingala.zip4j.exception.ZipException]，由调用方转译。
     */
    fun resolve(file: File, override: Charset? = null): Resolved {
        if (override != null) {
            return Resolved(open(file, override), override)
        }

        val utf8Zip = open(file, Charsets.UTF_8)
        val looksLikeMojibake = utf8Zip.fileHeaders.any { header ->
            header.fileName?.contains(REPLACEMENT_CHAR) == true
        }
        if (!looksLikeMojibake) {
            return Resolved(utf8Zip, Charsets.UTF_8)
        }

        // 整体用 GBK 重列
        utf8Zip.close()
        return Resolved(open(file, GBK), GBK)
    }

    private fun open(file: File, charset: Charset): ZipFile =
        ZipFile(file).apply { setCharset(charset) }
}
