package ziyao.huang.unzip.archive.rar

/**
 * [RarNative] 的真身：libunrar_jni.so（vendored unrar 源码 + unrar_jni.cpp 胶水，
 * 见 src/main/cpp）。
 *
 * [RarNative] 无句柄语义：每次调用以 [paths] 首卷（调用方保证按序排列）重开包，
 * native 侧枚举顺序在 open/list/extract 间确定一致（分卷续段在 extract 枚举中
 * 跳过且不计数，与 list 的合并视图对齐）。密码 CharArray 在 JNI 边界当场拷贝为
 * C 字符串、用后即弃，绝不跨调用持有引用。
 */
class RarNativeJni : RarNative {

    init {
        System.loadLibrary("unrar_jni")
    }

    override fun open(paths: List<String>): NativeRarInfo =
        NativeRarInfo(
            isHeaderEncrypted = nOpenHeaderEncrypted(paths.first()),
            volumes = paths,
        )

    override fun list(paths: List<String>, password: CharArray?): List<NativeRarEntry> =
        nList(paths.first(), password).toList()

    override fun extract(
        paths: List<String>,
        outDir: String,
        password: CharArray?,
        wanted: IntArray,
        onFileDone: (Int) -> Unit,
    ) {
        nExtract(paths.first(), outDir, password, wanted, onFileDone)
    }

    private external fun nOpenHeaderEncrypted(path: String): Boolean

    private external fun nList(path: String, password: CharArray?): Array<NativeRarEntry>

    private external fun nExtract(
        path: String,
        outDir: String,
        password: CharArray?,
        wanted: IntArray,
        onFileDone: (Int) -> Unit,
    )
}
