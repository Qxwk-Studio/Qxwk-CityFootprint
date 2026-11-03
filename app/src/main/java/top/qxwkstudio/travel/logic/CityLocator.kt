package top.qxwkstudio.travel.logic

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 坐标 → 城市。定位按钮用它把「我在哪」变成一个城市名。
 *
 * 两段判定，缺一不可：
 *  1. **落在哪个市的面里**（[CityShapes.firstContaining]，射线法含洞）；
 *  2. 落不进任何面时，取**最近的城市中心**兜底 —— 370 个城市是各自独立简化的，相邻边界不是
 *     同一条弧段，简化后存在几十到几百米的缝隙（实测判不到率 0.12–0.39%）。没有兜底，
 *     用户站在两省交界处会得到「不在任何城市」，看起来像定位坏了。
 *
 * 兜底有**距离上限**（[MAX_FALLBACK_METERS]）：海上、国外离最近的城几十上百公里，
 * 那种情况宁可不判（返回 null）也不要把人算成去了边境城市。
 *
 * 台湾：DataV 把整岛给了 adcode 710000，所以岛内一律判成「台湾」；台北/新北/桃园这些
 * 没有自己边界数据，只能手选。这是数据限制，不是算法问题（2.0 工程口径文档 口径 23）。
 *
 * 纯 Kotlin：城市表与边界 JSON 都由调用方喂进来（Android 侧的读 assets 在 data/CityBoundaries.kt），
 * 所以整段判定能在 JVM 单测里跑。
 */
class CityLocator(
    private val cities: List<City>,
    private val shapes: CityShapes,
) {

    /** 判定结果。[byCenterFallback] = true 表示没落进任何面、是按最近城市中心救回来的。 */
    data class Located(val city: City, val byCenterFallback: Boolean)

    /**
     * 判定。落在治理范围外（海上、国外）返回 null。
     *
     * 城市表里找不到对应名字时也走兜底：边界文件与城市表是两份生成物，理论上同源，
     * 但真对不上时宁可给一座最近的城市，也不要让用户点了定位什么都没发生。
     */
    fun locate(lat: Double, lng: Double): Located? {
        val shape = shapes.firstContaining(lat, lng)
        if (shape != null) {
            cities.firstOrNull { it.name == shape.name }?.let { return Located(it, byCenterFallback = false) }
        }

        var best: City? = null
        var bestMeters = Double.MAX_VALUE
        for (city in cities) {
            val meters = distanceMeters(lat, lng, city.lat, city.lng)
            if (meters < bestMeters) {
                bestMeters = meters
                best = city
            }
        }
        // 50 km 之外不算「附近」：海上、国外会走到这里
        if (best == null || bestMeters > MAX_FALLBACK_METERS) return null
        return Located(best, byCenterFallback = true)
    }

    val shapeCount: Int get() = shapes.size

    /** 地图用：去过的城市的边界形状（判定与绘制共用同一份解析结果）。 */
    fun shapesFor(names: Set<String>): List<CityShapes.Shape> = shapes.shapesFor(names)

    companion object {
        /** 兜底的最大距离。超过它就认为「不在这片地方」，返回 null 让调用方提示手选。 */
        const val MAX_FALLBACK_METERS = 50_000.0

        /** 组装：城市表 + 边界 GeoJSON 原文。 */
        fun build(cities: List<City>, boundaries: String): CityLocator =
            CityLocator(cities, CityShapes.parse(boundaries))

        /** 球面距离（米）。判定兜底用它，不需要米级精度，用标准 haversine 即可。 */
        fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
            return 2 * EARTH_RADIUS_METERS * asin(sqrt(a))
        }

        private const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
