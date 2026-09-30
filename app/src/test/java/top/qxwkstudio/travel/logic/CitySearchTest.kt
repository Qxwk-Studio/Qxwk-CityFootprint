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

    // 坐标往后一律具名传：City 的构造参数里夹着带默认值的 country（见 logic/City.kt），
    // 位置传参会一路错位，这个用例就是被那次加字段踩到的
    private val cities = listOf(
        City("北京", "北京", lat = 39.904, lng = 116.407, adcode = 110000),
        City("石家庄", "河北", lat = 38.043, lng = 114.515, adcode = 130100),
        City("唐山", "河北", lat = 39.631, lng = 118.180, adcode = 130200),
        City("上海", "上海", lat = 31.230, lng = 121.474, adcode = 310000),
        // 县级市在源数据里没有 adcode，这里也留一条，钉住「adcode 为 null 是合法值」
        City("格尔木", "青海", lat = 36.406, lng = 94.903, adcode = null),
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

    @Test
    fun `nearest 取最近的城市 城市表为空回 null`() {
        // 点落在石家庄与北京之间、离石家庄更近：必须取石家庄而不是更近纬度的北京
        assertEquals("石家庄", CitySearch.nearest(cities, 38.5, 115.0)?.name)
        assertEquals("上海", CitySearch.nearest(cities, 31.0, 121.0)?.name)
        // 城市表读不到（asset 坏了会退化成空表）时不能崩，回 null 让上层提示
        assertNull(CitySearch.nearest(emptyList(), 38.5, 115.0))
    }
}