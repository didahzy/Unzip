package ziyao.huang.unzip.archive.rar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ziyao.huang.unzip.archive.core.ArchiveEntry
import ziyao.huang.unzip.archive.core.ArchiveSource
import ziyao.huang.unzip.archive.core.ExtractSink
import ziyao.huang.unzip.archive.core.ExtractResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * 真机/模拟器上的端到端验证：RarNativeJni（vendored unrar JNI）+ RarExtractor
 * 对真实 RAR fixture（scripts/gen-rar-fixtures.sh 产物，随测试 APK 以 assets 打包）。
 * 文本内容的期望值与生成脚本逐字节共享（约定行尾 \n）。
 */
@RunWith(AndroidJUnit4::class)
class RarExtractorConnectedTest {

    private companion object {
        const val PW = "pw1234"

        // 与 scripts/gen-rar-fixtures.sh 中的 printf 内容逐字一致
        const val ALPHA = "UNSEAL plain rar5 alpha content\n"
        const val BETA = "UNSEAL plain rar5 beta, plain second line.\n"
        const val NESTED_CN = "UNSEAL 中文文档内容：嵌套目录条目。\n"
        const val SECRET = "UNSEAL encrypted secret payload\n"
        const val SECRET_CN = "UNSEAL 加密条目：密码正确才能读到。\n"
        const val MULTI_FIRST = "UNSEAL multi-volume first volume text\n"
        const val R4_LEGACY = "UNSEAL plain rar4 legacy content\n"
        const val R4_CN = "UNSEAL 旧版格式里的中文文件。\n"
        const val CN_1 = "第一篇：中文文件名与目录。\n"
        const val CN_2 = "第二篇：嵌套子目录中的文件。\n"
        const val CN_README = "README with Chinese neighbours.\n"
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(instrumentation.targetContext.cacheDir, "rar-fixtures")
            .apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ---------- helpers ----------

    /** 把 fixture 从测试 APK assets 复制到 cacheDir，返回文件列表。 */
    private fun stage(vararg names: String): List<String> = names.map { name ->
        val target = File(dir, name)
        instrumentation.context.assets.open("fixtures/$name").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        target.absolutePath
    }

    private fun fixtureAssetText(name: String): String =
        instrumentation.context.assets.open("fixtures/$name")
            .use { it.readBytes().decodeToString() }.trim()

    private fun extractor(paths: List<String>): RarExtractor =
        RarExtractor(RarNativeJni(), realFiles = { paths }, workDir = dir)

    /** ArchiveSource 在 rar 路径上只被 realFiles 接缝消费，流内容无关紧要。 */
    private fun source() = object : ArchiveSource {
        override fun open(): InputStream = ByteArrayInputStream(ByteArray(0))
        override val size: Long get() = 0L
    }

    private class MemSink : ExtractSink {
        val files = mutableMapOf<String, ByteArray>()
        val dirs = mutableSetOf<String>()
        val partials = mutableListOf<String>()

        override fun createFile(path: String): OutputStream = object : OutputStream() {
            private val buf = ByteArrayOutputStream()
            override fun write(b: Int) { buf.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { buf.write(b, off, len) }
            override fun close() { files[path] = buf.toByteArray() }
        }

        override fun createDirectory(path: String) { dirs += path }
        override fun deletePartial(path: String) { partials += path }
        override fun exists(path: String): Boolean = path in files || path in dirs

        fun text(path: String): String = files.getValue(path).decodeToString()
    }

    private data class Extraction(val result: ExtractResult, val sink: MemSink)

    private fun extractAll(
        paths: List<String>,
        entries: List<ArchiveEntry>,
        prompt: suspend (Boolean) -> CharArray? = { null },
    ): Extraction {
        val sink = MemSink()
        val result = runBlocking {
            extractor(paths).extract(source(), sink, entries, prompt) { }
        }
        return Extraction(result, sink)
    }

    // ---------- 1. header 加密：列出前必须先要到密码 ----------

    @Test
    fun headerEncryptedPromptsBeforeListing() {
        val paths = stage("header-pw-rar5.rar")
        val flags = mutableListOf<Boolean>()
        val entries = runBlocking {
            extractor(paths).listEntries(source()) { firstAttempt ->
                flags += firstAttempt
                PW.toCharArray()
            }
        }
        // 无密码技术上无法列目：恰好一次 prompt 且是首次尝试
        assertEquals(listOf(true), flags)
        val secret = entries.firstOrNull { it.path == "secret.txt" }
        assertNotNull("header-encrypted rar should list secret.txt after password", secret)
        assertTrue("header encryption marks entries encrypted", secret!!.encrypted)
        assertEquals(SECRET.length.toLong(), secret.size)
    }

    // ---------- 2. 明文 RAR5：list + extract 字节一致（含 0 字节条目） ----------

    @Test
    fun plainRar5RoundTrip() {
        val paths = stage("plain-rar5.rar")
        val entries = runBlocking { extractor(paths).listEntries(source()) { null } }
        val byPath = entries.associateBy { it.path }
        assertEquals(32L, byPath.getValue("alpha.txt").size)
        assertEquals(0L, byPath.getValue("empty.txt").size)
        // 条目 size 是字节数（UTF-8），不是字符数
        assertEquals(NESTED_CN.toByteArray(Charsets.UTF_8).size.toLong(), byPath.getValue("docs/nested/说明.txt").size)
        assertTrue("directory entries listed", byPath.getValue("docs/nested").isDir)

        val (result, sink) = extractAll(paths, entries)
        assertTrue("no failures: ${result.failed}", result.failed.isEmpty())
        assertTrue("not cancelled", !result.cancelled)
        assertEquals(ALPHA, sink.text("alpha.txt"))
        assertEquals(BETA, sink.text("docs/beta.txt"))
        assertEquals(NESTED_CN, sink.text("docs/nested/说明.txt"))
        // 0 字节条目也必须产出文件（native 实测落盘）
        assertArrayEquals(ByteArray(0), sink.files.getValue("empty.txt"))
        assertTrue("nested dirs reported", "docs/nested" in sink.dirs)
    }

    // ---------- 3. 错密码 → prompt 重试后成功（RAR5 文件加密） ----------

    @Test
    fun wrongPassword() {
        val paths = stage("pw-rar5.rar")
        val flags = mutableListOf<Boolean>()
        val answers = ArrayDeque(listOf("totally-wrong", PW))
        val entries = runBlocking { extractor(paths).listEntries(source()) { null } }
        assertEquals(2, entries.count { !it.isDir && it.encrypted })

        val (result, sink) = extractAll(paths, entries) { firstAttempt ->
            flags += firstAttempt
            answers.removeFirstOrNull()?.toCharArray()
        }
        assertEquals(listOf(true, false), flags)
        assertEquals("both files extracted", 2, result.succeeded.count { !it.isDir })
        assertEquals(SECRET, sink.text("secret.txt"))
        assertEquals(SECRET_CN, sink.text("secret dir/机密.txt"))
    }

    // ---------- 4. 分卷：从 part1 起解出全部 ----------

    @Test
    fun multiVolume() {
        val partPaths = stage(
            "multi-rar5.part1.rar",
            "multi-rar5.part2.rar",
            "multi-rar5.part3.rar",
        )
        // 分卷发现（同目录、part 号数值升序）
        val paths = MultiVolume.siblings(partPaths[0])
        assertEquals(3, paths.size)

        val entries = runBlocking { extractor(paths).listEntries(source()) { null } }
        val byPath = entries.associateBy { it.path }
        // LIST 视图：跨卷文件合并为单条目、原始终长
        assertEquals(8100L, byPath.getValue("payload.bin").size)

        val (result, sink) = extractAll(paths, entries)
        assertTrue("no failures: ${result.failed}", result.failed.isEmpty())
        assertEquals(MULTI_FIRST, sink.text("first.txt"))
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(sink.files.getValue("payload.bin"))
            .joinToString("") { "%02x".format(it) }
        assertEquals(fixtureAssetText("multi-rar5.sha256"), sha)
    }

    // ---------- 5. 中文名称与嵌套目录 ----------

    @Test
    fun chineseNames() {
        val paths = stage("中文-rar5.rar")
        val entries = runBlocking { extractor(paths).listEntries(source()) { null } }
        val byPath = entries.associateBy { it.path }
        assertEquals(40L, byPath.getValue("文档/第一篇.txt").size)
        assertEquals(43L, byPath.getValue("文档/子目录/第二篇.txt").size)
        assertTrue(byPath.getValue("空目录").isDir)

        val (result, sink) = extractAll(paths, entries)
        assertTrue("no failures: ${result.failed}", result.failed.isEmpty())
        assertEquals(CN_1, sink.text("文档/第一篇.txt"))
        assertEquals(CN_2, sink.text("文档/子目录/第二篇.txt"))
        assertEquals(CN_README, sink.text("说明.md"))
        assertTrue("nested dir created", "文档/子目录" in sink.dirs)
        assertTrue("empty dir created", "空目录" in sink.dirs)
    }

    // ---------- 补充：RAR4 明文回环 ----------

    @Test
    fun plainRar4RoundTrip() {
        val paths = stage("plain-rar4.rar")
        val entries = runBlocking { extractor(paths).listEntries(source()) { null } }
        val byPath = entries.associateBy { it.path }
        assertEquals(33L, byPath.getValue("legacy.txt").size)
        assertTrue(byPath.getValue("旧目录").isDir)

        val (result, sink) = extractAll(paths, entries)
        assertTrue("no failures: ${result.failed}", result.failed.isEmpty())
        assertEquals(R4_LEGACY, sink.text("legacy.txt"))
        assertEquals(R4_CN, sink.text("旧目录/文档.txt"))
    }

    // ---------- 补充：RAR4 错密码（unrar 对 RAR4 无密码校验字段，CRC 失败须转译为错密码） ----------

    @Test
    fun pwRar4WrongPassword() {
        val paths = stage("pw-rar4.rar")
        val flags = mutableListOf<Boolean>()
        val answers = ArrayDeque(listOf("totally-wrong", PW))
        val entries = runBlocking { extractor(paths).listEntries(source()) { null } }

        val (result, sink) = extractAll(paths, entries) { firstAttempt ->
            flags += firstAttempt
            answers.removeFirstOrNull()?.toCharArray()
        }
        assertEquals(listOf(true, false), flags)
        assertEquals("both files extracted", 2, result.succeeded.count { !it.isDir })
        assertEquals(SECRET, sink.text("secret.txt"))
        assertEquals(SECRET_CN, sink.text("secret dir/机密.txt"))
    }
}
