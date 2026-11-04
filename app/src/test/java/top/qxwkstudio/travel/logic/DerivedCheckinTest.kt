package top.qxwkstudio.travel.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双层时间线 → 派生打卡。
 *
 * 核心口径：每个城市段只生成一条打卡，时间是该段第一条事件；城市表里查不到的名字跳过。
 */
class DerivedCheckinTest {

    private fun event(id: String, time: Long, type: Timeline.Type = Timeline.Type.ACTIVITY) =
        Timeline.Event(id = id, time = time, type = type, text = "x")

    private fun timeline(vararg segments: Timeline.Segment) = Timeline(segments.toList())

    private val cities = mapOf(
        "北京" to City(name = "北京", province = "北京", lat = 39.9, lng = 116.4, adcode = 110000),
        "上海" to City(name = "上海", province = "上海", lat = 31.2, lng = 121.4, adcode = 310000),
    )
    private fun lookup(name: String): City? = cities[name]

    @Test
    fun `one checkin per city using its first event`() {
        val t = timeline(
            Timeline.Segment("北京", listOf(event("a", 300), event("b", 200))),
            Timeline.Segment("上海", listOf(event("c", 500))),
        )
        val result = deriveCheckins(t, ::lookup)
        assertEquals(2, result.size)
        assertEquals("北京", result[0].cityName)
        assertEquals(200L, result[0].checkinAt)   // 段内最早，不按列表顺序
        assertEquals("北京", result[0].province)
        assertEquals(110000, result[0].cityCode)
        assertEquals("上海", result[1].cityName)
        assertEquals(500L, result[1].checkinAt)
    }

    @Test
    fun `unknown city skipped`() {
        val t = timeline(
            Timeline.Segment("火星", listOf(event("a", 100))),
            Timeline.Segment("上海", listOf(event("b", 200))),
        )
        val result = deriveCheckins(t, ::lookup)
        assertEquals(1, result.size)
        assertEquals("上海", result[0].cityName)
    }

    @Test
    fun `ride events alone still make a checkin`() {
        val t = timeline(
            Timeline.Segment("北京", listOf(event("a", 100, Timeline.Type.RIDE))),
        )
        val result = deriveCheckins(t, ::lookup)
        assertEquals(1, result.size)
        assertTrue(result[0].checkinAt == 100L)
    }

    @Test
    fun `optional fields copied from segment`() {
        val t = timeline(
            Timeline.Segment(
                "北京",
                listOf(event("a", 100)),
                companions = "朋友",
                rating = 5,
                photoPath = "/tmp/a.jpg",
            ),
        )
        val result = deriveCheckins(t, ::lookup).single()
        assertEquals("朋友", result.companions)
        assertEquals(5, result.rating)
        assertEquals("/tmp/a.jpg", result.photoPath)
    }

    @Test
    fun `empty timeline derives nothing`() {
        assertTrue(deriveCheckins(Timeline(), ::lookup).isEmpty())
    }
}
