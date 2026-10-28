package ziyao.huang.unzip.archive.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class ExtractionRunnerTest {

    private class FakeSink : ExtractSink {
        val createdFiles = mutableListOf<String>()
        val createdDirs = mutableListOf<String>()
        val deletedPartials = mutableListOf<String>()

        override fun createFile(path: String): OutputStream {
            createdFiles += path
            return ByteArrayOutputStream()
        }

        override fun createDirectory(path: String) {
            createdDirs += path
        }

        override fun deletePartial(path: String) {
            deletedPartials += path
        }

        override fun exists(path: String): Boolean = path in createdFiles || path in createdDirs
    }

    private fun entry(path: String, size: Long = 10L) = ArchiveEntry(
        path = path,
        isDir = false,
        size = size,
        packedSize = size / 2,
        encrypted = false,
        mtime = null,
    )

    private fun threeEntries() = listOf(entry("a.txt"), entry("b.txt", 20L), entry("c.txt"))

    @Test
    fun cancelDeletesPartial() = runBlocking {
        val sink = FakeSink()
        val runner = ExtractionRunner(sink)
        val entries = threeEntries()

        val thrown = runCatching {
            runner.run(
                entries,
                onProgress = {},
                write = { e ->
                    if (e.path == "b.txt") throw CancellationException("cancel")
                },
            )
        }.exceptionOrNull()

        assertTrue("expected a CancellationException, got $thrown", thrown is CancellationException)
        val sealed = (thrown as ExtractionCancelledException).result
        assertTrue(sealed.cancelled)
        assertEquals(listOf("a.txt"), sealed.succeeded.map { it.path })
        assertEquals(listOf("b.txt", "c.txt"), sealed.skipped.map { it.path })
        assertTrue(sealed.failed.isEmpty())
        assertEquals(listOf("b.txt"), sink.deletedPartials)
    }

    @Test
    fun failureKeepsOthers() = runBlocking {
        val sink = FakeSink()
        val runner = ExtractionRunner(sink)
        val entries = threeEntries()

        val result = runner.run(
            entries,
            onProgress = {},
            write = { e ->
                if (e.path == "a.txt") throw IOException("boom")
            },
        )

        assertFalse(result.cancelled)
        assertEquals(listOf("a.txt"), result.failed.map { it.path })
        assertEquals(listOf("b.txt", "c.txt"), result.succeeded.map { it.path })
        assertTrue(result.skipped.isEmpty())
        assertEquals(listOf("a.txt"), sink.deletedPartials)
    }

    @Test
    fun progressMonotonic() = runBlocking {
        val sink = FakeSink()
        val runner = ExtractionRunner(sink)
        val entries = threeEntries()
        val progress = mutableListOf<ExtractProgress>()

        val result = runner.run(entries, onProgress = { progress += it }, write = {})

        assertFalse(result.cancelled)
        assertTrue(progress.isNotEmpty())
        val doneCounts = progress.map { it.doneCount }
        assertEquals("doneCount must be monotonic", doneCounts, doneCounts.sorted())
        assertEquals(entries.size, doneCounts.max())
        assertEquals(entries.size, progress.last().totalCount)
        assertEquals(entries.sumOf { it.size }, progress.first().totalBytes)
    }
}
