package ziyao.huang.unzip.archive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ArchiveRegistryTest {

    private class FakeExtractor : ArchiveExtractor {
        override suspend fun listEntries(
            source: ArchiveSource,
            password: PasswordPrompt,
        ): List<ArchiveEntry> = emptyList()

        override suspend fun extract(
            source: ArchiveSource,
            sink: ExtractSink,
            entries: List<ArchiveEntry>,
            password: PasswordPrompt,
            onProgress: (ExtractProgress) -> Unit,
        ): ExtractResult = ExtractResult(emptyList(), emptyList(), emptyList(), cancelled = false)
    }

    @Test
    fun registeredExtractorIsReturned() {
        val extractor = FakeExtractor()
        val registry = ArchiveRegistry(mapOf(ArchiveFormat.ZIP to extractor))
        assertSame(extractor, registry.extractorFor(ArchiveFormat.ZIP))
    }

    @Test(expected = UnsupportedOperationException::class)
    fun unregisteredFormatThrows() {
        val registry = ArchiveRegistry(mapOf(ArchiveFormat.ZIP to FakeExtractor()))
        registry.extractorFor(ArchiveFormat.RAR)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun sevenZipThrowsUnsupported() {
        val registry = ArchiveRegistry(
            mapOf(
                ArchiveFormat.ZIP to FakeExtractor(),
                ArchiveFormat.RAR to FakeExtractor(),
            ),
        )
        registry.extractorFor(ArchiveFormat.SEVEN_ZIP)
    }

    @Test
    fun sniffFormatDelegatesToHeaderSniffer() {
        val registry = ArchiveRegistry(mapOf(ArchiveFormat.ZIP to FakeExtractor()))
        assertEquals(ArchiveFormat.ZIP, registry.sniffFormat(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertNull(registry.sniffFormat("hello".toByteArray()))
    }
}
