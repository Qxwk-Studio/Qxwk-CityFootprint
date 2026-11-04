package top.qxwkstudio.travel.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 照片引用清扫的纯逻辑：给「目录里的文件」与「仍被引用的路径」，挑出应删的。
 *
 * 关键场景是**共享引用**：行程城市段与派生打卡指向同一张图时，只要还有一方引用就不能删。
 */
class PhotoJanitorTest {

    @Test
    fun `orphan files are removed`() {
        val files = listOf("/photos/a.jpg", "/photos/b.jpg")
        val referenced = setOf("/photos/a.jpg")
        assertEquals(listOf("/photos/b.jpg"), PhotoJanitor.unreferenced(files, referenced))
    }

    @Test
    fun `shared reference keeps file`() {
        // 城市段与派生打卡共用一张图：集合里出现一次就够
        val files = listOf("/photos/shared.jpg")
        val referenced = setOf("/photos/shared.jpg")
        assertEquals(emptyList<String>(), PhotoJanitor.unreferenced(files, referenced))
    }

    @Test
    fun `nothing to delete when all referenced`() {
        val files = listOf("/photos/a.jpg", "/photos/b.jpg")
        assertEquals(emptyList<String>(), PhotoJanitor.unreferenced(files, files.toSet()))
    }

    @Test
    fun `empty referenced deletes everything`() {
        val files = listOf("/photos/a.jpg", "/photos/b.jpg")
        assertEquals(files, PhotoJanitor.unreferenced(files, emptySet()))
    }
}
