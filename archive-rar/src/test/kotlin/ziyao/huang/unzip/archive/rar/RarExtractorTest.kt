package ziyao.huang.unzip.archive.rar

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ziyao.huang.unzip.archive.core.ArchiveFormatException
import ziyao.huang.unzip.archive.core.ArchiveSource
import ziyao.huang.unzip.archive.core.ExtractProgress
import ziyao.huang.unzip.archive.core.ExtractSink
import ziyao.huang.unzip.archive.core.PasswordCancelledException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Task 5 用例：native 层以 [FakeRarNative] 为接缝（真 JNI 在 Task 6）。
 */
class RarExtractorTest {

    private lateinit var root: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("unseal-rar-test")
    }

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    // ---------- fixtures ----------

    private class FakeSink : ExtractSink {
        val files = LinkedHashMap<String, ByteArrayOutputStream>()
        val dirs = LinkedHashSet<String>()
        val deletedPartials = mutableListOf<String>()

        override fun createFile(path: String): OutputStream =
            ByteArrayOutputStream().also { files[path] = it }

        override fun createDirectory(path: String) {
            dirs += path
        }

        override fun deletePartial(path: String) {
            deletedPartials += path
            files.remove(path)
        }

        override fun exists(path: String): Boolean = path in files || path in dirs

        fun bytes(path: String): ByteArray = files.getValue(path).toByteArray()
    }

    /** ArchiveSource 在 rar 路径上只被 realFiles 接缝消费，流内容无关紧要。 */
    private val source = object : ArchiveSource {
        override fun open(): InputStream = ByteArrayInputStream(ByteArray(0))
        override val size: Long get() = 0L
    }

    /** realFiles 接缝在测试里给假路径；用例顺带断言它原样流入 native 调用。 */
    private val realFiles = listOf("/cache/multi.part1.rar", "/cache/multi.part2.rar")

    private fun extractor(native: FakeRarNative): RarExtractor =
        RarExtractor(native, realFiles = { realFiles })

    // ---------- 1. headerEncryptedPromptsBeforeListing ----------

    @Test
    fun headerEncryptedPromptsBeforeListing() = runBlocking {
        val native = FakeRarNative(
            info = NativeRarInfo(isHeaderEncrypted = true, volumes = realFiles),
            entries = listOf(NativeRarEntry("a.txt", false, 10L, 12L, true, 1700000000L)),
        )
        val flags = mutableListOf<Boolean>()

        assertThrows(PasswordCancelledException::class.java) {
            runBlocking { extractor(native).listEntries(source) { flags += it; null } }
        }

        assertEquals(listOf(true), flags)
        assertTrue("用户取消时不得 list 任何条目", native.listCalls.isEmpty())
    }

    // ---------- 2. headerEncryptedWrongPasswordRetries（list 阶段重试） ----------

    @Test
    fun headerEncryptedWrongPasswordRetries() = runBlocking {
        val native = FakeRarNative(
            info = NativeRarInfo(isHeaderEncrypted = true, volumes = realFiles),
            entries = listOf(NativeRarEntry("a.txt", false, 10L, 12L, false, null)),
            password = "pw1234".toCharArray(),
        )
        val answers = ArrayDeque(listOf("nope", "pw1234"))
        val flags = mutableListOf<Boolean>()

        val entries = extractor(native).listEntries(source) { first ->
            flags += first
            answers.removeFirst().toCharArray()
        }

        assertEquals(listOf(true, false), flags)
        assertEquals(listOf("a.txt"), entries.map { it.path })
        assertArrayEquals("pw1234".toCharArray(), native.listCalls.last())
    }

    // ---------- 3. entryMapping ----------

    @Test
    fun entryMapping() = runBlocking {
        val native = FakeRarNative(
            entries = listOf(
                NativeRarEntry("docs\\报告.txt", false, 2048L, 512L, true, 1700000000L),
                NativeRarEntry("sub/", true, 0L, 0L, false, null),
            ),
        )
        val flags = mutableListOf<Boolean>()
        val entries = extractor(native).listEntries(source) { flags += it; null }

        assertEquals(2, entries.size)
        val file = entries[0]
        assertEquals("docs/报告.txt", file.path) // '\' 归一为 '/'
        assertEquals(2048L, file.size)
        assertEquals(512L, file.packedSize)
        assertTrue(file.encrypted)
        assertEquals(1700000000000L, file.mtime) // 秒 → 毫秒
        val dir = entries[1]
        assertTrue(dir.isDir)
        assertEquals("sub", dir.path) // 尾部 '/' 去除
        assertNull(dir.mtime)
        assertEquals("非 header 加密的包列清单不得弹密码", 0, flags.size)
    }

    // ---------- 4. plainExtractRoundTrip（临时目录 → sink 搬运全链路） ----------

    @Test
    fun plainExtractRoundTrip() = runBlocking {
        val native = FakeRarNative(
            entries = listOf(
                NativeRarEntry("sub/", true, 0L, 0L, false, null),
                NativeRarEntry("sub/a.txt", false, 5L, 5L, false, null),
                NativeRarEntry("b.bin", false, 4L, 4L, false, null),
            ),
            contentByIndex = mapOf(1 to "alpha".toByteArray(), 2 to "beta".toByteArray()),
        )
        val entries = extractor(native).listEntries(source) { null }
        val sink = FakeSink()
        val progress = mutableListOf<ExtractProgress>()

        val result = extractor(native).extract(source, sink, entries, { null }) { progress += it }

        assertFalse(result.cancelled)
        assertTrue(result.failed.isEmpty())
        assertEquals(entries.map { it.path }, result.succeeded.map { it.path })
        assertEquals("alpha", String(sink.bytes("sub/a.txt"), Charsets.UTF_8))
        assertEquals("beta", String(sink.bytes("b.bin"), Charsets.UTF_8))
        assertEquals(setOf("sub"), sink.dirs)
        // 逐条目 native.extract：每次 wanted 只含一个索引，且只对文件条目调用
        assertEquals(listOf(listOf(1), listOf(2)), native.extractCalls.map { it.wanted })
        assertEquals(realFiles, native.extractCalls.first().paths)
        assertTrue("runner 应有进度上报", progress.isNotEmpty())
    }

    // ---------- 5. wrongPasswordRetries（extract 阶段） ----------

    @Test
    fun wrongPasswordRetries() = runBlocking {
        val native = FakeRarNative(
            entries = listOf(NativeRarEntry("secret.txt", false, 11L, 11L, true, null)),
            password = "correct-horse".toCharArray(),
            contentByIndex = mapOf(0 to "top-secret!!".toByteArray()),
        )
        val entries = extractor(native).listEntries(source) { null }
        val answers = ArrayDeque(listOf("wrong-guess", "correct-horse"))
        val flags = mutableListOf<Boolean>()
        val sink = FakeSink()

        val result = extractor(native).extract(
            source,
            sink,
            entries,
            { flags += it; answers.removeFirst().toCharArray() },
        ) { }

        assertFalse(result.cancelled)
        assertEquals(listOf("secret.txt"), result.succeeded.map { it.path })
        assertEquals("top-secret!!", String(sink.bytes("secret.txt"), Charsets.UTF_8))
        assertEquals(listOf(true, false), flags)
        assertArrayEquals("correct-horse".toCharArray(), native.extractCalls.last().password)
    }

    // ---------- 6. promptCancelMidLoopSkipsRest（中途取消走 runner 封口） ----------

    @Test
    fun promptCancelMidLoopSkipsRest() = runBlocking {
        val native = FakeRarNative(
            entries = listOf(
                NativeRarEntry("a.txt", false, 1L, 1L, true, null),
                NativeRarEntry("b.txt", false, 1L, 1L, true, null),
            ),
            password = "pw".toCharArray(),
            contentByIndex = mapOf(0 to "A".toByteArray(), 1 to "B".toByteArray()),
        )
        val entries = extractor(native).listEntries(source) { null }
        val sink = FakeSink()

        val result = extractor(native).extract(source, sink, entries, { null }) { }

        assertTrue(result.cancelled)
        assertTrue(result.succeeded.isEmpty())
        assertEquals(entries.map { it.path }, result.skipped.map { it.path })
        assertTrue("取消时不得有 sink 写入", sink.files.isEmpty())
    }

    // ---------- 7. extractHeaderEncryptedCancelThrows（循环前取消 → PasswordCancelledException） ----------

    @Test
    fun extractHeaderEncryptedCancelThrows() {
        val native = FakeRarNative(
            info = NativeRarInfo(isHeaderEncrypted = true, volumes = realFiles),
            entries = listOf(NativeRarEntry("a.txt", false, 1L, 1L, true, null)),
        )
        assertThrows(PasswordCancelledException::class.java) {
            runBlocking {
                extractor(native).extract(source, FakeSink(), emptyList(), { null }) { }
            }
        }
        assertTrue(native.extractCalls.isEmpty())
    }

    // ---------- 8. corruptArchiveSurfacesAsFormat ----------

    @Test
    fun corruptArchiveSurfacesAsFormat() {
        val native = FakeRarNative(
            openError = UnrarException("header garbage", UnrarException.Reason.BAD_ARCHIVE),
        )
        assertThrows(ArchiveFormatException::class.java) {
            runBlocking { extractor(native).listEntries(source) { null } }
        }
    }
}
