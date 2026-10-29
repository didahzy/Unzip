package ziyao.huang.unzip.archive.rar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** Task 5：分卷发现（目录列表以 lambda 注入，app 层接真实目录/缓存，测试接 fake）。 */
class MultiVolumeTest {

    @Test
    fun volumeSiblingDiscovery() {
        val dir = listOf(
            "/x/a.part3.rar",
            "/x/a.part10.rar",
            "/x/a.part2.rar",
            "/x/a.part1.rar",
            "/x/b.part1.rar",
            "/x/a.part9.rar",
            "/x/readme.txt",
        )
        assertEquals(
            // 按 part 号数值排序（part9 在 part10 前），同前缀才算同一组分卷
            listOf("/x/a.part1.rar", "/x/a.part2.rar", "/x/a.part3.rar", "/x/a.part9.rar", "/x/a.part10.rar"),
            MultiVolume.siblings("/x/a.part1.rar") { dir },
        )
    }

    @Test
    fun singleVolumeReturnsItself() {
        assertEquals(listOf("/x/solo.rar"), MultiVolume.siblings("/x/solo.rar") { listOf("/x/solo.rar") })
    }

    @Test
    fun openingMiddlePartStillStartsFromFirst() {
        // unrar 必须从最小 part 开始解；用户从中间卷进来也要交回全组按序排列
        val dir = listOf("/x/a.part1.rar", "/x/a.part2.rar", "/x/a.part3.rar")
        assertEquals(
            listOf("/x/a.part1.rar", "/x/a.part2.rar", "/x/a.part3.rar"),
            MultiVolume.siblings("/x/a.part2.rar") { dir },
        )
    }

    /** 默认目录列表实现必须列 [firstVolume] 所在目录本身（曾误列父目录，connected 实测抓出）。 */
    @Test
    fun defaultListDirectoryListsSameDirectory() {
        val dir = Files.createTempDirectory("unseal-mv").toFile()
        try {
            listOf(1, 2, 3).forEach { n -> Files.createFile(dir.toPath().resolve("a.part$n.rar")) }
            val expected = (1..3).map { dir.resolve("a.part$it.rar").absolutePath }
            assertEquals(expected, MultiVolume.siblings(dir.resolve("a.part1.rar").absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }
}
