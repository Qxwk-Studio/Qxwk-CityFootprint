package top.qxwkstudio.travel.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双层时间线的校验与派生（2.0 的行程 = 城市段 → 事件列表）。
 *
 * 为什么值得单测：这些规则**算错了不会崩**，只会悄悄存进一条城市重复、时间乱序的行程，
 * 或者少派生一座城市 —— 都是几天后才发现的那种问题。纯 Kotlin，不需要设备。
 */
class TimelineTest {

    private fun event(
        id: String = "e",
        time: Long = 0,
        type: Timeline.Type = Timeline.Type.ACTIVITY,
        text: String = "故宫",
        cost: Int? = null,
    ) = Timeline.Event(id = id, time = time, type = type, text = text, costCents = cost)

    private fun segment(city: String = "北京", vararg events: Timeline.Event) =
        Timeline.Segment(city = city, events = events.toList())

    private fun timeline(vararg segments: Timeline.Segment) = Timeline(segments.toList())

    // ── 基本不变量 ──

    @Test
    fun `empty timeline allowed as draft but rejected on save`() {
        assertNull(timeline().validate(requireSegments = false))
        assertEquals("至少添加一座到访城市", timeline().validate())
    }

    @Test
    fun `blank city rejected`() {
        assertEquals("第 1 段：请选择到访城市", timeline(segment("", event())).validate())
    }

    @Test
    fun `same city twice rejected`() {
        val error = timeline(segment("北京", event()), segment("北京", event(time = 10))).validate()
        assertTrue(error!!.contains("出现了两次"))
    }

    @Test
    fun `city without events rejected`() {
        val error = timeline(segment("北京")).validate()
        assertTrue(error!!.contains("还没有添加活动或交通"))
    }

    @Test
    fun `blank event text rejected`() {
        val error = timeline(segment("北京", event(text = "   "))).validate()
        assertTrue(error!!.contains("请填写活动内容"))
    }

    @Test
    fun `too long text rejected`() {
        val long = "很".repeat(Limits.SHORT + 1)
        val error = timeline(segment("北京", event(text = long))).validate()
        assertTrue(error!!.contains("超过 ${Limits.SHORT} 字"))
    }

    // ── 时间顺序 ──

    @Test
    fun `reversed inside one city rejected`() {
        val error = timeline(segment("北京", event("a", 200), event("b", 100))).validate()
        assertTrue(error!!.contains("时间早于上一条"))
    }

    @Test
    fun `reversed across cities rejected`() {
        val error = timeline(
            segment("北京", event("a", 200)),
            segment("上海", event("b", 100)),
        ).validate()
        assertTrue(error!!.contains("早于上一座城市"))
    }

    @Test
    fun `equal times allowed`() {
        assertNull(timeline(segment("北京", event("a", 100), event("b", 100))).validate())
    }

    @Test
    fun `rating outside one to five rejected`() {
        val low = timeline(Timeline.Segment("北京", listOf(event()), rating = 0))
        val high = timeline(Timeline.Segment("北京", listOf(event()), rating = 6))
        assertTrue(low.validate()!!.contains("评分"))
        assertTrue(high.validate()!!.contains("评分"))
    }

    @Test
    fun `too long companions rejected`() {
        val t = timeline(Timeline.Segment("北京", listOf(event()), companions = "字".repeat(Limits.SHORT + 1)))
        assertTrue(t.validate()!!.contains("同行人超过"))
    }

    @Test
    fun `too long event note rejected`() {
        val t = timeline(Timeline.Segment("北京", listOf(event().copy(note = "字".repeat(Limits.NOTE + 1)))))
        assertTrue(t.validate()!!.contains("备注超过"))
    }

    // ── 派生 ──

    @Test
    fun `first event by city uses earliest event of each segment`() {
        val t = timeline(
            segment("北京", event("a", 300), event("b", 200)),
            segment("上海", event("c", 400)),
        )
        val first = t.firstEventByCity()
        assertEquals(setOf("北京", "上海"), first.keys)
        assertEquals(200L, first.getValue("北京").time)
        assertEquals(400L, first.getValue("上海").time)
    }

    @Test
    fun `counts and totals`() {
        val t = timeline(
            segment("北京", event("a", 100, cost = 1500), event("b", 200, Timeline.Type.RIDE, "高铁")),
            segment("上海", event("c", 500)),
        )
        assertEquals(2, t.cityCount)
        assertEquals(3, t.eventCount)
        assertEquals(100L, t.startTime)
        assertEquals(500L, t.endTime)
        assertEquals(1500, t.totalCostCents)
    }

    @Test
    fun `encode decode round trip`() {
        val t = timeline(
            segment("北京", event("a", 100, cost = 9900), event("b", 200, Timeline.Type.RIDE, "高铁")),
            segment("上海", event("c", 500)),
        )
        val decoded = Timeline.decode(t.encode())
        assertNotNull(decoded)
        assertEquals(2, decoded!!.cityCount)
        assertEquals(3, decoded.eventCount)
        assertEquals("高铁", decoded.segments[0].events[1].text)
        assertEquals(9900, decoded.segments[0].events[0].costCents)
    }

    @Test
    fun `garbage json returns null`() {
        assertNull(Timeline.decode("not json"))
        assertNull(Timeline.decode("{\"segments\": 5}"))
    }
}
