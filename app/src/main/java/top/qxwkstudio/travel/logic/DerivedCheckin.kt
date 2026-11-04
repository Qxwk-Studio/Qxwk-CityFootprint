package top.qxwkstudio.travel.logic

/**
 * 从一条行程的时间线派生出来的打卡（还没落库的形态）。
 *
 * 「足迹自动派生关联打卡」是 2.0 的核心口径：一条行程里到的每一座城市都会生成一条打卡，
 * 打卡表再统一作为地图点亮、统计与成就的数据源 —— 打卡只有一份，不按来源各算一套。
 */
data class DerivedCheckin(
    val cityName: String,
    val cityCode: Int?,
    val province: String,
    /** 该城市第一条事件的时刻（UTC 毫秒）：城市不单独记到达时间，就用它当打卡时间。 */
    val checkinAt: Long,
    /** 同行人 / 评分 / 照片：从足迹的城市段复制过来，派生打卡与手动打卡看到的是同一套信息。 */
    val companions: String? = null,
    val rating: Int? = null,
    val photoPath: String? = null,
)

/**
 * 时间线 → 派生打卡。
 *
 * 规则：
 *  1. 每个城市段取**第一条事件**的时刻当打卡时间（[Timeline.firstEventByCity]）；
 *  2. 城市表里查不到的名字**直接跳过**：宁可少一条派生打卡，也不要造一条省份空着、
 *     地图点不亮的记录；
 *  3. 精度一律是「完整日期」：城市段的时间来自具体事件，不存在只记得年月的派生打卡。
 *
 * 纯函数（城市数据由调用方以 [lookup] 传入），能在 JVM 单测里直接跑。
 */
fun deriveCheckins(timeline: Timeline, lookup: (String) -> City?): List<DerivedCheckin> =
    timeline.firstEventByCity().mapNotNull { (name, event) ->
        val city = lookup(name) ?: return@mapNotNull null
        DerivedCheckin(
            cityName = city.name,
            cityCode = city.adcode,
            province = city.province,
            checkinAt = event.time,
            companions = timeline.segments.firstOrNull { it.city == name }?.companions,
            rating = timeline.segments.firstOrNull { it.city == name }?.rating,
            photoPath = timeline.segments.firstOrNull { it.city == name }?.photoPath,
        )
    }
