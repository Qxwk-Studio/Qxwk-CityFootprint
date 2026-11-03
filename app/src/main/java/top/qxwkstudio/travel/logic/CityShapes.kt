package top.qxwkstudio.travel.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 城市边界（`assets/data/city_boundaries.geojson`）的**纯解析 + 命中判定**。
 *
 * 数据是生成物（`app/tools/build-city-geo.mjs`）：370 个市级面，**已从 GCJ-02 转到 WGS-84**、
 * 简化到 20%。这一步很重要 —— 定位拿到的是 WGS-84，不转的话判定整体偏 500–640 m。
 *
 * 为什么自己解析而不是引 GeoJSON 库：这里只要「一个多边形 = 外环 + 若干洞」这一件事，
 * 引一个通用库要多一份依赖与 R8 规则，换来的只是省下这一百来行。
 *
 * 为什么不用 `org.json`（app 里 GeoJson.kt 用的是它）：那份是给 Android 运行时用的，
 * 而这里的解析要能在 JVM 单测里跑（Android 的 org.json 在单测里会抛 "not mocked"）。
 * kotlinx-serialization 的 JsonElement 两边都能用，且已经是本项目的既有依赖。
 *
 * 坐标存储用**扁平 DoubleArray**（[lng0, lat0, lng1, lat1, …]）而不是 `List<LatLng>` 一类对象：
 * 370 个面、十几万个点，每点一个对象在手机上就是几十 MB 的无谓分配；拍平之后是一个 double 数组，
 * 命中判定也只是一次线性扫描。
 */
class CityShapes private constructor(
    private val shapes: List<Shape>,
    private val grid: Map<Long, List<Shape>>,
) {

    /** 一个市级面（MultiPolygon 会拆成多个）。 */
    class Shape(
        val name: String,
        val adcode: Int,
        /** 外环，扁平 [lng, lat, …]。 */
        val outer: DoubleArray,
        /** 洞（湖泊、飞地挖空）。 */
        val holes: List<DoubleArray>,
        val minLng: Double, val minLat: Double, val maxLng: Double, val maxLat: Double,
    )

    /**
     * 点是否落在这批形状里的某一个。返回命中的形状；都不命中返回 null。
     *
     * 只做「命中」判断，**不做兜底** —— 兜底（最近城市、距离阈值）是 [CityLocator] 的事，
     * 因为那要城市表，而这里只有边界。
     */
    fun firstContaining(lat: Double, lng: Double): Shape? {
        for (shape in grid[cellKey(lng, lat)] ?: return null) {
            if (shape.contains(lat, lng)) return shape
        }
        return null
    }

    /** 解析出来的面数（370 个城市里有的城市是多面，所以这个数可能大于城市数）。 */
    val size: Int get() = shapes.size

    /** 网格粗筛的格子边长（度）。0.5° 约 55 km，比 R-Tree 简单且够用（P0 结论）。 */
    private fun cellKey(lng: Double, lat: Double): Long =
        ((lng / CELL).toLong() shl 32) xor ((lat / CELL).toLong() and 0xffffffffL)

    private fun Shape.contains(lat: Double, lng: Double): Boolean {
        if (lng < minLng || lng > maxLng || lat < minLat || lat > maxLat) return false
        if (!inRing(outer, lng, lat)) return false
        return holes.none { inRing(it, lng, lat) }
    }

    companion object {
        /** 网格格子边长（度），查询与建索引必须用同一个值。 */
        private const val CELL = 0.5

        /**
         * 解析。**结构不符/坏 JSON 返回 empty 而不是抛异常**：地图与定位都是可降级的功能，
         * 边界文件坏了应该退化成「只按城市中心画点」，而不是让打卡页崩掉。
         */
        fun parse(raw: String): CityShapes = runCatching { parseOrThrow(raw) }.getOrElse { empty() }

        fun empty(): CityShapes = CityShapes(emptyList(), emptyMap())

        private fun parseOrThrow(raw: String): CityShapes {
            val root = Json.parseToJsonElement(raw).jsonObject
            val features = root["features"]?.jsonArray ?: return empty()

            val shapes = ArrayList<Shape>(features.size)
            for (element in features) {
                val feature = element.jsonObject
                val props = feature["properties"]?.jsonObject
                val name = props?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: continue
                val adcode = props["adcode"]?.jsonPrimitive?.intOrNull ?: continue
                val geometry = feature["geometry"]?.jsonObject ?: continue

                when (geometry["type"]?.jsonPrimitive?.contentOrNull) {
                    "Polygon" -> geometry["coordinates"]?.jsonArray?.let { shapes += polygon(name, adcode, it) }
                    "MultiPolygon" -> geometry["coordinates"]?.jsonArray?.forEach { part ->
                        (part as? JsonArray)?.let { shapes += polygon(name, adcode, it) }
                    }
                    // 其余几何类型（点/线）对判定没有意义，直接跳过
                }
            }
            return CityShapes(shapes, buildGrid(shapes))
        }

        /** 一个 Polygon 的 coordinates = [外环, 洞…]。 */
        private fun polygon(name: String, adcode: Int, rings: JsonArray): List<Shape> {
            if (rings.isEmpty()) return emptyList()
            val parsed = rings.mapNotNull { ring -> (ring as? JsonArray)?.let(::flatRing) }
            val outer = parsed.firstOrNull() ?: return emptyList()
            return listOf(withBounds(name, adcode, outer, parsed.drop(1)))
        }

        private fun withBounds(name: String, adcode: Int, outer: DoubleArray, holes: List<DoubleArray>): Shape {
            var minLng = Double.MAX_VALUE
            var minLat = Double.MAX_VALUE
            var maxLng = -Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var i = 0
            while (i < outer.size) {
                val lng = outer[i]
                val lat = outer[i + 1]
                if (lng < minLng) minLng = lng
                if (lng > maxLng) maxLng = lng
                if (lat < minLat) minLat = lat
                if (lat > maxLat) maxLat = lat
                i += 2
            }
            return Shape(name, adcode, outer, holes, minLng, minLat, maxLng, maxLat)
        }

        /** `[[lng,lat],[lng,lat],…]` → 扁平数组；点数 < 3 的环丢掉（闭合不成面）。 */
        private fun flatRing(ring: JsonArray): DoubleArray? {
            if (ring.size < 3) return null
            val out = DoubleArray(ring.size * 2)
            var i = 0
            for (point in ring) {
                val pair = point as? JsonArray ?: return null
                val lng = pair.getOrNull(0)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
                val lat = pair.getOrNull(1)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
                out[i++] = lng
                out[i++] = lat
            }
            return out
        }

        /** 建网格索引：把一个面的包围盒覆盖到的每个格子都登记上它，查询时只看一个格子。 */
        private fun buildGrid(shapes: List<Shape>): Map<Long, List<Shape>> {
            val grid = HashMap<Long, MutableList<Shape>>()
            for (shape in shapes) {
                val gx0 = (shape.minLng / CELL).toLong()
                val gx1 = (shape.maxLng / CELL).toLong()
                val gy0 = (shape.minLat / CELL).toLong()
                val gy1 = (shape.maxLat / CELL).toLong()
                for (gx in gx0..gx1) for (gy in gy0..gy1) {
                    grid.getOrPut((gx shl 32) xor (gy and 0xffffffffL)) { ArrayList(2) } += shape
                }
            }
            return grid
        }

        /** 射线法（含「点在边上」的抖动容忍交给调用方，这里只回答严格的内外）。 */
        private fun inRing(ring: DoubleArray, x: Double, y: Double): Boolean {
            var inside = false
            var i = 0
            var j = ring.size - 2
            while (i < ring.size) {
                val xi = ring[i]
                val yi = ring[i + 1]
                val xj = ring[j]
                val yj = ring[j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                    inside = !inside
                }
                j = i
                i += 2
            }
            return inside
        }
    }
}
