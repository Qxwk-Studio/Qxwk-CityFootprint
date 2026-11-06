package top.qxwkstudio.travel.logic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一条行程的时间线：**扁平列表**，到访城市与活动/交通平级。
 *
 * ```
 * items = [
 *   Item(到访 北京),
 *   Item(活动 故宫),
 *   Item(交通 高铁),
 *   Item(到访 上海),
 *   Item(活动 外滩),
 * ]
 * ```
 *
 * 派生打卡时按「到访城市」切段：两个城市之间的活动算前一个城市；
 * 第一个城市之前、最后一个城市之后的交通/活动照常保存，但不参与派生。
 *
 * 纯 Kotlin、不碰 android.* 与数据库，校验与派生都能在 JVM 单测里跑。
 */
@Serializable
data class Timeline(
    val items: List<Item> = emptyList(),
) {

    /**
     * 时间线上的一项。城市（VISIT）与活动/交通共用一张结构：
     * 城市只使用 [text]（城市名）、[cityCode]、[province] 与城市级可选项，[time] 不参与展示。
     */
    @Serializable
    data class Item(
        val id: String = "",
        val type: Type = Type.ACTIVITY,
        /** 活动/交通的时间（UTC 毫秒）；城市项不使用。 */
        val time: Long = 0L,
        val tz: Int? = null,
        /** 活动内容 / 交通方式 / 到访城市名。 */
        val text: String = "",
        val costCents: Int? = null,
        val note: String? = null,
        /** 地点纬度（WGS-84）；与 [lng] 成对出现。 */
        val lat: Double? = null,
        val lng: Double? = null,
        /** 活动照片（最多 3 张，逗号分隔）或城市照片，按 [type] 区分。 */
        val photoPath: String? = null,
        // ── 仅城市项使用 ──
        val cityCode: Int? = null,
        val province: String? = null,
        val companions: String? = null,
        val rating: Int? = null,
    )

    /** 只读的城市分段视图：给派生打卡、详情、统计用；不在 JSON 里。 */
    @Serializable
    data class Segment(
        val city: String = "",
        val events: List<Event> = emptyList(),
        val companions: String? = null,
        val rating: Int? = null,
        val photoPath: String? = null,
        /** 已废弃：到达方式并入时间线里的交通项。 */
        val transport: String? = null,
        val note: String? = null,
        val cityCode: Int? = null,
        val province: String? = null,
    )

    /** 旧的事件模型：保留给派生/详情代码与单测用，不再直接落库。 */
    @Serializable
    data class Event(
        val id: String = "",
        val time: Long = 0L,
        val type: Type = Type.ACTIVITY,
        val tz: Int? = null,
        val text: String = "",
        val costCents: Int? = null,
        val note: String? = null,
        val lat: Double? = null,
        val lng: Double? = null,
        val photoPath: String? = null,
    )

    /** 事件类型。`@SerialName` 是落库 JSON 的契约，改名等于改数据格式。 */
    @Serializable
    enum class Type {
        @SerialName("activity")
        ACTIVITY,

        @SerialName("ride")
        RIDE,

        /** 到访城市（扁平时间线里的城市项）。 */
        @SerialName("visit")
        VISIT,
    }

    /** 按到访城市切出来的城市段；第一个城市之前的事件不属于任何段。 */
    val segments: List<Segment>
        get() {
            val out = mutableListOf<Segment>()
            var city: Item? = null
            var buffer = mutableListOf<Event>()

            fun flush() {
                val current = city ?: return
                out += Segment(
                    city = current.text,
                    events = buffer.toList(),
                    companions = current.companions,
                    rating = current.rating,
                    photoPath = current.photoPath,
                    note = current.note,
                    cityCode = current.cityCode,
                    province = current.province,
                )
            }

            items.forEach { item ->
                if (item.type == Type.VISIT) {
                    flush()
                    city = item
                    buffer = mutableListOf()
                } else if (city != null) {
                    buffer += item.toEvent()
                }
            }
            flush()
            return out
        }

    /** 所有非城市项（含第一个城市之前的）。 */
    val events: List<Event> get() = items.filter { it.type != Type.VISIT }.map { it.toEvent() }

    val cityCount: Int get() = items.count { it.type == Type.VISIT }

    val eventCount: Int get() = items.count { it.type != Type.VISIT }

    val startTime: Long get() = events.minOfOrNull { it.time } ?: 0L

    val endTime: Long get() = events.maxOfOrNull { it.time } ?: 0L

    val totalCostCents: Int get() = events.sumOf { it.costCents ?: 0 }

    private fun Item.toEvent() = Event(
        id = id,
        time = time,
        type = type,
        tz = tz,
        text = text,
        costCents = costCents,
        note = note,
        lat = lat,
        lng = lng,
        photoPath = photoPath,
    )

    /**
     * 校验，返回第一条错误文案；`null` 表示通过。
     *
     * 规则：
     *  - 至少一座到访城市、至少一条活动/交通；
     *  - 城市名不能空、≤ [Limits.SHORT]；城市级同行人/备注/评分范围；
     *  - 活动内容不能空、≤ [Limits.SHORT]；备注 ≤ [Limits.NOTE]；
     *  - 活动时间按列表顺序**允许相等、不允许逆序**（城市项没有时间，不参与比较）；
     *  - 坐标成对出现且在合法范围内。
     */
    fun validate(requireSegments: Boolean = true): String? {
        if (cityCount == 0) return if (requireSegments) "至少添加一座到访城市" else null
        if (events.isEmpty()) return "请至少添加一条活动或交通"

        var cityNo = 0
        var eventNo = 0
        var previousTime = Long.MIN_VALUE
        items.forEach { item ->
            if (item.type == Type.VISIT) {
                cityNo++
                if (item.text.isBlank()) return "第 $cityNo 座城市：请选择到访城市"
                if (item.text.length > Limits.SHORT) {
                    return "「${item.text}」城市名不能超过 ${Limits.SHORT} 字"
                }
                if (item.rating != null && item.rating !in 1..10) {
                    return "「${item.text}」评分需在 1–10 之间"
                }
                if (item.companions.orEmpty().length > Limits.SHORT) {
                    return "「${item.text}」同行人不能超过 ${Limits.SHORT} 字"
                }
                if (item.note.orEmpty().length > Limits.NOTE) {
                    return "「${item.text}」备注不能超过 ${Limits.NOTE} 字"
                }
                return@forEach
            }

            eventNo++
            if (item.text.isBlank()) {
                return "第 $eventNo 条：请填写${when (item.type) { Type.RIDE -> "交通方式"; else -> "活动内容" }}"
            }
            if (item.text.length > Limits.SHORT) {
                return "第 $eventNo 条：内容不能超过 ${Limits.SHORT} 字"
            }
            if (item.note.orEmpty().length > Limits.NOTE) {
                return "第 $eventNo 条：备注不能超过 ${Limits.NOTE} 字"
            }
            if ((item.lat == null) != (item.lng == null)) {
                return "第 $eventNo 条：位置坐标不完整"
            }
            if (item.lat != null && item.lat !in -90.0..90.0) {
                return "第 $eventNo 条：纬度不合法"
            }
            if (item.lng != null && item.lng !in -180.0..180.0) {
                return "第 $eventNo 条：经度不合法"
            }
            if (previousTime != Long.MIN_VALUE && item.time < previousTime) {
                return "第 $eventNo 条：时间不能早于上一条"
            }
            previousTime = item.time
        }
        return null
    }

    /** 派生打卡用：城市 → 该城市下第一条事件（城市重复出现时取第一次）。 */
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
        fun decode(raw: String): Timeline? = runCatching {
            json.decodeFromString(serializer(), raw)
        }.getOrNull()

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}
