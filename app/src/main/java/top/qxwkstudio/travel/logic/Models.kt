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
 * 所以这些模型仍然能在 JVM 上直接跑单测（见 src/test 下的日期 / 城市搜索测试）。
 * @SerialName 只是把后端的 snake_case 字段名挂到属性上；判定逻辑（VisitDate / CitySearch）
 * 拿到的仍是普通 data class，解析细节不会渗进判定里。
 * 成就**没有**客户端判定：名称/图标/阈值全在后端 backend/src/achievements.js，
 * 这里只声明接口回给我们的形状（见下面的 AchievementGroup 说明）。
 *
 * 字段名/类型对齐后端（backend/src/worker.js）：
 *   my-visits：{visits: [{id, city, lat, lng, visit_date, note, is_private, transport[]}], achievements[]}
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
    /** 出行方式 code 数组（后端已按白名单顺序排好，并把库里那串 JSON 还原成数组）。 */
    val transport: List<String> = emptyList(),
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
    /**
     * 出行方式（可多选，存 code 数组）。后端 [worker.js pickTransports] 会拿白名单过滤一遍，
     * 所以这里发出去的即使带脏 code 也落不了库 —— 客户端不做「过滤」这层假动作，只管收集用户勾的。
     */
    val transport: List<String> = emptyList(),
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
 * GET /api/my-visits 的完整报文：我的行程 + 我的成就。
 *
 * 成就由后端 dispatch worker.js 就着**已经取出来的那批行**就地判定后一起回给我们
 * （backend/src/achievements.js 的 getAchievements），客户端**不再自带一份判定表**：
 * 名字/图标/阈值改一次就只改后端一个文件，App 与网页不会各算一个结果。
 */
@Serializable
data class MyVisits(
    val visits: List<Visit> = emptyList(),
    val achievements: List<AchievementGroup> = emptyList(),
)

/**
 * 一个成就分类（如「🌟 足迹丰碑」）+ 它下面的条目。
 * 顺序即后端数组顺序（分类顺序、条目顺序都是后端定的），客户端不重排。
 */
@Serializable
data class AchievementGroup(
    val title: String = "",
    val items: List<Achievement> = emptyList(),
)

/**
 * 一条成就（「我的成就」页用）。[done] 由后端按「我去过哪些城市」判定。
 * 后端的 code（稳定标识）只在服务端按 code 累计达成人数时有用，客户端按数组顺序渲染，故不声明。
 */
@Serializable
data class Achievement(
    val icon: String = "",
    val name: String = "",
    val desc: String = "",
    val done: Boolean = false,
)

/**
 * 「全站统计」页的成就达成人数：形状与 [AchievementGroup] 一样，只是每条的 done 换成 [AchievementCount.count]。
 * 单独一组模型而不是复用上面那对：done（我有没有达成）与 count（多少人达成）是两件事，
 * 塞进同一个类里会让「这个字段在这一页有没有意义」变成要靠约定记住的事。
 */
@Serializable
data class AchievementCountGroup(
    val title: String = "",
    val items: List<AchievementCount> = emptyList(),
)

@Serializable
data class AchievementCount(
    val icon: String = "",
    val name: String = "",
    val desc: String = "",
    val count: Int = 0,
)

/**
 * GET /api/stats（公开接口）。
 * 字段名照抄 worker.js 里 /api/stats 的实现，别猜：
 *   cityRank:     [{city, count, people}]   count = 打卡次数，people = 打卡人数
 *   achievements: [{title, items:[{icon, name, desc, count}]}]   count = 达成人数
 * 注意**没有** users[] 明细了（worker.js:168 的说明）：成就达成人数由后端算好，
 * 只回一个 totalUsers 数字 —— 客户端不再需要 users[].cities 去本地判定。
 */
@Serializable
data class SiteStats(
    val totalVisits: Int = 0,
    val totalCities: Int = 0,
    val totalUsers: Int = 0,
    val cityRank: List<CityRank> = emptyList(),
    val achievements: List<AchievementCountGroup> = emptyList(),
    val isAdmin: Boolean = false,
)

@Serializable
data class CityRank(val city: String = "", val count: Int = 0, val people: Int = 0)

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
