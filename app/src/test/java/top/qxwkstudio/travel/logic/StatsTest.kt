package top.qxwkstudio.travel.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 主页统计与城市排名的口径（2.0 工程口径文档 §6.4）。
 *
 * 这些数字算错了不会崩，只会「看起来差不多但不对」—— 比如省份多算一个、跨度被占位日期拉到
 * 1 月 1 日、或者城市排名把没有打卡的城市也列出来。所以每条口径都钉一个测试。
 */
class StatsTest {

    private fun entry(
        city: String,
        province: String,
        at: Long,
        precision: Int = DatePrecision.DAY,
    ) = Stats.Entry(city, province, at, precision)

    private val 北京 = entry("北京", "北京", 100)
    private val 上海 = entry("上海", "上海", 200)
    private val 苏州 = entry("苏州", "江苏", 300)
    private val 南京 = entry("南京", "江苏", 400)

    // ── 概览 ──

    @Test
    fun `空数据显示全是0 且没有跨度`() {
        val o = Stats.overview(emptyList())
        assertEquals(0, o.cities)
        assertEquals(0, o.provinces)
        assertEquals(0, o.visits)
        assertNull(o.rangeStart)
        assertNull(o.rangeEnd)
        assertNull(o.mostVisitedCity)
    }

    @Test
    fun `城市与省份都去重 同城多次只算一座`() {
        val entries = listOf(
            北京, 北京.copy(checkinAt = 150),
            上海,
            苏州, 南京, // 同属江苏
        )
        val o = Stats.overview(entries)
        assertEquals(4, o.cities)      // 北京 / 上海 / 苏州 / 南京（北京的第二次不算新城市）
        assertEquals(3, o.provinces)   // 北京 / 上海 / 江苏（苏州与南京同省）
        assertEquals(5, o.visits)      // 打卡条数照实算，不去重
    }

    @Test
    fun `跨度只统计完整日期的打卡`() {
        val entries = listOf(
            entry("北京", "北京", 100, DatePrecision.YEAR),
            entry("上海", "上海", 500, DatePrecision.DAY),
            entry("苏州", "江苏", 900, DatePrecision.UNKNOWN),
            entry("南京", "江苏", 300, DatePrecision.DAY),
        )
        val o = Stats.overview(entries)
        // 只取两条 DAY：300 与 500，其余两档的日期是补出来的占位值
        assertEquals(300L, o.rangeStart)
        assertEquals(500L, o.rangeEnd)
    }

    @Test
    fun `一条完整日期都没有时跨度为空 但城市照常统计`() {
        val entries = listOf(
            entry("北京", "北京", 100, DatePrecision.YEAR),
            entry("上海", "上海", 200, DatePrecision.MONTH),
        )
        val o = Stats.overview(entries)
        assertNull(o.rangeStart)
        assertNull(o.rangeEnd)
        assertEquals(2, o.cities)
    }

    @Test
    fun `次数最多按城市计次 并列取名字字典序靠前`() {
        val entries = listOf(
            entry("上海", "上海", 1), entry("上海", "上海", 2),
            entry("北京", "北京", 3), entry("北京", "北京", 4),
            entry("苏州", "江苏", 5),
        )
        // 上海 2 次、北京 2 次：并列时取字典序靠前的「上海」
        assertEquals("上海", Stats.overview(entries).mostVisitedCity)

        // 次数不同时按次数
        val more = entries + entry("上海", "上海", 6)
        assertEquals("上海", Stats.overview(more).mostVisitedCity)

        val beijingMore = entries + entry("北京", "北京", 7) + entry("北京", "北京", 8)
        assertEquals("北京", Stats.overview(beijingMore).mostVisitedCity)
    }

    // ── 排名 ──

    private val rankEntries = listOf(
        entry("北京", "北京", 100),
        entry("北京", "北京", 200),
        entry("北京", "北京", 300),
        entry("上海", "上海", 900),
        entry("苏州", "江苏", 500),
        entry("苏州", "江苏", 600),
    )

    @Test
    fun `按次数排序`() {
        val rows = Stats.rank(rankEntries, Stats.RankBy.COUNT)
        assertEquals(listOf("北京", "苏州", "上海"), rows.map { it.city })
        assertEquals(listOf(3, 2, 1), rows.map { it.count })
    }

    @Test
    fun `按时间排序取最近一次到访`() {
        // 「时间」现在按**停留天数**排序（来自足迹城市段），不再按最后一次打卡时间
        val days = mapOf("上海" to 5, "苏州" to 2, "北京" to 1)
        val rows = Stats.rank(rankEntries, Stats.RankBy.TIME, cityDays = days)
        assertEquals(listOf("上海", "苏州", "北京"), rows.map { it.city })
        assertEquals(5, rows.first().stayDays)

        // 「金额」按城市累计花销排序
        val amounts = mapOf("上海" to 100, "苏州" to 300, "北京" to 200)
        val byAmount = Stats.rank(rankEntries, Stats.RankBy.AMOUNT, cityAmounts = amounts)
        assertEquals(listOf("苏州", "北京", "上海"), byAmount.map { it.city })
    }

    @Test
    fun `并列时按城市名字典序 保证每次顺序一致`() {
        val entries = listOf(
            entry("苏州", "江苏", 100),
            entry("北京", "北京", 200),
        )
        // 次数都是 1、最近到访不同：按次数排序时只能靠名字定序，不能靠 map 的遍历顺序
        assertEquals(listOf("北京", "苏州"), Stats.rank(entries, Stats.RankBy.COUNT).map { it.city })
    }

    @Test
    fun `没有打卡就没有排名行`() {
        assertEquals(emptyList<Stats.CityRow>(), Stats.rank(emptyList(), Stats.RankBy.COUNT))
    }

    @Test
    fun `省份为空的打卡仍计入城市数 但不计入省份数`() {
        val entries = listOf(entry("某地", "", 100), 北京)
        val o = Stats.overview(entries)
        assertEquals(2, o.cities)
        assertEquals(1, o.provinces) // 只有北京的「北京」
    }
}
