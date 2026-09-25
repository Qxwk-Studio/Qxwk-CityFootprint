package top.qxwkstudio.travel.data

import android.content.Context
import org.json.JSONObject
import top.qxwkstudio.travel.logic.City

/**
 * 城市数据（assets/cities.json）。
 *
 * 这份 JSON 是**生成物**：tools/gen-cities.mjs 从 frontend/cities.js + frontend/city-codes.js
 * 导出来，跟着仓库一起提交。app 运行时不联网取城市表、也不解析前端的 JS ——
 * 前端改了城市数据就重跑一次脚本（见 README），两端因此永远同源。
 *
 * 内存里缓存一份：总共四百多条，城市选择页每打开一次都重新解析 + 读 asset 不划算。
 */
object CityStore {
    @Volatile
    private var cache: List<City>? = null

    fun all(context: Context): List<City> {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: load(context).also { cache = it }
        }
    }

    private fun load(context: Context): List<City> {
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val array = JSONObject(text).optJSONArray("cities") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val name = o.strOrNull("name") ?: return@mapNotNull null
            val lat = o.doubleOrNull("lat") ?: return@mapNotNull null
            val lng = o.doubleOrNull("lng") ?: return@mapNotNull null
            // adcode 可能为 null（源数据里没有对应行政区划代码的县级市），保持 null 一路传下去，
            // 地图页据此降级成「只画标记」
            City(
                name = name,
                province = o.strOrNull("province").orEmpty(),
                lat = lat,
                lng = lng,
                adcode = if (o.isNull("adcode")) null else o.optInt("adcode"),
            )
        }
    }

    private const val ASSET_NAME = "cities.json"
}