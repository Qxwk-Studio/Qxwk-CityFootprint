package top.qxwkstudio.travel.logic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一条行程的时间线：**双层结构** —— 外层是到访城市，内层是这座城市里发生的活动/交通。
 *
 * ```
 * Timeline(
 *   segments = [
 *     Segment("北京", [Event(活动="故宫"), Event(交通="高铁")]),
 *     Segment("上海", [Event(活动="外滩")]),
 *   ]
 * )
 * ```
 *
 * 为什么是两层：用户记的是「去了哪几座城，每座城里做了什么」，扁平事件列表表达不了
 * 「这些活动属于哪座城市」；而城市本身不必单独记时间 —— 一个城市段的时间范围就取它第一条
 * 与最后一条事件的时刻，离开时间由下一座城的第一条事件决定。
 *
 * 纯 Kotlin、不碰 android.* 与数据库，校验与派生都能在 JVM 单测里跑。
 * 落库时整段序列化成 JSON 存进 `trips.timeline`，起止时间与总花销另存冗余列
 * （列表排序不解析 JSON，见 data/db/Entities.kt 的 TripEntity）。
 */
@Serializable
data class Timeline(
    val segments: List<Segment> = emptyList(),
) {

    /**
     * 一座到访的城市，以及在这座城市里发生的事件。
     *
     * **城市不存时间**：到达时间取 [events] 第一条，离开时间取最后一条；下一座城的第一条事件
     * 就是转场时刻。这样用户只需要在事件上选时间，不用再为城市单独挑一次。
     */
    @Serializable
    data class Segment(
        val city: String = "",
        val events: List<Event> = emptyList(),
        /** 同行人：自由文本（预制「独自 / 朋友 / 情侣 / 家人」只作输入提示）；null = 没填。 */
        val companions: String? = null,
        /** 城市评分 1..5；null = 没评。 */
        val rating: Int? = null,
        /** 本地照片路径（应用私有目录）；null = 没配图。 */
        val photoPath: String? = null,
        /** 到达这座城市的方式（城市之间的交通，如「高铁」「自驾」）；null = 没填。 */
        val transport: String? = null,
        /** 这座城市段的备注（≤ [Limits.NOTE]）；派生打卡会直接用它当备注。 */
        val note: String? = null,
        /** 城市 adcode（来自城市表）；null = 老数据/未选城，派生时按名字回查。 */
        val cityCode: Int? = null,
        /** 省份（来自城市表）；null = 老数据，派生时按名字回查。 */
        val province: String? = null,
    )

    /**
     * 城市内的一条事件。时间存 **UTC 毫秒**，另存记录时的时区偏移 [tz]（分钟）：
     * 只存 UTC 的话，跨时区旅行后「当时几点」还原不出来；只存本地时间又会让排序错。
     *
     * [id] 是客户端生成的稳定标识：编辑页重排/删除后下标会变，靠它定位「改的是哪一条」。
     * [type] 只有活动与交通两种 —— 「到访」升成了外层城市，「前往」取消（下一座城市就是转场）。
     * [costCents] 用**分**：`null` = 没填，`0` = 合法的「花了 0 元」。
     */
    @Serializable
    data class Event(
        val id: String = "",
        val time: Long = 0L,
        val type: Type = Type.ACTIVITY,
        val tz: Int? = null,
        val text: String = "",
        val costCents: Int? = null,
        /** 这条事件自己的备注（≤ [Limits.NOTE]）；null = 没写。 */
        val note: String? = null,
    )

    /** 事件类型。`@SerialName` 是落库 JSON 的契约，改名等于改数据格式。 */
    @Serializable
    enum class Type {
        /** 活动：做了什么（≤ [Limits.SHORT]）。 */
        @SerialName("activity")
        ACTIVITY,

        /** 交通：怎么到 / 怎么走（自由文本，≤ [Limits.SHORT]）。 */
        @SerialName("ride")
        RIDE,

        /** 到访：去了哪里（自由文本，≤ [Limits.SHORT]）。 */
        @SerialName("visit")
        VISIT,
    }

    /** 按城市段顺序摊平的所有事件（派生与展示用）。 */
    val events: List<Event> get() = segments.flatMap { it.events }

    val cityCount: Int get() = segments.size

    val eventCount: Int get() = segments.sumOf { it.events.size }

    /** 首个事件的时刻（行程起点）。调用方保证时间线已通过校验或至少非空。 */
    val startTime: Long get() = events.first().time

    /** 末个事件的时刻（行程终点）。 */
    val endTime: Long get() = events.last().time

    /** 所有事件花销之和（没填的算 0）。 */
    val totalCostCents: Int get() = events.sumOf { it.costCents ?: 0 }

    /**
     * 校验，返回给人看的第一条错误文案；`null` 表示通过。
     *
     * [requireSegments]：已保存的行程至少要有一座城市（否则地图上没有任何点）；
     * 草稿/编辑中允许暂时为空。
     *
     * 规则：
     *  - 城市名不能空、≤ [Limits.SHORT]、**同一座城市只能出现一段**（编辑器按城分节，重复会分不清）；
     *  - 每座城市至少一条事件（否则这座城市没有时间，派生不出一条打卡）；
     *  - 事件内容不能空、≤ [Limits.SHORT]；
     *  - 时间**允许相等**（同一分钟的两件事很常见），但不允许逆序 —— 跨城市也一样。
     */
    fun validate(requireSegments: Boolean = true): String? {
        if (segments.isEmpty()) return if (requireSegments) "至少添加一座到访城市" else null
        segments.forEachIndexed { si, segment ->
            val no = si + 1
            if (segment.city.isBlank()) return "第 $no 段：请选择到访城市"
            if (segment.city.length > Limits.SHORT) return "第 $no 段：城市名不能超过 ${Limits.SHORT} 字"
            if (segment.rating != null && segment.rating !in 1..10) return "「${segment.city}」评分需在 1–10 之间"
            if (segment.companions.orEmpty().length > Limits.SHORT) {
                return "「${segment.city}」同行人不能超过 ${Limits.SHORT} 字"
            }
            if (segment.transport.orEmpty().length > Limits.SHORT) {
                return "「${segment.city}」到达方式不能超过 ${Limits.SHORT} 字"
            }
            if (segment.note.orEmpty().length > Limits.NOTE) {
                return "「${segment.city}」备注不能超过 ${Limits.NOTE} 字"
            }
            if (segment.events.isEmpty()) return "「${segment.city}」请至少添加一条活动或交通"
            segment.events.forEachIndexed { ei, event ->
                val eventNo = ei + 1
                if (event.text.isBlank()) {
                    return "「${segment.city}」第 $eventNo 条：请填写${when (event.type) { Type.RIDE -> "交通方式"; Type.VISIT -> "地点"; else -> "活动内容" }}"
                }
                if (event.text.length > Limits.SHORT) {
                    return "「${segment.city}」第 $eventNo 条：内容不能超过 ${Limits.SHORT} 字"
                }
                if (event.note.orEmpty().length > Limits.NOTE) {
                    return "「${segment.city}」第 $eventNo 条：备注不能超过 ${Limits.NOTE} 字"
                }
            }
        }

        // 时间顺序：城市内相邻事件不许逆序；跨城市也不许（下一城的第一条不能早于上一城最后一条）
        var previousAcrossCities = Long.MIN_VALUE
        segments.forEach { segment ->
            var previousInCity = Long.MIN_VALUE
            segment.events.forEachIndexed { ei, event ->
                if (previousInCity != Long.MIN_VALUE && event.time < previousInCity) {
                    return "「${segment.city}」第 ${ei + 1} 条：时间不能早于上一条"
                }
                if (event.time < previousAcrossCities) {
                    return "「${segment.city}」第 ${ei + 1} 条：时间不能早于上一座城市的事件"
                }
                previousInCity = event.time
                previousAcrossCities = event.time
            }
        }
        return null
    }

    /**
     * 派生打卡用：城市 → 该城市**第一条**事件。
     *
     * 按城市段顺序返回 LinkedHashMap；同一城市重复出现时取最先的一段（校验本来就不允许重复，
     * 这里只是防御）。「同一座城市去了两次只记一条」与 1.x 的口径一致。
     */
    fun firstEventByCity(): Map<String, Event> {
        val out = LinkedHashMap<String, Event>()
        for (segment in segments) {
            val name = segment.city.trim()
            if (name.isEmpty()) continue
            val first = segment.events.minByOrNull { it.time } ?: continue
            out.putIfAbsent(name, first)
        }
        return out
    }

    /** 落库用：整段序列化成 JSON 文本（存进 `trips.timeline`）。 */
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        /**
         * 反序列化。**解析失败返回 null，不抛异常**：库里可能存着旧格式或损坏的 JSON，
         * 读坏了按「这条行程的时间线不可用」处理，不能让一次列表刷新整体崩掉。
         */
        fun decode(raw: String): Timeline? = runCatching {
            json.decodeFromString(serializer(), raw)
        }.getOrNull()

        /**
         * 与 `data/Json.kt` 同口径的两个开关：未知键忽略、缺省值不写回。
         * 这里自带一份而不是复用 data 包：logic 不反向依赖 data（那条边界见 Models.kt 的说明）。
         */
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}
