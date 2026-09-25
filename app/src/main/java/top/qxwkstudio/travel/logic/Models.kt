package top.qxwkstudio.travel.logic

/**
 * 纯数据模型：**刻意不 import 任何 android.* 与 org.json** ——
 * 一是这些判定逻辑（日期、成就、搜索）要能在 JVM 上直接跑单测，二是不让解析细节渗进判定里。
 * 字段名/类型对齐后端（backend/src/worker.js）：
 *   my-visits：{id, city, lat, lng, visit_date, note, is_private}
 *   is_private 后端是 0/1，到 Kotlin 侧就翻成 Boolean，界面不再关心它是几。
 */
data class Visit(
    val id: Long,
    val city: String,
    val lat: Double,
    val lng: Double,
    val visitDate: String?,
    val note: String,
    val isPrivate: Boolean,
)

/** 一条待提交的足迹（新增/编辑共用）。lat/lng 由城市选择页给出，用户不会手填坐标。 */
data class VisitDraft(
    val city: String,
    val lat: Double,
    val lng: Double,
    val visitDate: String?,
    val note: String,
    val isPrivate: Boolean,
)

/** 通行证登录成功后落下的身份信息（token 单独存，见 data/Store）。 */
data class LoginSession(
    val token: String,
    val userId: Long,
    val nickname: String,
    val color: String,
    val email: String?,
    val avatar: String?,
)

/** GET /api/me（足迹后端，不是通行证那个）。token 失效时后端回 401。 */
data class Me(
    val userId: Long,
    val nickname: String,
    val color: String,
    val isAdmin: Boolean,
    val createdAt: String?,
    val avatar: String?,
)

/**
 * GET /api/stats（公开接口）。
 * 字段名照抄 worker.js 里 /api/stats 的实现，别猜：
 *   cityRank: [{city, count, people}]   count = 打卡次数，people = 打卡人数
 *   users:    [{nickname, color, cities}]  cities 是逗号拼起来的城市名串
 */
data class SiteStats(
    val totalVisits: Int,
    val totalCities: Int,
    val cityRank: List<CityRank>,
    val users: List<UserStat>,
    val isAdmin: Boolean,
)

data class CityRank(val city: String, val count: Int, val people: Int)

data class UserStat(val nickname: String, val color: String, val cities: List<String>)