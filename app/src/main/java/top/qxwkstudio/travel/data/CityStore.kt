package top.qxwkstudio.travel.data

import android.content.Context
import kotlinx.serialization.Serializable
import top.qxwkstudio.travel.logic.City

/**
 * 城市数据（assets/cities.json）。
 *
 * 这份 JSON 是**生成物**：tools/gen-cities.mjs 从 docs/cities.js + docs/city-codes.js
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

    /** assets/cities.json 的报文：`{ "cities": [...] }`。 */
    @Serializable
    private data class CitiesFile(val cities: List<City> = emptyList())

    private fun load(context: Context): List<City> {
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        // 这份 JSON 是生成物、生成脚本已校验过；真读到坏数据就退化成「没有城市」，
        // 而不是把整个城市选择页崩掉
        return runCatching { json.decodeFromString(CitiesFile.serializer(), text).cities }
            .getOrDefault(emptyList())
    }

    private const val ASSET_NAME = "cities.json"
}
