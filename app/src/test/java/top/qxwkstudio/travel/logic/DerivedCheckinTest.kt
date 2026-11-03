package top.qxwkstudio.travel.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行程 → 派生打卡的规则（2.0 的核心口径：足迹自动派生关联打卡，打卡是地图与成就的唯一数据源）。
 *
 * 纯函数测试：城市数据用假的查表函数喂进来，不需要设备也不需要数据库。
 * 这些规则算错了不会崩 —— 只会让地图少点亮一座城、或者成就永远差一条，几天后才被发现。
 */
class DerivedCheckinTest {

    private val 北京 = City(name = "北京", province = "北京", lat = 39.904, lng = 116.407, adcode = 110000)
    private val 上海 = City(name = "上海", province = "上海", lat = 31.23, lng = 121.474, adcode = 310000)

    // 县级市：城市表里有、但没有 adcode（地图上只画标记），派生打卡照样要生成
    private val 格尔木 = City(name = "格尔木", province = "青海", lat = 36.402, lng = 94.903, adcode = null)

    private val cities = listOf(北京, 上海, 格尔木)
    private val lookup: (String) -> City? = { name -> cities.firstOrNull { it.name == name } }

    private fun visit(id: String, time: Long, city: String) =
        TimelineEvent(id = id, time = time, type = TimelineType.VISIT, cityName = city)

    @Test
    fun `每次首次到访生成一条 同城多次只取第一次`() {
        val timeline = Timeline(
            listOf(
                visit("a", 100, "北京"),
                visit("b", 200, "上海"),
                visit("c", 300, "北京"),
            )
        )
        val derived = deriveCheckins(timeline, lookup)

        assertEquals(listOf("北京", "上海"), derived.map { it.cityName })
        // 时间取首次到访那一刻，而不是最后一次
        assertEquals(100L, derived.first().checkinAt)
        assertEquals("北京", derived.first().province)
        assertEquals(110000, derived.first().cityCode)
    }

    @Test
    fun `非到访事件不派生打卡`() {
        val timeline = Timeline(
            listOf(
                TimelineEvent("a", 100, TimelineType.RIDE, transport = "高铁"),
                TimelineEvent("b", 200, TimelineType.ACTIVITY, activity = "看升旗"),
                TimelineEvent("c", 300, TimelineType.GOTO, place = "天安门", lat = 39.9, lng = 116.4),
                visit("d", 400, "北京"),
            )
        )
        assertEquals(listOf("北京"), deriveCheckins(timeline, lookup).map { it.cityName })
    }

    @Test
    fun `城市表里查不到的名字直接跳过 不造空省份记录`() {
        // 「西安」「三亚」这类：名字对不上城市表（旧数据/手写差异），宁可少一条也不写脏数据
        val timeline = Timeline(listOf(visit("a", 100, "不存在的城市"), visit("b", 200, "上海")))
        val derived = deriveCheckins(timeline, lookup)
        assertEquals(listOf("上海"), derived.map { it.cityName })
    }

    @Test
    fun `没有adcode的城市也能派生 只是没有边界`() {
        val timeline = Timeline(listOf(visit("a", 100, "格尔木")))
        val derived = deriveCheckins(timeline, lookup).single()
        assertEquals("格尔木", derived.cityName)
        assertEquals("青海", derived.province)
        assertNull(derived.cityCode)
    }

    @Test
    fun `空时间线派出空列表`() {
        assertTrue(deriveCheckins(Timeline(emptyList()), lookup).isEmpty())
    }
}
