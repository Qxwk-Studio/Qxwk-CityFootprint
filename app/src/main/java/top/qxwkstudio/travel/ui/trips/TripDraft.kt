package top.qxwkstudio.travel.ui.trips

import top.qxwkstudio.travel.logic.Timeline

/**
 * 编辑期的一项：城市（VISIT）与活动/交通共用，字段和 [Timeline.Item] 一一对应。
 * 时间线是扁平列表，顺序就是用户看到、保存的顺序。
 */
internal data class DraftItem(
    var id: String = "",
    var type: Timeline.Type = Timeline.Type.ACTIVITY,
    var time: Long = 0L,
    var tz: Int? = null,
    var text: String = "",
    var costCents: Int? = null,
    var note: String = "",
    var lat: Double? = null,
    var lng: Double? = null,
    var photoPath: String? = null,
    var cityCode: Int? = null,
    var province: String? = null,
    var companions: String? = null,
    var rating: Int? = null,
) {
    val isCity: Boolean get() = type == Timeline.Type.VISIT

    fun toItem(): Timeline.Item = Timeline.Item(
        id = id,
        type = type,
        time = time,
        tz = tz,
        text = text,
        costCents = costCents,
        note = note.ifBlank { null },
        lat = lat,
        lng = lng,
        photoPath = photoPath,
        cityCode = cityCode,
        province = province,
        companions = companions,
        rating = rating,
    )

    companion object {
        fun from(item: Timeline.Item) = DraftItem(
            id = item.id,
            type = item.type,
            time = item.time,
            tz = item.tz,
            text = item.text,
            costCents = item.costCents,
            note = item.note.orEmpty(),
            lat = item.lat,
            lng = item.lng,
            photoPath = item.photoPath,
            cityCode = item.cityCode,
            province = item.province,
            companions = item.companions,
            rating = item.rating,
        )
    }
}

/** 最后一条活动/交通的时间：新增活动默认接在它后面；没有就用当前时间。 */
internal fun List<DraftItem>.lastActivityTime(): Long =
    filter { !it.isCity }.maxOfOrNull { it.time } ?: System.currentTimeMillis()

/** 本段天数：最早与最晚活动按本地日期差 +1。 */
internal fun segmentDays(events: List<Timeline.Event>): Int {
    if (events.isEmpty()) return 0
    val first = events.minOf { it.time }
    val last = events.maxOf { it.time }
    val c1 = java.util.Calendar.getInstance().apply { timeInMillis = first }
    val c2 = java.util.Calendar.getInstance().apply { timeInMillis = last }
    val d1 = c1.get(java.util.Calendar.YEAR) * 366 + c1.get(java.util.Calendar.DAY_OF_YEAR)
    val d2 = c2.get(java.util.Calendar.YEAR) * 366 + c2.get(java.util.Calendar.DAY_OF_YEAR)
    return (d2 - d1) + 1
}
