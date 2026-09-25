package top.qxwkstudio.travel.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 城市搜索与按名查找。
 * 测的是纯函数（CitySearch），用的数据是这里自己造的几条，不依赖 assets ——
 * assets 那份由 tools/gen-cities.mjs 生成，条数校验放在脚本自己那边（跑脚本时就会报）。
 */
class CitySearchTest {

    private val cities = listOf(
        City("北京", "北京", 39.904, 116.407, 110000),
        City("石家庄", "河北", 38.043, 114.515, 130100),
        City("唐山", "河北", 39.631, 118.180, 130200),
        City("上海", "上海", 31.230, 121.474, 310000),
        // 县级市在源数据里没有 adcode，这里也留一条，钉住「adcode 为 null 是合法值」
        City("格尔木", "青海", 36.406, 94.903, null),
    )

    @Test
    fun `空查询返回全部`() {
        // 城市选择页一进去就该看到完整列表，而不是一片空白
        assertEquals(cities, CitySearch.filter(cities, ""))
        assertEquals(cities, CitySearch.filter(cities, "   "))
    }

    @Test
    fun `按名称匹配`() {
        val result = CitySearch.filter(cities, "北京")
        assertEquals(listOf("北京"), result.map { it.name })
    }

    @Test
    fun `按名称部分匹配`() {
        assertEquals(listOf("石家庄"), CitySearch.filter(cities, "石家").map { it.name })
        // 「山」既在唐山也在（河北的）省名里没有，但唐山的名字里有 —— 只应命中唐山
        assertEquals(listOf("唐山"), CitySearch.filter(cities, "唐山").map { it.name })
    }

    @Test
    fun `按省份匹配 一次拿到该省全部城市`() {
        val result = CitySearch.filter(cities, "河北")
        assertEquals(listOf("石家庄", "唐山"), result.map { it.name })
    }

    @Test
    fun `查不到就是空列表`() {
        assertTrue(CitySearch.filter(cities, "不存在的城市").isEmpty())
    }

    @Test
    fun `查询词首尾空格不影响匹配`() {
        assertEquals(listOf("上海"), CitySearch.filter(cities, "  上海 ").map { it.name })
    }

    @Test
    fun `findByName 精确匹配`() {
        assertEquals(130100, CitySearch.findByName(cities, "石家庄")?.adcode)
        // adcode 为 null 是合法值（县级市取不到边界）
        assertNull(CitySearch.findByName(cities, "格尔木")?.adcode)
        assertNull(CitySearch.findByName(cities, "石家庄市"))
    }
}