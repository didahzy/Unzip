package ziyao.huang.unzip.archive.rar

import java.io.File

/**
 * 测试用 [RarNative] 假实现：可编程行为（正确密码、条目落盘内容、open 故障）
 * + 全量调用记录。密码记录一律深拷贝，避免被 PasswordSession 的 fill(' ')
 * 卫生清理污染事后断言。
 */
class FakeRarNative(
    var info: NativeRarInfo = NativeRarInfo(isHeaderEncrypted = false, volumes = emptyList()),
    var entries: List<NativeRarEntry> = emptyList(),

    /** 加密条目/加密头的正确密码；null 表示包内无加密，任何密码都放行。 */
    var password: CharArray? = null,
    var contentByIndex: Map<Int, ByteArray> = emptyMap(),
    var openError: UnrarException? = null,
) : RarNative {

    data class ExtractCall(
        val paths: List<String>,
        val outDir: String,
        val password: CharArray?,
        val wanted: List<Int>,
    )

    val openCalls = mutableListOf<List<String>>()
    val listCalls = mutableListOf<CharArray?>()
    val extractCalls = mutableListOf<ExtractCall>()

    override fun open(paths: List<String>): NativeRarInfo {
        openCalls += paths.toList()
        openError?.let { throw it }
        return info
    }

    override fun list(paths: List<String>, password: CharArray?): List<NativeRarEntry> {
        listCalls += password?.copyOf()
        if (info.isHeaderEncrypted && !matches(password)) {
            throw UnrarException("wrong password", UnrarException.Reason.WRONG_PASSWORD)
        }
        return entries
    }

    override fun extract(
        paths: List<String>,
        outDir: String,
        password: CharArray?,
        wanted: IntArray,
        onFileDone: (Int) -> Unit,
    ) {
        extractCalls += ExtractCall(paths.toList(), outDir, password?.copyOf(), wanted.toList())
        for (index in wanted) {
            val entry = entries[index]
            if (entry.isEncrypted && !matches(password)) {
                throw UnrarException("wrong password", UnrarException.Reason.WRONG_PASSWORD)
            }
            // 模拟 unrar：把解出的文件写到真实目录（支持嵌套路径）
            val target = File(outDir, entry.name)
            target.parentFile?.mkdirs()
            target.writeBytes(contentByIndex[index] ?: ByteArray(0))
            onFileDone(index)
        }
    }

    private fun matches(actual: CharArray?): Boolean =
        password == null || (actual != null && actual.contentEquals(password))
}
