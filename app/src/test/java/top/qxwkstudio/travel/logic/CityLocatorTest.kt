package top.qxwkstudio.travel.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坐标 → 城市判定（网格 + 射线法 + 最近城市兜底）。
 *
 * 用合成的边界数据（两个方形 + 一个带洞的方形 + 一座远处的城），不依赖那份 4.6 MB 的真数据：
 * 真数据只用于人工抽查，单测要的是「规则对不对」，而规则不随边界精度变化。
 */
class CityLocatorTest {

    // 注意参数顺序：City(name, province, country, lat, lng, adcode) —— lat 在前，别和 GeoJSON 的
    // [lng, lat] 顺序搞混（写反了所有兜底距离都会错，症状是「明明站在城里却判不出城市」）
    private val 甲市 = City(name = "甲市", province = "甲省", lat = 30.5, lng = 100.5, adcode = 1)
    private val 乙市 = City(name = "乙市", province = "乙省", lat = 30.5, lng = 102.5, adcode = 2)
    private val 丙市 = City(name = "丙市", province = "丙省", lat = 39.5, lng = 119.5, adcode = 3)
    private val cities = listOf(甲市, 乙市, 丙市)

    private fun ring(vararg points: Pair<Double, Double>): String =
        points.joinToString(",", "[", "]") { (lng, lat) -> "[$lng,$lat]" }

    private fun square(minLng: Double, minLat: Double, size: Double = 1.0): String =
        ring(
            minLng to minLat,
            (minLng + size) to minLat,
            (minLng + size) to (minLat + size),
            minLng to (minLat + size),
            minLng to minLat,
        )

    private fun polygonFeature(name: String, adcode: Int, vararg rings: String): String =
        """{"type":"Feature","properties":{"name":"$name","adcode":$adcode},
           "geometry":{"type":"Polygon","coordinates":[${rings.joinToString(",")}]}}"""

    private fun multiPolygonFeature(name: String, adcode: Int, vararg parts: String): String =
        """{"type":"Feature","properties":{"name":"$name","adcode":$adcode},
           "geometry":{"type":"MultiPolygon","coordinates":[${parts.joinToString(",")}]}}"""

    private fun collection(vararg features: String): String =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    /** 甲市：100–101 / 30–31；乙市：102–103 / 30–31，中间挖一个 0.2° 的洞；丙市在 (119.5, 39.5)。 */
    private val boundaries = collection(
        polygonFeature("甲市", 1, square(100.0, 30.0)),
        polygonFeature(
            "乙市", 2,
            square(102.0, 30.0),
            ring(102.4 to 30.4, 102.6 to 30.4, 102.6 to 30.6, 102.4 to 30.6, 102.4 to 30.4),
        ),
        polygonFeature("丙市", 3, square(119.0, 39.0)),
    )

    private val locator = CityLocator.build(cities, boundaries)

    @Test
    fun `落在面里 直接命中 不走兜底`() {
        val hit = locator.locate(30.5, 100.5)!!
        assertEquals("甲市", hit.city.name)
        assertTrue(!hit.byCenterFallback)

        // 换到格子的另一侧（0.5° 网格边界附近）仍然命中同一个面
        assertEquals("甲市", locator.locate(30.9, 100.9)!!.city.name)
    }

    @Test
    fun `落在洞里 由最近城市兜底`() {
        // 洞的中心离乙市城市中心很近（同经度、纬度差为零量级），所以兜底回乙市但标记为 fallback
        val hit = locator.locate(30.5, 102.5)!!
        assertEquals("乙市", hit.city.name)
        assertTrue("洞里没有命中任何面，应该走兜底", hit.byCenterFallback)
    }

    @Test
    fun `缝隙里的点由最近城市兜底`() {
        // 两座相邻的城中间留一条窄缝（简化后相邻边界不重合就是这个形状）：
        // 缝里的点离最近的城市中心只有几公里，必须兜底回来，而不是答「不在任何城市」。
        // 注意兜底按**城市中心**算距离（50 km 上限），所以这里让两座城围着各自的中心铺开。
        val gap = collection(
            polygonFeature("甲市", 1, square(100.4, 30.4, size = 0.2)),
            polygonFeature("乙市", 2, square(100.7, 30.4, size = 0.2)),
        )
        val l = CityLocator.build(cities, gap)
        val hit = l.locate(30.5, 100.65)!!
        assertEquals("甲市", hit.city.name)
        assertTrue("应走兜底", hit.byCenterFallback)
    }

    @Test
    fun `超过50公里不判 返回null`() {
        // 海上/国外：离最近的城上千公里
        assertNull(locator.locate(40.0, 110.0))
    }

    @Test
    fun `坏数据退化成只有兜底 不抛异常`() {
        val broken = CityLocator.build(cities, "{ not json")
        assertEquals(0, broken.shapeCount)
        // 仍然能按最近城市中心给出答案
        val hit = broken.locate(30.5, 100.5)!!
        assertEquals("甲市", hit.city.name)
        assertTrue(hit.byCenterFallback)
    }

    @Test
    fun `MultiPolygon 拆成多个面 都算同一座城市`() {
        val twoPart = collection(
            multiPolygonFeature(
                "甲市", 1,
                "[${square(100.0, 30.0)}]",
                "[${square(105.0, 30.0)}]",
            )
        )
        val l = CityLocator.build(cities, twoPart)
        assertEquals(2, l.shapeCount)
        assertEquals("甲市", l.locate(30.5, 100.5)!!.city.name)
        assertEquals("甲市", l.locate(30.5, 105.5)!!.city.name)
    }

    @Test
    fun `球面距离算得对`() {
        // 赤道上经度差 1° ≈ 111.19 km
        val d = CityLocator.distanceMeters(0.0, 0.0, 0.0, 1.0)
        assertTrue("$d", d in 111_000.0..111_400.0)
        // 同一点为 0
        assertEquals(0.0, CityLocator.distanceMeters(30.0, 100.0, 30.0, 100.0), 0.0001)
    }

    @Test
    fun `城市表里没有的边界名 走兜底而不是丢结果`() {
        val orphan = collection(polygonFeature("不存在的市", 99, square(100.0, 30.0)))
        val l = CityLocator.build(cities, orphan)
        val hit = l.locate(30.5, 100.5)!!
        assertEquals("甲市", hit.city.name)
        assertTrue(hit.byCenterFallback)
    }
}
