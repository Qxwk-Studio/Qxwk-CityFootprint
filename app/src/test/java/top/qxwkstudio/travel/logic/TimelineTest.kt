package top.qxwkstudio.travel.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间线的校验与派生逻辑（2.0 的行程 = 一个有序事件数组）。
 *
 * 为什么值得单测：这些规则**算错了不会崩**，只会悄悄存进一条逆序的行程、少派生一座城市、
 * 或者让「总花销」对不上 —— 都是几天后才发现的那类问题。
 * 纯 Kotlin，不需要设备。
 */
class TimelineTest {

    private fun visit(id: String, time: Long, city: String, code: Int? = null) =
        TimelineEvent(id = id, time = time, type = TimelineType.VISIT, cityName = city, cityCode = code)

    private fun ride(id: String, time: Long, transport: String) =
        TimelineEvent(id = id, time = time, type = TimelineType.RIDE, transport = transport)

    // ── 基本不变量 ──

    @Test
    fun `空时间线在未保存时允许 但保存时必须至少一条`() {
        val empty = Timeline(emptyList())
        assertNull(empty.validate(requireEvents = false))
        assertEquals("至少添加一条事件", empty.validate())
    }

    @Test
    fun `逆序被拒 同一时刻允许`() {
        val reversed = Timeline(listOf(visit("a", 200, "北京"), visit("b", 100, "上海")))
        assertNotNull(reversed.validate())
        assertTrue(reversed.validate()!!.contains("第 2 条"))

        // 同一分钟发生的两件事很常见，不能算错
        val sameTime = Timeline(listOf(visit("a", 100, "北京"), ride("b", 100, "高铁")))
        assertNull(sameTime.validate())
    }

    @Test
    fun `各类型必填项逐个拦下`() {
        val noCity = Timeline(listOf(visit("a", 1, "")))
        assertTrue(noCity.validate()!!.contains("请选择城市"))

        val noTransport = Timeline(listOf(ride("a", 1, "   ")))
        assertTrue(noTransport.validate()!!.contains("请填写交通方式"))

        val noActivity = Timeline(listOf(TimelineEvent("a", 1, TimelineType.ACTIVITY, activity = "")))
        assertTrue(noActivity.validate()!!.contains("请填写活动内容"))

        val noPlace = Timeline(listOf(TimelineEvent("a", 1, TimelineType.GOTO, lat = 39.9, lng = 116.4)))
        assertTrue(noPlace.validate()!!.contains("请填写地点名称"))

        // 地点名有、坐标缺：也要拦（没有坐标的点在地图上画不出来）
        val noCoord = Timeline(
            listOf(TimelineEvent("a", 1, TimelineType.GOTO, place = "天安门", lat = null, lng = 116.4))
        )
        assertTrue(noCoord.validate()!!.contains("缺少坐标"))
    }

    @Test
    fun `短文本超过23字被拒`() {
        val long = "x".repeat(Limits.SHORT + 1)
        val timeline = Timeline(listOf(ride("a", 1, long)))
        assertTrue(timeline.validate()!!.contains("超过 ${Limits.SHORT} 字"))

        // 正好 23 字是合法的（边界不能差一）
        val exact = Timeline(listOf(ride("a", 1, "x".repeat(Limits.SHORT))))
        assertNull(exact.validate())
    }

    @Test
    fun `校验文案带序号 从1开始`() {
        val timeline = Timeline(listOf(visit("a", 1, "北京"), visit("b", 2, "")))
        assertEquals("第 2 条：请选择城市", timeline.validate())
    }

    // ── 派生 ──

    @Test
    fun `派生打卡同一城市只取首次到访`() {
        val timeline = Timeline(
            listOf(
                visit("a", 100, "北京"),
                visit("b", 200, "上海"),
                visit("c", 300, "北京"),
                ride("d", 400, "高铁"),
            )
        )
        val first = timeline.firstVisitByCity()
        assertEquals(listOf("北京", "上海"), first.keys.toList())
        assertEquals(100L, first.getValue("北京").time)
    }

    @Test
    fun `总花销忽略没填的 但0算合法值`() {
        val timeline = Timeline(
            listOf(
                TimelineEvent("a", 1, TimelineType.ACTIVITY, activity = "午饭", costCents = 3500),
                TimelineEvent("b", 2, TimelineType.ACTIVITY, activity = "散步", costCents = 0),
                TimelineEvent("c", 3, TimelineType.ACTIVITY, activity = "发呆"),
            )
        )
        assertEquals(3500, timeline.totalCostCents)
    }

    @Test
    fun `起止时间取首尾节点`() {
        val timeline = Timeline(listOf(visit("a", 100, "北京"), ride("b", 250, "高铁"), visit("c", 400, "天津")))
        assertEquals(100L, timeline.startTime)
        assertEquals(400L, timeline.endTime)
    }

    // ── 序列化（落库格式，改字段名就是改数据格式）──

    @Test
    fun `编解码往返一致`() {
        val original = Timeline(
            listOf(
                visit("a", 100, "北京", 110000),
                TimelineEvent(
                    id = "b", time = 200, type = TimelineType.GOTO, tz = 480,
                    lat = 39.9, lng = 116.4, place = "天安门", costCents = 0,
                ),
            )
        )
        val decoded = Timeline.decode(original.encode())
        assertNotNull(decoded)
        assertEquals(original.events, decoded!!.events)
    }

    @Test
    fun `落库JSON用的字段名是契约`() {
        // 这些键名就是 trips.timeline 里的实际形态：改名等于让存量行解不出来
        val json = Timeline(listOf(visit("a", 100, "北京", 110000))).encode()
        assertTrue(json.contains("\"time\":100"))
        assertTrue(json.contains("\"type\":\"visit\""))
        assertTrue(json.contains("\"cityName\":\"北京\""))
        assertTrue(json.contains("\"cityCode\":110000"))
        // 默认值不写：null 字段不该出现在 JSON 里（否则每条事件都拖一串 null）
        assertTrue(!json.contains("\"transport\""))
    }

    @Test
    fun `坏JSON返回null而不是抛异常`() {
        assertNull(Timeline.decode("not json"))
        // 未知类型：宁可整条判为不可用，也不要猜成某个默认类型
        assertNull(Timeline.decode("""[{"id":"a","time":1,"type":"teleport"}]"""))
        // 缺必填键（id）同理
        assertNull(Timeline.decode("""[{"time":1,"type":"visit"}]"""))
    }
}
