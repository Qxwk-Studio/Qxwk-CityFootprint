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
 * GET /api/cities（地图数据）的一座城市：坐标 + 去过的人（昵称/颜色）。
 * 日期/备注/私密这些明细**不在这里**（后端刻意不铺开），点开城市时再调 /api/city/{城市名} 按需拉，
 * 与网页 docs/index.html 的 renderFilter / loadCityDetail 是同一口径。
 */
@Serializable
data class MapCity(
    val city: String = "",
    /** 旧行可能没有（加列前写入的），后端会用城市字典兜底；都拿不到时地图退回圆点。 */
    val adcode: Int? = null,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** 按 created_at 升序（末位 = 最新，后端排的）—— 地图取**末位**的颜色给这座城市上色。 */
    val people: List<MapPerson> = emptyList(),
)

@Serializable
data class MapPerson(val nickname: String = "", val color: String = "")

@Serializable
data class CitiesResponse(val cities: List<MapCity> = emptyList(), val isAdmin: Boolean = false)

/** GET /api/city/{城市名}：该城市的**全部**行程（后端按 created_at 倒序），截取最近 10 条由客户端做。 */
@Serializable
data class CityVisitsResponse(val city: String = "", val visits: List<CityVisit> = emptyList())

/**
 * 一条城市明细（地图底部卡片用）。字段对应 worker.js 的 /api/city/:city：
 *   {nickname, color, visit_date, note, is_private, adcode, transport[]}
 * is_private 与 [Visit] 一样按 0/1 收成 Boolean。
 */
@Serializable
data class CityVisit(
    val nickname: String = "",
    val color: String = "",
    @SerialName("visit_date") val visitDate: String? = null,
    val note: String = "",
    @SerialName("is_private")
    @Serializable(with = IntBooleanSerializer::class)
    val isPrivate: Boolean = false,
    val adcode: Int? = null,
    val transport: List<String> = emptyList(),
)

/**
 * App 清单：一处读它三件事 —— 「我的 → 检查更新」比 [android] 的 version_code，
 * 主页横幅与「我的 → 公告」看 [notices]，主页右上角三横菜单看 [menu]。
 *
 * 数据在后端 D1（cf_app_version 含更新说明 notes 列 / cf_notices / cf_menu），
 * 由 `GET /api/manifest` 一次返回（见 [top.qxwkstudio.travel.Api.VERSION_MANIFEST]）。
 * 字段名（这里的 @SerialName）与后端 worker.js 那个接口是**跨端契约**，改一处必须两端一起改。
 *
 * 外层留一层 `android`：这份清单将来也可能记网页 / iOS 的版本；现在只有安卓会读，
 * 多出来的键靠 data/Json.kt 的 ignoreUnknownKeys 兜住。
 */
@Serializable
data class VersionManifest(
    val android: ReleaseInfo = ReleaseInfo(),
    val notices: List<Notice> = emptyList(),
    val menu: List<MenuItem> = emptyList(),
)

/**
 * 抽屉里的一个栏目（清单的 `menu` 数组，库里是 cf_menu 表）：一条 = 一行 = 一个网页。
 *
 * 两个字段**都必填**，缺一个整条就不显示（见 MainActivity.renderMenu）：
 * 这个数组是手写的（往 cf_menu 里加行），写漏了就该看不见，而不是把用户点进一个空白页或错误页。
 * 也**不支持**「只填标题、点了做原生动作」这种扩展 —— 真有那种需求再加字段，
 * 现在就按「一行一个网页」这一种形态。
 */
@Serializable
data class MenuItem(
    val title: String = "",
    val url: String = "",
)

/**
 * 一条公告（清单的 `notices` 数组，没有公告时就是空数组）。
 *
 * [id] 是**自增整数**，App 拿它记「读到哪一条了」（见 data/Store.noticeReadId）——
 * 所以维护这个清单（往 cf_notices 加行）时只能往上加，**别改已有的 id**，改小会让读过的公告又变成未读。
 * 判定用 id 而不是日期：日期是给人看的，手滑写错一个月的格式也不该影响「有没有新公告」。
 *
 * [body] 是一整段正文（与网页 news.html 的 `.notice-text` 一样是一段），
 * 不做多段 / 富文本：稿子要分段就拆成两条公告。
 */
@Serializable
data class Notice(
    val id: Int = 0,
    val title: String = "",
    val date: String = "",
    val body: String = "",
)

/**
 * 清单里的安卓那一节：`{version_name, version_code, download_url, notes}`。
 *
 * **比对用 [versionCode] 而不是 [versionName]**：版本名是给人看的，
 * 「1.2.10 与 1.2.9 谁大」拿字符串比一定答错，而 CI 发版时填的 versionCode 是单调递增的整数。
 * 各字段都有默认值（配 data/Json.kt 的 coerceInputValues）：缺字段不抛异常，
 * [versionCode] 缺省为 0 就等同「没有新版本」—— 宁可漏一次提示，也别因为清单写漏一个键就把所有人推向浏览器。
 *
 * [notes] 是更新说明，**一行一条**（弹窗里逐条列出来）。后端把 cf_app_version.notes 那一列的多行文本
 * 按 \n 拆成这个数组，所以加减一行说明就是改那一列里的一段文本。
 */
@Serializable
data class ReleaseInfo(
    @SerialName("version_name") val versionName: String = "",
    @SerialName("version_code") val versionCode: Int = 0,
    @SerialName("download_url") val downloadUrl: String = "",
    val notes: List<String> = emptyList(),
)

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
