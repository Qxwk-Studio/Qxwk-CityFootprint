package top.qxwkstudio.travel.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成就判定的边界值。
 *
 * 为什么单测要钉这一份：成就是**给人看的荣誉**，阈值算错不会崩、只会悄悄少给或多给一个徽章，
 * 而那是最难被用户发现、也最容易让人怀疑「这 app 是不是在乱算」的一类 bug。
 * 阈值必须与网页版 frontend/achievements.js 完全一致，所以这里的期望值都是从那份 JS 抄下来的。
 */
class AchievementsTest {

    /** 造 n 座不撞任何特殊名单的普通城市。 */
    private fun plainCities(n: Int): List<String> = (1..n).map { "普通城$it" }

    private fun isDone(cityNames: List<String>, achievementName: String): Boolean =
        Achievements.all(cityNames)
            .flatMap { it.items }
            .first { it.name == achievementName }
            .done

    // ── 结构 ──

    @Test
    fun `四组共 42 条成就`() {
        val groups = Achievements.all(emptyList())
        assertEquals(4, groups.size)
        assertEquals(listOf(8, 13, 13, 8), groups.map { it.items.size })
        val (done, total) = Achievements.progress(groups)
        assertEquals(42, total)
        assertEquals(0, done)
    }

    @Test
    fun `同一座城市打卡多次只算一座`() {
        val once = Achievements.all(listOf("北京"))
        val twice = Achievements.all(listOf("北京", "北京"))
        assertEquals(once, twice)
    }

    // ── 数量阈值（>=2 / >=200 / >=293 这三个是边界最容易写错的地方）──

    @Test
    fun `初次启程 在 2 座城市时点亮 1 座时不点亮`() {
        assertFalse(isDone(plainCities(1), "初次启程"))
        assertTrue(isDone(plainCities(2), "初次启程"))
    }

    @Test
    fun `城市之王 在 200 座城市时点亮 199 座时不点亮`() {
        assertFalse(isDone(plainCities(199), "城市之王"))
        assertTrue(isDone(plainCities(200), "城市之王"))
    }

    @Test
    fun `全境巡礼 在 293 座城市时点亮 292 座时不点亮`() {
        assertFalse(isDone(plainCities(292), "全境巡礼"))
        assertTrue(isDone(plainCities(293), "全境巡礼"))
    }

    @Test
    fun `只去过两座普通城市时只点亮一条成就`() {
        val (done, total) = Achievements.progress(Achievements.all(plainCities(2)))
        assertEquals(1, done)
        assertEquals(42, total)
    }

    // ── 集合型（全部到齐 / 任意一座）──

    @Test
    fun `都市集章者 要求四座一线城市全部到齐`() {
        assertFalse(isDone(listOf("北京", "上海", "广州"), "都市集章者"))
        assertTrue(isDone(listOf("北京", "上海", "广州", "深圳"), "都市集章者"))
    }

    @Test
    fun `直辖市览胜 是京津沪渝`() {
        assertTrue(isDone(listOf("北京", "天津", "上海", "重庆"), "直辖市览胜"))
        // 广州不是直辖市，凑四座也不该点亮
        assertFalse(isDone(listOf("北京", "天津", "上海", "广州"), "直辖市览胜"))
    }

    @Test
    fun `高原之城 任意一座即达`() {
        assertFalse(isDone(listOf("苏州"), "高原之城"))
        assertTrue(isDone(listOf("拉萨"), "高原之城"))
    }

    @Test
    fun `单城彩蛋按城市名精确匹配`() {
        assertTrue(isDone(listOf("徐州"), "优势在我"))
        assertFalse(isDone(listOf("杭州"), "优势在我"))
    }
}