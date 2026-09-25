package top.qxwkstudio.travel.ui

import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint

/**
 * 阿里 DataV 边界 GeoJSON →osmdroid 的多边形点集。
 *
 * 为什么手工解析而不是引 GeoJSON 库：这份数据只需要两样东西 ——
 * geometry.type（Polygon / MultiPolygon）与 coordinates 里的经纬度点，
 * 引一个通用库要多一份依赖与 R8 规则，换来的只是省下这四十行。
 *
 * 约定：
 *  - 坐标顺序是 **[经度, 纬度]**（GeoJSON 规范），GeoPoint 的构造是 (纬度, 经度) —— 顺序写反了
 *    地图上会跑到非洲以西的大西洋里去；
 *  - 返回值是「一个面一个点环」的列表：Polygon 与 MultiPolygon 都拍平成同一形态，调用方不用管类型；
 *  - 少于 3 个点的环直接丢掉（闭合不成面，画出来什么也没有）；
 *  - 解析失败/结构不符就返回空列表 —— 地图页据此降级成「只保留标记」（见 MapFragment）。
 */
object GeoJson {

    fun polygons(json: String): List<List<GeoPoint>> {
        val features = runCatching { JSONObject(json).optJSONArray("features") }.getOrNull() ?: return emptyList()
        val result = mutableListOf<List<GeoPoint>>()
        for (i in 0 until features.length()) {
            val geometry = features.optJSONObject(i)?.optJSONObject("geometry") ?: continue
            when (geometry.optString("type")) {
                "Polygon" -> addFromPolygon(geometry.optJSONArray("coordinates"), result)
                "MultiPolygon" -> {
                    val multi = geometry.optJSONArray("coordinates") ?: continue
                    for (p in 0 until multi.length()) addFromPolygon(multi.optJSONArray(p), result)
                }
            }
        }
        return result
    }

    /** 一个 Polygon 的 coordinates = 若干「环」（第一个是外环，其余是洞）；洞也一并画出来。 */
    private fun addFromPolygon(rings: JSONArray?, out: MutableList<List<GeoPoint>>) {
        if (rings == null) return
        for (r in 0 until rings.length()) {
            val ring = rings.optJSONArray(r) ?: continue
            val points = ArrayList<GeoPoint>(ring.length())
            for (i in 0 until ring.length()) {
                val pair = ring.optJSONArray(i) ?: continue
                val lng = pair.optDouble(0, Double.NaN)
                val lat = pair.optDouble(1, Double.NaN)
                if (lat.isNaN() || lng.isNaN()) continue
                points.add(GeoPoint(lat, lng))
            }
            if (points.size >= 3) out.add(points)
        }
    }
}