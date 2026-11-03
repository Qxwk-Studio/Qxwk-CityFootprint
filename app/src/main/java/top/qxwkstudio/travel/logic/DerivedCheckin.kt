package top.qxwkstudio.travel.logic

/**
 * 从一条行程的时间线派生出来的打卡（还没落库的形态）。
 *
 * 「足迹自动派生关联打卡」是 2.0 的核心口径：一条行程里的每次**首次到访**都会生成一条打卡，
 * 打卡表再统一作为地图点亮、统计与成就的数据源 —— 所以打卡只有一份，不按来源各算一套。
 */
data class DerivedCheckin(
    val cityName: String,
    val cityCode: Int?,
    val province: String,
    /** 该城市的首次到访时间（UTC 毫秒），直接取自时间线节点的 `time`。 */
    val checkinAt: Long,
)

/**
 * 时间线 → 派生打卡。
 *
 * 三条规则：
 *  1. 只看 [TimelineType.VISIT] 节点，同一座城市**只取首次到访**（[Timeline.firstVisitByCity]）；
 *  2. 城市表里查不到的名字**直接跳过** —— 与 1.x「省份查不到就不计入」同一口径：
 *     宁可少一条派生打卡，也不要造一条省份空着、地图点不亮的记录；
 *  3. 精度一律是「完整日期」：时间线节点本来就带具体时间，不存在只记得年月的派生打卡。
 *
 * 纯函数（城市数据由调用方以 [lookup] 传入），所以能在 JVM 单测里直接跑，不需要设备与数据库。
 */
fun deriveCheckins(timeline: Timeline, lookup: (String) -> City?): List<DerivedCheckin> =
    timeline.firstVisitByCity().mapNotNull { (name, event) ->
        val city = lookup(name) ?: return@mapNotNull null
        DerivedCheckin(
            cityName = city.name,
            cityCode = city.adcode,
            province = city.province,
            checkinAt = event.time,
        )
    }
