package top.qxwkstudio.travel.logic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 时间线事件的四种类型（2.0 的行程 = 一个有序事件数组，不再是「城市 + 年月」一条记录）。
 *
 * **跨端/跨版本契约**：`@SerialName` 的值就是落进 `trips.timeline` 那份 JSON 里的 `type`，
 * 改一个字就等于改数据格式 —— 存量行会解不出来。要加类型就新增，不要改名。
 *
 * 四种类型各自的必填字段见 [Timeline.validate]。
 */
@Serializable
enum class TimelineType {
    /** 到访：必填城市（[TimelineEvent.cityName]，`cityCode` 可空）。 */
    @SerialName("visit")
    VISIT,

    /** 乘坐：必填交通方式（自由文本，≤ [Limits.SHORT]）。 */
    @SerialName("ride")
    RIDE,

    /** 进行：必填活动内容（≤ [Limits.SHORT]）。 */
    @SerialName("activity")
    ACTIVITY,

    /** 前往：必填地点名（≤ [Limits.SHORT]）与坐标。 */
    @SerialName("goto")
    GOTO,
}

/**
 * 时间线上的一个节点。
 *
 * **时间存 UTC 毫秒**（`time`），另存记录时的时区偏移 `tz`（分钟）：只存 UTC 的话，
 * 跨时区旅行后「当时是几点」就还原不出来了；只存本地时间的话，排序又会因为时区而错。
 * 展示 = `time` 按 `tz` 折算，排序 = 直接用 `time`。
 *
 * 为什么不是 `java.time.Instant`：minSdk 是 24，`java.time` 要开 core library desugaring，
 * 为几个日期格式化引入一整套脱糖不值得；Long 毫秒在 JVM 单测里也更好喂。
 *
 * [id] 是客户端生成的稳定标识（不用自增）：编辑页重排/删除后序号会变，
 * 靠它才能定位「改的是哪一条」。同一个时间线里必须唯一（编辑页按它取节点）。
 *
 * [costCents] 用**分**：`null` = 没填（界面不渲染这一行），`0` = 合法的「花了 0 元」。
 */
@Serializable
data class TimelineEvent(
    val id: String,
    val time: Long,
    val type: TimelineType,
    val tz: Int? = null,
    @SerialName("cityName") val cityName: String? = null,
    @SerialName("cityCode") val cityCode: Int? = null,
    val transport: String? = null,
    val activity: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val place: String? = null,
    val costCents: Int? = null,
)

/**
 * 一条行程的时间线：有序事件数组 + 几处「只读派生」。
 *
 * 纯 Kotlin、不碰 android.*、不碰数据库 —— 校验与派生逻辑要能直接在 JVM 单测里跑
 * （见 `src/test/.../logic/TimelineTest.kt`）。落库时整段序列化成 JSON 存进 `trips.timeline`，
 * 起止时间与总花销另存冗余列（列表排序不解析 JSON，见 data/db/Entities.kt 的 TripEntity）。
 *
 * **有序**是这个类的不变量：构造时不做校验（草稿/记录中允许空与乱序），
 * 但任何要落库或展示的入口都必须先过 [validate] —— 逆序保存是明确禁止的。
 */
class Timeline(val events: List<TimelineEvent>) {

    /**
     * 校验，返回给人看的第一条错误文案；`null` 表示通过。
     *
     * [requireEvents]：已保存的行程至少要有一个节点（否则地图上没有任何城市）；
     * 草稿/记录中允许为空 —— 刚开始记录时节点数就是 0。
     *
     * 两条**刻意**的取舍：
     *  - 时间允许**相等**（同一分钟发生的两件事很常见），只禁止逆序；
     *  - 长度按 [Limits] 的字符数算，与输入框 `maxLength` 同一口径，不另做 trim 后的再校验。
     */
    fun validate(requireEvents: Boolean = true): String? {
        if (events.isEmpty()) return if (requireEvents) "至少添加一条事件" else null
        events.forEachIndexed { index, event ->
            val no = index + 1
            when (event.type) {
                TimelineType.VISIT ->
                    if (event.cityName.isNullOrBlank()) return "第 $no 条：请选择城市"

                TimelineType.RIDE ->
                    if (event.transport.isNullOrBlank()) return "第 $no 条：请填写交通方式"

                TimelineType.ACTIVITY ->
                    if (event.activity.isNullOrBlank()) return "第 $no 条：请填写活动内容"

                TimelineType.GOTO -> {
                    if (event.place.isNullOrBlank()) return "第 $no 条：请填写地点名称"
                    if (event.lat == null || event.lng == null) return "第 $no 条：缺少坐标"
                }
            }
            // 文本长度：超了不该静默截断（用户会以为存进去了），在这里拦下
            val tooLong = when (event.type) {
                TimelineType.RIDE -> event.transport
                TimelineType.ACTIVITY -> event.activity
                TimelineType.GOTO -> event.place
                TimelineType.VISIT -> null
            }?.let { if (it.length > Limits.SHORT) it else null }
            if (tooLong != null) return "第 $no 条：内容超过 ${Limits.SHORT} 字"

            if (index > 0 && event.time < events[index - 1].time) {
                return "第 $no 条：时间早于上一条（时间线必须按时间先后排列）"
            }
        }
        return null
    }

    /** 首个节点的时间。调用方必须保证已通过 [validate]（或至少非空）。 */
    val startTime: Long get() = events.first().time

    /** 末个节点的时间。 */
    val endTime: Long get() = events.last().time

    /** 各节点花销之和（没填的算 0）。 */
    val totalCostCents: Int get() = events.sumOf { it.costCents ?: 0 }

    /**
     * 派生打卡用：同一座城市只取**首次**到访。
     *
     * 返回 LinkedHashMap（按事件顺序），调用方据此逐个生成 `source = 派生` 的打卡 ——
     * 「一条行程里同一城市去了两次」只记一条，与 1.x 的「一座城市多次打卡只算一座」同一口径。
     */
    fun firstVisitByCity(): Map<String, TimelineEvent> {
        val out = LinkedHashMap<String, TimelineEvent>()
        for (event in events) {
            if (event.type != TimelineType.VISIT) continue
            val name = event.cityName?.trim().orEmpty()
            if (name.isEmpty()) continue
            out.putIfAbsent(name, event)
        }
        return out
    }

    /** 落库用：整段序列化成 JSON 文本（存进 `trips.timeline`）。 */
    fun encode(): String = json.encodeToString(listSerializer, events)

    companion object {
        /**
         * 反序列化。**解析失败返回 null，不抛异常**：库里存的是历史版本的 JSON，
         * 读坏了应当按「这条行程的时间线不可用」处理（由调用方决定降级），
         * 而不是让一次列表刷新整体崩掉。
         */
        fun decode(raw: String): Timeline? = runCatching {
            Timeline(json.decodeFromString(listSerializer, raw))
        }.getOrNull()

        private val listSerializer =
            kotlinx.serialization.builtins.ListSerializer(TimelineEvent.serializer())

        /**
         * 与 `data/Json.kt` 同口径的两个开关：未知键忽略（新版本写的行、旧版本也要能读）、
         * 缺省值不写回（`encodeDefaults = false` 让 JSON 更短；读的时候有默认值兜底）。
         * 这里自带一份而不是复用 `data/Json.kt`：logic 包不反向依赖 data 包（那条边界见 Models.kt 的说明）。
         */
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}
