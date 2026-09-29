package top.qxwkstudio.travel.data

import android.content.Context
import top.qxwkstudio.travel.Api
import top.qxwkstudio.travel.logic.CitiesResponse
import top.qxwkstudio.travel.logic.CityVisitsResponse
import top.qxwkstudio.travel.logic.MyVisits
import top.qxwkstudio.travel.logic.SiteStats
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

    /**
     * GET /api/my-visits → 行程已按 created_at DESC, id DESC 排好序（后端排的，客户端不再重排），
     * 成就是后端按这批行就地判定后一起回来的（所以「我的成就」页也只用这一个请求）。
     *
     * **带一天的本机缓存**（见 [Store.cachedPayload]）：[force] = false 时先看缓存，命中就直接返回、
     * 一个请求都不发；下拉刷新传 true 强制走网络并刷新缓存。缓存解析不过（旧版本报文）就当没有缓存。
     * 写操作（增删改）之后必须把缓存作废，否则这里会把「刚改完却还是旧数据」的报文交出去 ——
     * 作废点在调用方（ui/VisitEditActivity、ui/VisitsFragment），那边本来就持有 Store。
     */
    fun myVisits(context: Context, token: String, force: Boolean = false): MyVisits {
        val store = Store(context)
        if (!force) {
            store.cachedPayload(CACHE_MY_VISITS)?.let { cached -> parseMyVisits(cached)?.let { return it } }
        }
        val result = Http.request("GET", Api.MY_VISITS, token = token)
        if (!result.ok) throw apiException(result, "获取足迹失败（HTTP ${result.code}）")
        store.savePayload(CACHE_MY_VISITS, result.body)
        // 解析失败仍然给个空对象：接口是通的，只是内容对不上，界面按「没有足迹」显示
        return parseMyVisits(result.body) ?: MyVisits()
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

    /**
     * GET /api/stats（公开，不需要 token）→ 带一天的本机缓存，规则与 [myVisits] 一致
     * （[force] = false 时优先命中缓存；下拉刷新传 true）。
     */
    fun stats(context: Context, force: Boolean = false): SiteStats {
        val store = Store(context)
        if (!force) {
            store.cachedPayload(CACHE_STATS)?.let { cached -> parseStats(cached)?.let { return it } }
        }
        val result = Http.request("GET", Api.STATS)
        if (!result.ok) throw apiException(result, "统计加载失败（HTTP ${result.code}）")
        // 解析失败给一句人话，别把序列化库那串又长又英文的异常甩给用户
        val stats = parseStats(result.body) ?: throw ApiException(result.code, "统计内容无法解析")
        store.savePayload(CACHE_STATS, result.body)
        return stats
    }

    /** 解析 /api/my-visits 报文；结构对不上回 null（缓存那边用它判断「这份缓存还能不能用」）。 */
    private fun parseMyVisits(raw: String): MyVisits? =
        runCatching { json.decodeFromString(MyVisits.serializer(), raw) }.getOrNull()

    private fun parseStats(raw: String): SiteStats? =
        runCatching { json.decodeFromString(SiteStats.serializer(), raw) }.getOrNull()

    /**
     * GET /api/geo/{adcode}（公开，后端已做 7 天缓存）→ GeoJSON 原文。
     * 刻意**不在这里解析**：边界数据结构深、且只有地图页用得到，
     * 解析交给 ui/GeoJson（那边还要处理解析失败就只留标记的降级）。
     *
     * 带 7 天磁盘缓存（见 [GeoCache]，与网页 docs/index.js 的 IndexedDB 同形态）：
     * 命中且没过期直接返回、一个请求都不发；命中了但已过期**先返回旧内容**、后台重下只换文件
     * （不阻塞、不重画）；完全没缓存才走网络并顺手落盘。失败语义不变（抛 [ApiException]）。
     * 缓存**不跟登录态走** —— 边界是公共数据（见 GeoCache 的类注释）。
     */
    fun geoJson(context: Context, adcode: Int): String {
        GeoCache.read(context, adcode)?.let { cached ->
            if (cached.stale) GeoCache.refresh(context, adcode)
            return cached.raw
        }
        val result = Http.request("GET", Api.geo(adcode))
        if (!result.ok) throw apiException(result, "边界数据获取失败（HTTP ${result.code}）")
        GeoCache.write(context, adcode, result.body)
        return result.body
    }

    /**
     * GET /api/cities（公开；带 token 时自己的私密行程才可见）→ 地图数据（城市 + 坐标 + 去过的人）。
     * **不做本机缓存**：与网页一致，地图每次进页面重新取（缓存的是边界，见 data/GeoCache 与 MapFragment 的内存 geoCache）。
     * 解析不过给空对象：接口是通的、只是内容对不上，界面按「没有足迹」显示。
     */
    fun cities(token: String?): CitiesResponse {
        val result = Http.request("GET", Api.CITIES, token = token)
        if (!result.ok) throw apiException(result, "地图数据获取失败（HTTP ${result.code}）")
        return parseCities(result.body) ?: CitiesResponse()
    }

    /** GET /api/city/{城市名}（公开）→ 该城市的全部行程。解析不过给空对象（卡片显示「没有符合条件的行程」）。 */
    fun cityVisits(city: String, token: String?): CityVisitsResponse {
        val result = Http.request("GET", Api.city(city), token = token)
        if (!result.ok) throw apiException(result, "城市明细获取失败（HTTP ${result.code}）")
        return parseCityVisits(result.body) ?: CityVisitsResponse()
    }

    private fun parseCities(raw: String): CitiesResponse? =
        runCatching { json.decodeFromString(CitiesResponse.serializer(), raw) }.getOrNull()

    private fun parseCityVisits(raw: String): CityVisitsResponse? =
        runCatching { json.decodeFromString(CityVisitsResponse.serializer(), raw) }.getOrNull()

    /**
     * 请求体 = 直接序列化 [VisitDraft]，它的 @SerialName 与后端 POST/PUT 的取字段方式一一对应
     * （worker.js:122-127 / 156-161）：
     *  - `is_private` 按后端要的 0/1 发（见 logic/Models 的 IntBooleanSerializer）；
     *  - `visit_date` 为 null 时**显式**发 `"visit_date":null` —— PUT 是整体替换
     *    （worker.js:167 的 SET 里带着 visit_date = ?），显式 null 让「把日期清空」在报文里看得见。
     */
    private fun bodyOf(draft: VisitDraft): String = json.encodeToString(VisitDraft.serializer(), draft)

    /** 报文缓存的 key。前缀 `cache_` 是 Store.invalidatePayloads 的约定，别改动前缀。 */
    private const val CACHE_MY_VISITS = "cache_my_visits"
    private const val CACHE_STATS = "cache_stats"
}
