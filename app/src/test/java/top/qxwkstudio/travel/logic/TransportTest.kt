package top.qxwkstudio.travel.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出行方式枚举 / 展示 / 「最常用出行」判定。
 *
 * 期望值来自后端 worker.js 的 `TRANSPORTS` 与网页端 docs/app.js 的 `topTransportName` ——
 * 三处（含本文件所在的 logic/Transport.kt）必须**同源**，这里就是那份约定的钉子：
 * 谁改了 code 拼写或枚举顺序，先在这里炸出来，而不是等线上发现「最常用出行」跟网页对不上。
 */
class TransportTest {

    @Test
    fun `code 集合与顺序与后端白名单一致`() {
        // 顺序也是契约的一部分：后端按它落库排序，并列时也按它取「最常用」
        assertEquals(
            listOf("plane", "train", "hsr", "car", "bus", "ship", "bike", "walk", "other"),
            Transport.entries.map { it.code },
        )
    }

    @Test
    fun `认不出的 code 回 null`() {
        assertNull(Transport.fromCode("rocket"))
        assertNull(Transport.fromCode(""))
        assertEquals(Transport.HSR, Transport.fromCode("hsr"))
    }

    @Test
    fun `未知 code 在展示时被丢掉 其余保持原顺序`() {
        assertEquals(listOf("✈️ 飞机", "🚲 骑行"), transportLabels(listOf("plane", "rocket", "bike")))
        assertTrue(transportLabels(listOf("rocket")).isEmpty())
        assertTrue(transportLabels(emptyList()).isEmpty())
    }

    @Test
    fun `一条都没填回 null`() {
        assertNull(topTransportLabel(emptyList()))
        assertNull(topTransportLabel(listOf(Visit(city = "北京"), Visit(city = "上海"))))
    }

    @Test
    fun `按用过它的足迹条数投票 多的胜出`() {
        val visits = listOf(
            Visit(city = "北京", transport = listOf("train", "walk")),
            Visit(city = "上海", transport = listOf("train")),
            Visit(city = "广州", transport = listOf("plane")),
        )
        assertEquals("火车", topTransportLabel(visits))
    }

    @Test
    fun `并列时取枚举里靠前的那个`() {
        // 与网页 topTransportName 同口径：候选先照 TRANSPORTS 顺序排（plane 在最前）再稳定排序，
        // 所以同票时 plane 胜出。注意这跟「足迹出现的先后」无关 —— 故意把 train 写在前面来钉住这点
        val visits = listOf(
            Visit(city = "上海", transport = listOf("train")),
            Visit(city = "北京", transport = listOf("plane")),
        )
        assertEquals("飞机", topTransportLabel(visits))
    }

    @Test
    fun `返回值去掉 emoji`() {
        // 卡片标题已经带了 🚆，值里再来一个就重了（与网页端同处理）
        assertEquals("高铁", topTransportLabel(listOf(Visit(city = "北京", transport = listOf("hsr")))))
    }
}