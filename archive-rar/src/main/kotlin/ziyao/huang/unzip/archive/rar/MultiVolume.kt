package ziyao.huang.unzip.archive.rar

import java.io.File

/**
 * RAR 分卷发现：给定用户打开的任一卷，找齐同目录全部卷并按 part 号升序返回
 * （unrar 必须从最小 part 开始解）。
 */
object MultiVolume {

    /** 新式命名：name.partN.rar（WinRAR 默认，N 不足两位时可能补零）；matchEntire 全串锚定，无需 $。 */
    private val FIRST_VOLUME = Regex("^(.+)\\.part(\\d+)\\.rar")

    /**
     * 返回 [firstVolume] 所在分卷组的全部卷（绝对路径，按 part 号升序）。
     * 不匹配 partN 命名的输入视为单卷，原样返回自身。
     *
     * [listDirectory] 注入目录列表（app 层接缓存/SAF 物化目录，测试接 fake），
     * 默认实现按 java.io.File 列同目录。
     */
    fun siblings(
        firstVolume: String,
        listDirectory: (String) -> List<String> = { path ->
            File(path).parentFile?.listFiles()?.map { it.absolutePath }.orEmpty()
        },
    ): List<String> {
        // base 从文件名取（不是完整路径），与目录列表逐名匹配
        val match = FIRST_VOLUME.matchEntire(File(firstVolume).name) ?: return listOf(firstVolume)
        val base = match.groupValues[1]
        val partRegex = Regex(Regex.escape(base) + "\\.part(\\d+)\\.rar", RegexOption.IGNORE_CASE)
        val parent = File(firstVolume).parent ?: return listOf(firstVolume)
        val found = listDirectory(parent)
            .mapNotNull { path ->
                val name = File(path).name
                partRegex.matchEntire(name)?.let { it.groupValues[1].toInt() to path }
            }
            .sortedBy { it.first } // 数值序：part9 必须排在 part10 前
            .map { it.second }
        // 目录列表异常缺失输入卷时也要交回可用序列
        return if (found.any { it.equals(firstVolume, ignoreCase = true) }) found else found + firstVolume
    }
}
