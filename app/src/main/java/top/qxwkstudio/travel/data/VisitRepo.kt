package top.qxwkstudio.travel.data

import kotlinx.serialization.Serializable
import top.qxwkstudio.travel.Api
import top.qxwkstudio.travel.logic.SiteStats
import top.qxwkstudio.travel.logic.Visit
import top.qxwkstudio.travel.logic.VisitDraft
import top.qxwkstudio.travel.net.Http

/**
 * 足迹接口（全部在 Api.TRAVEL_BASE 下）。失败一律抛 [ApiException]，
 * **401 不在这一层处理** —— 由 ui/Session.expired 统一「清 token 回登录页」，
 * 各页面自己弹错的话，用户在失效会话里会被三个页面各弹一次。
 *
 * 报文的字段名与类型都交给 logic/Models 里的 @Serializable 模型表达，
 * 这里不再逐字段 optXxx（原来那套手写取值与兜底已删掉）。
 */
object VisitRepo {

    /** /api/my-visits 的报文：`{ "visits": [...] }`。缺字段当空列表（与旧 optJSONArray 的兜底一致）。 */
    @Serializable
    private data class MyVisitsBody(val visits: List<Visit> = emptyList())

    /** GET /api/my-visits → 已按 created_at DESC, id DESC 排好序（后端排的，客户端不再重排）。 */
    fun myVisits(token: String): List<Visit> {
        val result = Http.request("GET", Api.MY_VISITS, token = token)
        if (!result.ok) throw apiException(result, "获取足迹失败（HTTP ${result.code}）")
        return runCatching {
            json.decodeFromString(MyVisitsBody.serializer(), result.body).visits
        }.getOrDefault(emptyList())
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
        // 解析失败给一句人话，别把序列化库那串又长又英文的异常甩给用户
        return runCatching { json.decodeFromString(SiteStats.serializer(), result.body) }
            .getOrElse { throw ApiException(result.code, "统计内容无法解析") }
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
     * 请求体 = 直接序列化 [VisitDraft]，它的 @SerialName 与后端 POST/PUT 的取字段方式一一对应
     * （worker.js:122-127 / 156-161）：
     *  - `is_private` 按后端要的 0/1 发（见 logic/Models 的 IntBooleanSerializer）；
     *  - `visit_date` 为 null 时**显式**发 `"visit_date":null` —— PUT 是整体替换
     *    （worker.js:167 的 SET 里带着 visit_date = ?），显式 null 让「把日期清空」在报文里看得见。
     */
    private fun bodyOf(draft: VisitDraft): String = json.encodeToString(VisitDraft.serializer(), draft)
}
