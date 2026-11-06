package top.qxwkstudio.travel.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成就定义与判定。
 *
 * 重点钉三件事：
 *  1. **88 条、code 不重复** —— code 进数据库当主键，重复就等于两条成就互相覆盖；
 *  2. **回溯解锁日期** —— 重装/导入后重算，日期必须是当初那条打卡的时间，不是「现在」；
 *  3. **进度与目标** —— 进度条的分母来自规则本身，不能出现 200/100 这种读数。
 */
class AchievementsTest {

    @Test
    fun `定义共88条 分4类 且 code 唯一`() {
        val all = Achievements.all()
        assertEquals(88, all.size)
        assertEquals(4, Achievements.categories.size)
        assertEquals(all.size, all.map { it.code }.toSet().size)
        // 分类标题不带 emoji（口径 6/7）。注意只能判 emoji 区段：
        // 汉字的 code point 也大于 0x2000，用「大于某值」当判据会把中文全判成 emoji。
        assertTrue(Achievements.categories.none { it.title.any { ch -> ch.code in 0x1F300..0x1FAFF } })
    }

    @Test
    fun `没有打卡时全部未达成`() {
        val states = Achievements.evaluate(emptyList())
        assertEquals(88, states.size)
        assertTrue(states.values.none { it.done })
        assertTrue(states.values.all { it.unlockedAt == null })
        assertTrue(states.values.all { it.progress == 0 })
    }

    @Test
    fun `城市数里程碑 在跨过阈值的那一刻解锁`() {
        val samples = listOf(
            Achievements.Sample("北京", 100),
            Achievements.Sample("上海", 200),
            Achievements.Sample("广州", 300),
        )
        val states = Achievements.evaluate(samples)

        // 2 座：在第二座城市（上海@200）那一刻达成
        val firstTrip = states.getValue("first_trip")
        assertTrue(firstTrip.done)
        assertEquals(2, firstTrip.target)
        assertEquals(2, firstTrip.progress)
        assertEquals(200L, firstTrip.unlockedAt)

        // 5 座：还没达成，进度是实际城市数
        val roamer = states.getValue("city_roamer")
        assertFalse(roamer.done)
        assertNull(roamer.unlockedAt)
        assertEquals(3, roamer.progress)
        assertEquals(5, roamer.target)
    }

    @Test
    fun `同一座城市重复打卡不重复计数`() {
        val samples = listOf(
            Achievements.Sample("北京", 100),
            Achievements.Sample("北京", 200),
            Achievements.Sample("北京", 300),
        )
        val states = Achievements.evaluate(samples)
        assertFalse(states.getValue("first_trip").done)
        assertEquals(1, states.getValue("first_trip").progress)
    }

    @Test
    fun `全部去过的成就 进度按已去的座数算`() {
        val samples = listOf(
            Achievements.Sample("北京", 100),
            Achievements.Sample("上海", 200),
        )
        val metro = Achievements.evaluate(samples).getValue("metro_all")
        assertFalse(metro.done)
        assertEquals(2, metro.progress)
        assertEquals(4, metro.target)
    }

    @Test
    fun `全部去过的成就在最后一座那一刻解锁`() {
        val samples = listOf(
            Achievements.Sample("北京", 100),
            Achievements.Sample("上海", 200),
            Achievements.Sample("广州", 300),
            Achievements.Sample("深圳", 400),
        )
        val metro = Achievements.evaluate(samples).getValue("metro_all")
        assertTrue(metro.done)
        assertEquals(400L, metro.unlockedAt)
    }

    @Test
    fun `任一去过的成就 去一座就满进度`() {
        val samples = listOf(Achievements.Sample("拉萨", 500))
        val plateau = Achievements.evaluate(samples).getValue("plateau_city")
        assertTrue(plateau.done)
        assertEquals(1, plateau.progress)
        assertEquals(1, plateau.target)
        assertEquals(500L, plateau.unlockedAt)
    }

    @Test
    fun `输入顺序不影响结果（内部按时间排序）`() {
        val ordered = listOf(
            Achievements.Sample("北京", 100),
            Achievements.Sample("上海", 200),
            Achievements.Sample("深圳", 300),
        )
        val shuffled = listOf(ordered[2], ordered[0], ordered[1])
        assertEquals(Achievements.evaluate(ordered), Achievements.evaluate(shuffled))
    }

    @Test
    fun `单城成就按该城首次打卡解锁`() {
        val samples = listOf(
            Achievements.Sample("成都", 700),
            Achievements.Sample("成都", 900),
        )
        val chengdu = Achievements.evaluate(samples).getValue("city_chengdu")
        assertTrue(chengdu.done)
        assertEquals(700L, chengdu.unlockedAt)
    }

    @Test
    fun `打卡次数与足迹次数按第 N 次解锁`() {
        val samples = (1..10).map { Achievements.Sample("北京", it.toLong()) }
        val trips = listOf(10L, 20L, 30L)
        val states = Achievements.evaluate(samples, trips)

        val c10 = states.getValue("checkin_10")
        assertTrue(c10.done)
        assertEquals(10, c10.progress)
        assertEquals(10L, c10.unlockedAt)

        val c50 = states.getValue("checkin_50")
        assertFalse(c50.done)
        assertEquals(10, c50.progress)
        assertEquals(50, c50.target)

        val t1 = states.getValue("trip_10")
        assertFalse(t1.done)
        assertEquals(3, t1.progress)

        val tripFirst = states.getValue("trip_1")
        assertTrue(tripFirst.done)
        assertEquals(10L, tripFirst.unlockedAt)
    }

    @Test
    fun `进度不会超过目标`() {
        // 384 城全去：全境巡礼的进度封顶在 293/293
        val samples = (1..384).map { Achievements.Sample("城市$it", it.toLong()) }
        val all = Achievements.evaluate(samples).getValue("grand_tour")
        assertTrue(all.done)
        assertEquals(293, all.progress)
        assertEquals(293, all.target)
    }
}
