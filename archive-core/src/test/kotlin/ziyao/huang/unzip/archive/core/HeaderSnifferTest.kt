package ziyao.huang.unzip.archive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeaderSnifferTest {

    @Test
    fun localFileHeaderIsZip() {
        assertEquals(
            ArchiveFormat.ZIP,
            HeaderSniffer.sniff(byteArrayOf(0x50, 0x4B, 0x03, 0x04)),
        )
    }

    @Test
    fun emptyArchiveEocdIsZip() {
        assertEquals(
            ArchiveFormat.ZIP,
            HeaderSniffer.sniff(byteArrayOf(0x50, 0x4B, 0x05, 0x06)),
        )
    }

    @Test
    fun rar4SignatureIsRar() {
        assertEquals(
            ArchiveFormat.RAR,
            HeaderSniffer.sniff(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)),
        )
    }

    @Test
    fun rar5SignatureIsRar() {
        assertEquals(
            ArchiveFormat.RAR,
            HeaderSniffer.sniff(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)),
        )
    }

    @Test
    fun sevenZipSignatureIsSevenZip() {
        assertEquals(
            ArchiveFormat.SEVEN_ZIP,
            HeaderSniffer.sniff(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)),
        )
    }

    @Test
    fun truncatedHeaderIsNull() {
        assertNull(HeaderSniffer.sniff(byteArrayOf(0x50, 0x4B, 0x03)))
    }

    @Test
    fun unknownBytesAreNull() {
        assertNull(HeaderSniffer.sniff("hello".toByteArray()))
    }
}
