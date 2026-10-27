package ziyao.huang.unzip.archive.core

/** 压缩包内路径安全守卫（防 Zip Slip 目录穿越）。 */
object SafePaths {

    /**
     * 规则：以 '/' 或 '\' 开头、含 '\0'、按路径分隔符（'/' 与 '\'）拆分后
     * 任一段为 '..' → 不安全；其余 → 安全。
     */
    fun isSafe(entryPath: String): Boolean {
        if (entryPath.startsWith('/') || entryPath.startsWith('\\')) return false
        if (entryPath.indexOf('\u0000') >= 0) return false
        return entryPath.split('/', '\\').none { it == ".." }
    }
}
