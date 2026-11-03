package top.qxwkstudio.travel.logic

/**
 * 城市边界（`assets/data/city_boundaries.geojson`）的解析 + 命中判定。
 *
 * 数据是生成物（`app/tools/build-city-geo.mjs`）：370 个市级面，**已从 GCJ-02 转到 WGS-84**、
 * 简化到 20%。不转的话判定整体偏 500–640 m（详见生成脚本的注释）。
 *
 * **为什么是手写的流式扫描器，而不是 kotlinx-serialization 的 JsonElement 树**：
 * 这份文件 4.6 MB、四十多万个坐标点，先建一棵 JsonElement 树在手机上会吃掉一两百 MB ——
 * 真机上实测直接 OOM，而 `runCatching` 把失败吞了，症状是「面 0」、地图一个多边形都没有
 * （JVM 单测因为堆大，一直是过的，所以没提前发现）。现在边扫边把数字填进扁平 DoubleArray，
 * 峰值内存只有原始字符串 + 坐标数组（约 5 + 7 MB）。
 *
 * 坐标存**扁平 DoubleArray**（`[lng0, lat0, lng1, lat1, …]`）：370 个面十几万个点，
 * 每点一个对象在手机上就是几十 MB 的无谓分配；拍平之后命中判定也只是一次线性扫描。
 *
 * 文件格式假定（都由我们自己的生成脚本产出，见脚本头）：FeatureCollection → features[] →
 * 每个 feature 有 `properties{name, adcode}` 与 `geometry{type, coordinates}`。
 * 解析器对字段顺序不敏感（properties 与 geometry 谁先都行），坏数据整体退化为空。
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

    /** 解析出来的面数（有的城市是多面，所以这个数可能大于城市数）。 */
    val size: Int get() = shapes.size

    /** 取出名字在 [names] 里的面（地图只画去过的城市，不必重新解析一遍原始文件）。 */
    fun shapesFor(names: Set<String>): List<Shape> = shapes.filter { it.name in names }

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
         * 解析。**坏 JSON / 结构不符返回 empty 而不是抛异常**：边界是可降级的功能，
         * 文件坏了应该退化成「只按城市中心画点」，而不是让地图页崩掉。
         */
        /**
         * 上一次解析失败的原因（成功为 null）。
         *
         * 为什么留这个字段：解析失败是**静默降级**（地图只剩圆点），真机上没有 logcat 时
         * 根本不知道是内存、格式还是资产没读到。地图页会把这条挂到界面上。
         */
        @Volatile
        var lastError: String? = null
            private set

        fun parse(raw: String): CityShapes {
            lastError = null
            if (raw.isEmpty()) {
                lastError = "边界资产为空（没读到）"
                return empty()
            }
            return try {
                val shapes = Scanner(raw).readFeatures()
                lastError = null
                CityShapes(shapes, buildGrid(shapes))
            } catch (t: Throwable) {
                // 连带把位置带出来：结构不符时报的就是「位置 N 期望 X」，很好定位
                lastError = t.javaClass.simpleName + ": " + (t.message ?: "")
                empty()
            }
        }

        fun empty(): CityShapes = CityShapes(emptyList(), emptyMap())

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

        /** 射线法（含洞靠调用方逐个环判）。[ring] 是扁平 [lng, lat, …]。 */
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

/**
 * GeoJSON 的单遍扫描器：只认我们需要的字段，其余整段跳过。
 *
 * 关键点：
 *  - 不建语法树，读到坐标就直接填 [DoubleArray]；
 *  - 字段顺序不敏感：feature 里 properties 与 geometry 谁先都行；
 *  - 任何结构不符都抛异常，由 [CityShapes.parse] 统一退化成空。
 */
private class Scanner(private val s: String) {

    private var i = 0

    fun readFeatures(): List<CityShapes.Shape> {
        val out = ArrayList<CityShapes.Shape>(512)
        skipWs()
        expect('{')
        while (true) {
            skipWs()
            if (peek() == '}') { i++; break }
            val key = readString()
            skipWs()
            expect(':')
            skipWs()
            if (key == "features") readFeatureArray(out) else skipValue()
            skipWs()
            if (peek() == ',') { i++; continue }
            expect('}')
            break
        }
        return out
    }

    private fun readFeatureArray(out: MutableList<CityShapes.Shape>) {
        expect('[')
        skipWs()
        if (peek() == ']') { i++; return }
        while (true) {
            readFeature(out)
            skipWs()
            if (peek() == ',') { i++; skipWs(); continue }
            expect(']')
            return
        }
    }

    /** 一个 Feature：收集 name/adcode 与几何，最后把名字补到几何上（两者顺序不定）。 */
    private fun readFeature(out: MutableList<CityShapes.Shape>) {
        expect('{')
        var name: String? = null
        var adcode = 0
        val pending = ArrayList<CityShapes.Shape>(2)

        while (true) {
            skipWs()
            if (peek() == '}') { i++; break }
            val key = readString()
            skipWs()
            expect(':')
            skipWs()
            when (key) {
                "properties" -> readProperties { n, a -> if (n != null) { name = n; adcode = a } }
                "geometry" -> pending += readGeometry()
                else -> skipValue()
            }
            skipWs()
            if (peek() == ',') { i++; continue }
            expect('}')
            break
        }

        // 没名字的几何没法跟城市表对上，直接丢（与旧实现「查不到就跳过」同一口径）
        val resolved = name ?: return
        pending.forEach { out += it.copyWithName(resolved, adcode) }
    }

    private fun readProperties(onProps: (String?, Int) -> Unit) {
        expect('{')
        var name: String? = null
        var adcode = 0
        while (true) {
            skipWs()
            if (peek() == '}') { i++; break }
            val key = readString()
            skipWs()
            expect(':')
            skipWs()
            when (key) {
                "name" -> name = readString()
                "adcode" -> adcode = readNumber().toInt()
                else -> skipValue()
            }
            skipWs()
            if (peek() == ',') { i++; continue }
            expect('}')
            break
        }
        onProps(name, adcode)
    }

    /** geometry：type 可能先于 coordinates 出现（我们自己的生成脚本就是如此）。 */
    private fun readGeometry(): List<CityShapes.Shape> {
        expect('{')
        var type: String? = null
        val shapes = ArrayList<CityShapes.Shape>(1)
        while (true) {
            skipWs()
            if (peek() == '}') { i++; break }
            val key = readString()
            skipWs()
            expect(':')
            skipWs()
            when (key) {
                "type" -> type = readString()
                "coordinates" -> when (type) {
                    "Polygon" -> readPolygon(shapes)
                    "MultiPolygon" -> readMultiPolygon(shapes)
                    // 类型未知（点/线或顺序颠倒时）：跳过坐标，别把解析带崩
                    else -> skipValue()
                }
                else -> skipValue()
            }
            skipWs()
            if (peek() == ',') { i++; continue }
            expect('}')
            break
        }
        return shapes
    }

    /** Polygon：coordinates = [外环, 洞…]。 */
    private fun readPolygon(out: MutableList<CityShapes.Shape>) {
        expect('[')
        val rings = ArrayList<DoubleArray>(2)
        skipWs()
        if (peek() == ']') { i++; return }
        while (true) {
            rings += readRing()
            skipWs()
            if (peek() == ',') { i++; skipWs(); continue }
            expect(']')
            break
        }
        addPolygon(rings, out)
    }

    /** MultiPolygon：coordinates = [多边形…]，每个多边形又是一组环。 */
    private fun readMultiPolygon(out: MutableList<CityShapes.Shape>) {
        expect('[')
        skipWs()
        if (peek() == ']') { i++; return }
        while (true) {
            val rings = ArrayList<DoubleArray>(2)
            expect('[')
            skipWs()
            if (peek() == ']') { i++ } else {
                while (true) {
                    rings += readRing()
                    skipWs()
                    if (peek() == ',') { i++; skipWs(); continue }
                    expect(']')
                    break
                }
            }
            addPolygon(rings, out)
            skipWs()
            if (peek() == ',') { i++; skipWs(); continue }
            expect(']')
            return
        }
    }

    private fun addPolygon(rings: List<DoubleArray>, out: MutableList<CityShapes.Shape>) {
        val outer = rings.firstOrNull() ?: return
        if (outer.size < 6) return // 少于 3 个点闭合不成面
        out += newShape("", 0, outer, rings.drop(1))
    }

    private fun readRing(): DoubleArray {
        expect('[')
        val buffer = RingBuffer()
        skipWs()
        if (peek() == ']') { i++; return buffer.toArray() }
        while (true) {
            expect('[')
            skipWs()
            buffer.add(readNumber())
            skipWs()
            expect(',')
            skipWs()
            buffer.add(readNumber())
            skipWs()
            expect(']')
            skipWs()
            if (peek() == ',') { i++; skipWs(); continue }
            expect(']')
            break
        }
        return buffer.toArray()
    }

    // ── 基础读取 ──

    private fun peek(): Char = s[i]

    private fun skipWs() {
        while (i < s.length) {
            val c = s[i]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++ else return
        }
    }

    private fun expect(c: Char) {
        if (i >= s.length || s[i] != c) throw IllegalStateException("GeoJSON 结构不符：位置 $i 期望 '$c'")
        i++
    }

    private fun readString(): String {
        expect('"')
        val sb = StringBuilder(16)
        while (i < s.length) {
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) break
                    when (val esc = s[i++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 <= s.length) {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                        }
                        else -> sb.append(esc)
                    }
                }
                else -> sb.append(c)
            }
        }
        throw IllegalStateException("GeoJSON 字符串未闭合：位置 $i")
    }

    private fun readNumber(): Double {
        val start = i
        if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
        while (i < s.length) {
            val c = s[i]
            if ((c in '0'..'9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++ else break
        }
        return s.substring(start, i).toDouble()
    }

    /** 跳过任意 JSON 值（未知字段/未知几何类型）。 */
    private fun skipValue() {
        when (peek()) {
            '{', '[' -> {
                val open = s[i]
                val close = if (open == '{') '}' else ']'
                var depth = 0
                while (i < s.length) {
                    val c = s[i]
                    if (c == '"') { skipString() ; continue }
                    if (c == open) depth++
                    if (c == close) {
                        depth--
                        i++
                        if (depth == 0) return
                        continue
                    }
                    i++
                }
                throw IllegalStateException("GeoJSON 容器未闭合：位置 $i")
            }
            '"' -> skipString()
            else -> {
                while (i < s.length && s[i] != ',' && s[i] != '}' && s[i] != ']') i++
            }
        }
    }

    private fun skipString() {
        expect('"')
        while (i < s.length) {
            val c = s[i++]
            if (c == '\\') { i++; continue }
            if (c == '"') return
        }
        throw IllegalStateException("GeoJSON 字符串未闭合：位置 $i")
    }

    /** 可增长的 double 缓冲：避免 `ArrayList<Double>` 的装箱（四十多万个点会很明显）。 */
    private class RingBuffer {
        private var data = DoubleArray(1024)
        private var size = 0

        fun add(value: Double) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): DoubleArray = data.copyOf(size)
    }

    private fun newShape(
        name: String,
        adcode: Int,
        outer: DoubleArray,
        holes: List<DoubleArray>,
    ): CityShapes.Shape {
        var minLng = Double.MAX_VALUE
        var minLat = Double.MAX_VALUE
        var maxLng = -Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var p = 0
        while (p < outer.size) {
            val lng = outer[p]
            val lat = outer[p + 1]
            if (lng < minLng) minLng = lng
            if (lng > maxLng) maxLng = lng
            if (lat < minLat) minLat = lat
            if (lat > maxLat) maxLat = lat
            p += 2
        }
        return CityShapes.Shape(name, adcode, outer, holes, minLng, minLat, maxLng, maxLat)
    }
}

/** 解析时还不知道名字的临时形状：拿到 properties 之后再补名字。 */
private fun CityShapes.Shape.copyWithName(name: String, adcode: Int): CityShapes.Shape =
    CityShapes.Shape(name, adcode, outer, holes, minLng, minLat, maxLng, maxLat)
