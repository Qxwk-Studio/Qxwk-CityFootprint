package top.qxwkstudio.travel.logic

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 纯数据模型。**为什么可以标 @Serializable**：
 * kotlinx.serialization 的序列化器在**编译期**生成、运行时不用反射，也不依赖 android.* 或 org.json，
 * 所以这些模型仍然能在 JVM 上直接跑单测（见 src/test 下的成就 / 日期 / 城市搜索测试）。
 * @SerialName 只是把后端的 snake_case 字段名挂到属性上；判定逻辑（Achievements / VisitDate /
 * CitySearch）拿到的仍是普通 data class，解析细节不会渗进判定里。
 *
 * 字段名/类型对齐后端（backend/src/worker.js）：
 *   my-visits：{id, city, lat, lng, visit_date, note, is_private}
 *   is_private 后端是 0/1，到 Kotlin 侧就翻成 Boolean（见文件末尾的 IntBooleanSerializer），
 *   界面不再关心它是几。
 *
 * 每个属性都写了默认值：data/Json.kt 开了 `coerceInputValues`，后端漏字段或给 null 时退回默认值 ——
 * 与旧的 `optString(...).orEmpty()` / `optInt(...)` 是同一个语义。
 */
@Serializable
data class Visit(
    val id: Long = 0,
    val city: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    @SerialName("visit_date") val visitDate: String? = null,
    val note: String = "",
    @SerialName("is_private")
    @Serializable(with = IntBooleanSerializer::class)
    val isPrivate: Boolean = false,
)

/**
 * 一条待提交的足迹（新增/编辑共用）。lat/lng 由城市选择页给出，用户不会手填坐标。
 *
 * 它同时就是**请求体**：字段名（@SerialName）与后端 POST/PUT 的取字段方式一一对应
 * （worker.js:122-127 / 156-161），所以 VisitRepo 直接把实例序列化发出去，不再手拼 JSON。
 * 注意 `visit_date` 为 null 时会**显式**序列化成 `"visit_date":null`
 * （Json 的 explicitNulls 默认 true），这正是后端 PUT 整体替换时想要的语义。
 */
@Serializable
data class VisitDraft(
    val city: String,
    val lat: Double,
    val lng: Double,
    @SerialName("visit_date") val visitDate: String? = null,
    val note: String = "",
    @SerialName("is_private")
    @Serializable(with = IntBooleanSerializer::class)
    val isPrivate: Boolean = false,
)

/**
 * 通行证登录成功后落下的身份信息（token 单独存，见 data/Store）。
 * 刻意**不加 @Serializable**：登录响应的确会缺 token（空壳账号），
 * 「token 必须存在」这条不变量由 data/Auth 在解码后立刻判定，别让一个可空的 token 流到本机存储里。
 */
data class LoginSession(
    val token: String,
    val userId: Long,
    val nickname: String,
    val color: String,
    val email: String?,
    val avatar: String?,
)

/** GET /api/me（足迹后端，不是通行证那个）。token 失效时后端回 401。 */
@Serializable
data class Me(
    val userId: Long = 0,
    val nickname: String = "",
    val color: String = "",
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    val avatar: String? = null,
)

/**
 * GET /api/stats（公开接口）。
 * 字段名照抄 worker.js 里 /api/stats 的实现，别猜：
 *   cityRank: [{city, count, people}]   count = 打卡次数，people = 打卡人数
 *   users:    [{nickname, color, cities}]  cities 是后端 GROUP_CONCAT 拼出来的逗号串
 */
@Serializable
data class SiteStats(
    val totalVisits: Int = 0,
    val totalCities: Int = 0,
    val cityRank: List<CityRank> = emptyList(),
    val users: List<UserStat> = emptyList(),
    val isAdmin: Boolean = false,
)

@Serializable
data class CityRank(val city: String = "", val count: Int = 0, val people: Int = 0)

/**
 * 只保留界面用得到的两个字段：统计页只需要 `users.size`（参与人数）。
 * 后端还会返回 `cities`（逗号拼起来的城市名串），但 app 目前**不消费**它 ——
 * 不声明这个字段即可（data/Json.kt 开了 ignoreUnknownKeys，多出来的键会被忽略）。
 * 哪天「谁去过」要用到城市明细，再补一个按逗号切分的序列化器。
 */
@Serializable
data class UserStat(val nickname: String = "", val color: String = "")

/**
 * 后端 D1 里**没有布尔类型**：`is_private` 存的就是 0/1。这个序列化器把 0/1 与 Boolean 互转，
 * 于是界面与判定逻辑只面对 Boolean。
 *
 * 放在 models 文件里而不是 data/ 层：这是「数据长什么样」的定义，
 * 也让 logic 包保持自给自足（不反向依赖 data 包）。
 */
internal object IntBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("IntBoolean", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeInt(if (value) 1 else 0)

    override fun deserialize(decoder: Decoder): Boolean = decoder.decodeInt() != 0
}
