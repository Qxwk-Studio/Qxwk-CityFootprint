package top.qxwkstudio.travel.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * org.json 的取值小工具（Android 自带 org.json，不额外引 JSON 库）。
 *
 * 为什么要包一层：`JSONObject.optString(name)` 在**字段存在但值是 JSON null** 时返回的是
 * 字符串 "null"（Android 的实现走 JSON.toString(JSONObject.NULL)），直接拿去用就会得到
 * 「昵称叫 null」「日期显示成 null」。这里统一先 isNull 再取值，缺字段与 null 一律当 null。
 */
internal fun JSONObject.strOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

internal fun JSONObject.longOrZero(name: String): Long = if (isNull(name)) 0L else optLong(name)

internal fun JSONObject.intOrZero(name: String): Int = if (isNull(name)) 0 else optInt(name)

internal fun JSONObject.boolOrFalse(name: String): Boolean = !isNull(name) && optBoolean(name)

/** 坐标用 Double 而不是 Int：县级市的经纬度小数位多，optInt 会把 39.904 变成 39。 */
internal fun JSONObject.doubleOrNull(name: String): Double? =
    if (isNull(name)) null else optDouble(name).takeIf { !it.isNaN() }

internal fun JSONArray.strList(): List<String> =
    (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotEmpty() } }