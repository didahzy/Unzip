package ziyao.huang.unzip.archive.rar

/**
 * native 层列出的单个 RAR 条目。[name] 为压缩包内原始路径（RAR 可能用 '\' 分隔，
 * 调用方负责归一）；[mtimeEpochSec] 为秒级时间戳（unrar 原生粒度），未知时为 null。
 */
data class NativeRarEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val packedSize: Long,
    val isEncrypted: Boolean,
    val mtimeEpochSec: Long?,
)

/** native.open 的结果；[volumes] 为参与解压的全部卷（绝对路径，按解压顺序）。 */
data class NativeRarInfo(
    val isHeaderEncrypted: Boolean,
    val volumes: List<String>,
)

/**
 * unrar 操作失败。[reason] 供调用方分流：WRONG_PASSWORD 走重问密码，
 * BAD_ARCHIVE 视为畸形包（转译 ArchiveFormatException），其余原样上抛。
 */
class UnrarException(message: String, val reason: Reason) : Exception(message) {
    enum class Reason { WRONG_PASSWORD, BAD_ARCHIVE, IO }
}

/**
 * unrar JNI 接缝；真身 RarNativeJni 在 Task 6，单元测试用 Fake。
 * 全部方法为阻塞调用，调用方负责 runInterruptible(Dispatchers.IO) 包裹。
 */
interface RarNative {

    /** 打开压缩包（[paths] 为全部卷的绝对路径，按序）；只读头，不列条目。 */
    fun open(paths: List<String>): NativeRarInfo

    /** 列出全部条目；加密文件头的包必须先拿到正确密码，错密码抛 [UnrarException]。 */
    fun list(paths: List<String>, password: CharArray?): List<NativeRarEntry>

    /**
     * 把 [wanted] 索引（对应 [list] 顺序）的条目解到真实目录 [outDir]；
     * 每解完一个文件回调 [onFileDone]。错密码/坏包/IO 失败抛 [UnrarException]。
     */
    fun extract(
        paths: List<String>,
        outDir: String,
        password: CharArray?,
        wanted: IntArray,
        onFileDone: (Int) -> Unit,
    )
}
