package ziyao.huang.unzip.archive.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafePathsTest {

    @Test
    fun normalPathIsSafe() {
        assertTrue(SafePaths.isSafe("a/b/c.txt"))
    }

    @Test
    fun chineseFileNameIsSafe() {
        assertTrue(SafePaths.isSafe("中文名.txt"))
    }

    @Test
    fun parentSegmentIsUnsafe() {
        assertFalse(SafePaths.isSafe("../secret"))
    }

    @Test
    fun dotDotAloneIsUnsafe() {
        assertFalse(SafePaths.isSafe(".."))
    }

    @Test
    fun mixedDotDotSegmentsAreUnsafe() {
        assertFalse(SafePaths.isSafe("a/../.."))
    }

    @Test
    fun leadingSlashIsUnsafe() {
        assertFalse(SafePaths.isSafe("/etc/passwd"))
    }

    @Test
    fun leadingBackslashIsUnsafe() {
        assertFalse(SafePaths.isSafe("\\windows\\system32"))
    }

    @Test
    fun nulByteIsUnsafe() {
        assertFalse(SafePaths.isSafe("bad\u0000path"))
    }

    @Test
    fun backslashTraversalIsUnsafe() {
        // '\' 与 '/' 同为路径分隔符，`a\..\b` 拆分后含 '..' 段
        assertFalse(SafePaths.isSafe("a\\..\\b"))
    }
}
