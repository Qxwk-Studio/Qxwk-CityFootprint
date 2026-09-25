package top.qxwkstudio.travel.data

import org.json.JSONArray
import org.json.JSONObject
import top.qxwkstudio.travel.Api
import top.qxwkstudio.travel.logic.CityRank
import top.qxwkstudio.travel.logic.SiteStats
import top.qxwkstudio.travel.logic.UserStat
import top.qxwkstudio.travel.logic.Visit
import top.qxwkstudio.travel.logic.VisitDraft
import top.qxwkstudio.travel.net.Http

/**
 * 足迹接口（全部在 Api.TRAVEL_BASE 下）。失败一律抛 [ApiException]，
 * **401 不在这一层处理** —— 由 ui/Session.expired 统一「清 token 回登录页」，
 * 各页面自己弹错的话，用户在失效会话里会被三个页面各弹一次。
 */
object VisitRepo {

    /** GET /api/my-visits → 已按 created_at DESC, id DESC 排好序（后端排的，客户端不再重排）。 */
    fun myVisits(token: String): List<Visit> {
        val result = Http.request("GET", Api.MY_VISITS, token = token)
        if (!result.ok) throw apiException(result, "获取足迹失败（HTTP ${result.code}）")
        val visits = runCatching { JSONObject(result.body).optJSONArray("visits") }.getOrNull()
            ?: return emptyList()
        return (0 until visits.length()).map { visits.getJSONObject(it).toVisit() }
    }

    /** POST /api/visits → 201。 */
    fun create(token: String, draft: VisitDraft) {
        val result = Http.request("POST", Api.VISITS, token = token, jsonBody = bodyOf(draft))
        if (result.code != 201) throw apiException(result, "添加失败（HTTP ${result.code}）")
    }

    /** PUT /api/visits/{id}（只能改自己的，越权时后端回 403）。 */
    fun update(token: String, id: Long, draft: VisitDraft) {
        val result = Http.request("PUT", "${Api.VISITS}/$id", token = token, jsonBody = bodyOf(draft))
        if (!result.ok) throw apiException(result, "保存失败（HTTP ${result.code}）")
    }

    /** DELETE /api/visits/{id}。 */
    fun delete(token: String, id: Long) {
        val result = Http.request("DELETE", "${Api.VISITS}/$id", token = token)
        if (!result.ok) throw apiException(result, "删除失败（HTTP ${result.code}）")
    }

    /** GET /api/stats（公开，不需要 token）。 */
    fun stats(): SiteStats {
        val result = Http.request("GET", Api.STATS)
        if (!result.ok) throw apiException(result, "统计加载失败（HTTP ${result.code}）")
        val json = JSONObject(result.body)
        return SiteStats(
            totalVisits = json.intOrZero("totalVisits"),
            totalCities = json.intOrZero("totalCities"),
            cityRank = json.optJSONArray("cityRank").toRankList(),
            users = json.optJSONArray("users").toUserList(),
            isAdmin = json.boolOrFalse("isAdmin"),
        )
    }

    /**
     * GET /api/geo/{adcode}（公开，后端已做 24 小时缓存）→ GeoJSON 原文。
     * 刻意**不在这里解析**：边界数据结构深、且只有地图页用得到，
     * 解析交给 ui/GeoJson（那边还要处理解析失败就只留标记的降级）。
     */
    fun geoJson(adcode: Int): String {
        val result = Http.request("GET", Api.geo(adcode))
        if (!result.ok) throw apiException(result, "边界数据获取失败（HTTP ${result.code}）")
        return result.body
    }

    /**
     * 提交体与后端 POST/PUT 的取字段方式一一对应（worker.js:138-147 / 171-181）：
     * city 走 `body.city || ''`，所以空城市名到后端也只会变成「请选择城市」；
     * note 后端会 slice(0,100)，客户端先用 maxLength 挡住；is_private 后端按真值转 0/1。
     *
     * visit_date **显式发 null 而不是省略字段**：PUT 是整体替换（worker.js:183 的 SET 里带着
     * visit_date = ?），省略字段的写法在这里等价，但显式发 null 让「把日期清空」这件事在报文里看得见。
     */
    private fun bodyOf(draft: VisitDraft): String {
        val json = JSONObject()
            .put("city", draft.city)
            .put("lat", draft.lat)
            .put("lng", draft.lng)
            .put("note", draft.note)
            .put("is_private", draft.isPrivate)
        json.put("visit_date", draft.visitDate ?: JSONObject.NULL)
        return json.toString()
    }

    private fun JSONObject.toVisit(): Visit = Visit(
        id = longOrZero("id"),
        city = strOrNull("city").orEmpty(),
        lat = doubleOrNull("lat") ?: 0.0,
        lng = doubleOrNull("lng") ?: 0.0,
        visitDate = strOrNull("visit_date"),
        note = strOrNull("note").orEmpty(),
        // 后端存的是 0/1（D1 里没有布尔类型），这里翻成 Boolean，界面不再关心它是几
        isPrivate = intOrZero("is_private") != 0,
    )

    private fun JSONArray?.toRankList(): List<CityRank> {
        if (this == null) return emptyList()
        return (0 until length()).map { i ->
            val o = getJSONObject(i)
            CityRank(
                city = o.strOrNull("city").orEmpty(),
                count = o.intOrZero("count"),
                people = o.intOrZero("people"),
            )
        }
    }

    private fun JSONArray?.toUserList(): List<UserStat> {
        if (this == null) return emptyList()
        return (0 until length()).map { i ->
            val o = getJSONObject(i)
            UserStat(
                nickname = o.strOrNull("nickname").orEmpty(),
                color = o.strOrNull("color").orEmpty(),
                // 后端给的是 GROUP_CONCAT 拼出来的一个字符串（worker.js:100），空串当「没有城市」
                cities = o.strOrNull("cities")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList(),
            )
        }
    }
}