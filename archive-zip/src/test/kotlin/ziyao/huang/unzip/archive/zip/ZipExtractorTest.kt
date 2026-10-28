package ziyao.huang.unzip.archive.zip

import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ziyao.huang.unzip.archive.core.ArchiveFormatException
import ziyao.huang.unzip.archive.core.ArchiveSource
import ziyao.huang.unzip.archive.core.ExtractSink
import ziyao.huang.unzip.archive.core.PasswordPrompt
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Task 4 七个用例：fixture 全部在测试内现做，不提交二进制。
 */
class ZipExtractorTest {

    private lateinit var root: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("unseal-zip-test")
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

    private fun sourceOf(file: File) = object : ArchiveSource {
        override fun open(): InputStream = file.inputStream().buffered()
        override val size: Long get() = file.length()
    }

    /** 用 zip4j 流式输出直写条目，条目名与顺序完全可控。 */
    private fun plainZip(
        file: File,
        entries: Map<String, String>,
        charset: Charset = Charsets.UTF_8,
    ) {
        FileOutputStream(file).use { raw ->
            net.lingala.zip4j.io.outputstream.ZipOutputStream(raw.buffered(), charset).use { zos ->
                for ((name, content) in entries) {
                    val params = ZipParameters().apply { fileNameInZip = name }
                    zos.putNextEntry(params)
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
        }
    }

    /** 加密包：zip4j addFiles 以文件名作条目名，所以单条目直接按名字落盘。 */
    private fun encryptedZip(
        file: File,
        name: String,
        content: String,
        method: EncryptionMethod,
        password: String,
    ) {
        val srcDir = root.resolve("enc-src-${file.nameWithoutExtension}").toFile().apply { mkdirs() }
        val src = File(srcDir, name).apply { writeText(content, Charsets.UTF_8) }
        val params = ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = method
            if (method == EncryptionMethod.AES) {
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
        val zf = net.lingala.zip4j.ZipFile(file)
        zf.setPassword(password.toCharArray())
        zf.addFiles(listOf(src), params)
    }

    private fun promptAnswering(answer: () -> String?): Pair<PasswordPrompt, MutableList<Boolean>> {
        val flags = mutableListOf<Boolean>()
        val prompt: PasswordPrompt = { firstAttempt ->
            flags += firstAttempt
            answer()?.toCharArray()
        }
        return prompt to flags
    }

    // ---------- 1. plainListAndExtract ----------

    @Test
    fun plainListAndExtract() = runBlocking {
        val zipFile = root.resolve("plain.zip").toFile()
        val contents = mapOf(
            "a.txt" to "alpha",
            "sub/b.txt" to "beta-beta",
            "sub/c.txt" to "gamma",
        )
        plainZip(zipFile, contents)

        val extractor = ZipExtractor()
        val entries = extractor.listEntries(sourceOf(zipFile)) { null }

        assertEquals(contents.keys, entries.map { it.path }.toSet())
        assertTrue(entries.all { !it.encrypted })
        assertTrue(entries.all { !it.isDir })
        assertEquals(contents.values.map { it.length.toLong() }.sum(), entries.sumOf { it.size })

        val sink = FakeSink()
        val result = extractor.extract(sourceOf(zipFile), sink, entries, { null }) { }

        assertFalse(result.cancelled)
        assertTrue(result.failed.isEmpty())
        assertEquals(entries.map { it.path }.toSet(), result.succeeded.map { it.path }.toSet())
        for ((name, content) in contents) {
            assertArrayEquals(content.toByteArray(Charsets.UTF_8), sink.bytes(name))
        }
    }

    // ---------- 2. aesAndZipCryptoPassword ----------

    @Test
    fun aesAndZipCryptoPassword() = runBlocking {
        val password = "pw1234"
        val contents = mapOf("secret.txt" to "top-secret-bytes")

        for (method in listOf(EncryptionMethod.AES, EncryptionMethod.ZIP_STANDARD)) {
            val zipFile = root.resolve("enc-${method.name}.zip").toFile()
            encryptedZip(zipFile, "secret.txt", contents.getValue("secret.txt"), method, password)

            val extractor = ZipExtractor()
            val entries = extractor.listEntries(sourceOf(zipFile)) { null }
            assertEquals(1, entries.size)
            assertTrue("header must report encrypted for $method", entries[0].encrypted)

            val (prompt, flags) = promptAnswering { password }
            val sink = FakeSink()
            val result = extractor.extract(sourceOf(zipFile), sink, entries, prompt) { }

            assertFalse(result.cancelled)
            assertEquals(listOf("secret.txt"), result.succeeded.map { it.path })
            assertArrayEquals("top-secret-bytes".toByteArray(Charsets.UTF_8), sink.bytes("secret.txt"))
            assertTrue("prompt should have been asked for $method", flags.isNotEmpty())
        }
    }

    // ---------- 3. wrongPasswordRetries ----------

    @Test
    fun wrongPasswordRetries() = runBlocking {
        val zipFile = root.resolve("wrong-pw.zip").toFile()
        // AES 的密码校验位是确定性的（PBKDF2 + 2 字节验证），错密码 100% 抛 WRONG_PASSWORD
        encryptedZip(zipFile, "retry.txt", "retry-payload", EncryptionMethod.AES, "correct-horse")

        val extractor = ZipExtractor()
        val entries = extractor.listEntries(sourceOf(zipFile)) { null }
        val answers = ArrayDeque(listOf("wrong-guess", "correct-horse"))
        val (prompt, flags) = promptAnswering { answers.removeFirst() }

        val sink = FakeSink()
        val result = extractor.extract(sourceOf(zipFile), sink, entries, prompt) { }

        assertFalse(result.cancelled)
        assertEquals(listOf("retry.txt"), result.succeeded.map { it.path })
        assertArrayEquals("retry-payload".toByteArray(Charsets.UTF_8), sink.bytes("retry.txt"))
        assertEquals(listOf(true, false), flags)
    }

    // ---------- 4. promptCancelSkipsAll ----------

    @Test
    fun promptCancelSkipsAll() = runBlocking {
        val zipFile = root.resolve("cancel.zip").toFile()
        encryptedZip(zipFile, "locked.txt", "locked", EncryptionMethod.AES, "pw1234")

        val extractor = ZipExtractor()
        val entries = extractor.listEntries(sourceOf(zipFile)) { null }
        val flags = mutableListOf<Boolean>()
        val prompt: PasswordPrompt = { _ -> flags += true; null }

        val sink = FakeSink()
        val result = extractor.extract(sourceOf(zipFile), sink, entries, prompt) { }

        assertTrue(result.cancelled)
        assertTrue(result.succeeded.isEmpty())
        assertTrue(result.failed.isEmpty())
        assertEquals(entries.map { it.path }, result.skipped.map { it.path })
        assertTrue("no file may be written when prompt is cancelled", sink.files.isEmpty())
        assertTrue("prompt should have been asked", flags.isNotEmpty())
    }

    // ---------- 5. gbkNamesAutoDecoded ----------

    @Test
    fun gbkNamesAutoDecoded() = runBlocking {
        val zipFile = root.resolve("gbk.zip").toFile()
        val gbk = Charset.forName("GBK")
        // zip4j 以 GBK 写入「财务报表.txt」，非 UTF-8 charset 不打 EFS 标志
        plainZip(zipFile, mapOf("财务报表.txt" to "营收数据"), charset = gbk)

        val extractor = ZipExtractor()
        val entries = extractor.listEntries(sourceOf(zipFile)) { null }

        assertEquals(listOf("财务报表.txt"), entries.map { it.path })
    }

    // ---------- 6. filterUnsafeEntries ----------

    @Test
    fun filterUnsafeEntries() = runBlocking {
        val zipFile = root.resolve("evil.zip").toFile()
        FileOutputStream(zipFile).use { raw ->
            ZipOutputStream(raw.buffered()).use { zos ->
                for (name in listOf("../evil.txt", "/abs.txt")) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write("malicious".toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
        }

        val extractor = ZipExtractor()
        val entries = extractor.listEntries(sourceOf(zipFile)) { null }
        assertEquals(listOf("../evil.txt", "/abs.txt"), entries.map { it.path })

        val sink = FakeSink()
        val result = extractor.extract(sourceOf(zipFile), sink, entries, { null }) { }

        assertFalse(result.cancelled)
        assertEquals(entries.map { it.path }, result.failed.map { it.path })
        assertTrue(result.succeeded.isEmpty())
        for (unsafe in listOf("../evil.txt", "/abs.txt")) {
            assertFalse("unsafe path must never be created", sink.exists(unsafe))
        }
    }

    // ---------- 7. corruptZipFails ----------

    @Test
    fun corruptZipFails() {
        val zipFile = root.resolve("corrupt.zip").toFile()
        plainZip(zipFile, mapOf("a.txt" to "alpha"))
        val truncated = zipFile.readBytes().copyOfRange(0, zipFile.length().toInt() / 2)
        zipFile.writeBytes(truncated)

        val extractor = ZipExtractor()
        assertThrows(
            "truncated zip must surface as ArchiveFormatException",
            ArchiveFormatException::class.java,
        ) {
            runBlocking { extractor.listEntries(sourceOf(zipFile)) { null } }
        }
    }
}
