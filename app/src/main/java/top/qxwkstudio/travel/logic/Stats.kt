package top.qxwkstudio.travel.logic

/**
 * 主页统计与城市排名。纯函数，输入是「打卡 + 行程」两份快照，输出是可直接渲染的数字与行 ——
 * 不碰数据库、不碰 Context，所以能整段在 JVM 单测里跑。
 *
 * 口径（2.0-development.md §6.4，改之前先确认是产品口径变了）：
 *  1. 打卡城市 = 去重后的城市数；打卡省份 = 去重后的省份数，**省份为空的不计入**；
 *  2. 「时间跨度」只统计**完整日期**的打卡（[DatePrecision.DAY]）—— 只记得年/年月/记不清的
 *     记录都带占位日期，混进去会把跨度起点拉到一个其实没人去过的日子；
 *  3. 「次数最多」按**城市**计次（不是省份），并列时取名字字典序靠前的那个（结果稳定、可测）；
 *  4. 总花销 = 各行程花销之和，只出现在主页那张卡上；
 *  5. 城市排名**只有次数与时间两个维度**。花费不参与排名：一笔行程可能去好几座城市，
 *     把它的花销拆到城市要么重复计入、要么得按停留/节点金额分摊，而我们多数行程没填节点金额。
 *     行程卡片自己显示总额，主页那张卡显示全站总额 —— 两处都够用，不必为排名造一个不可解释的数。
 */
object Stats {

    /**
     * 一条打卡的统计输入。只带统计要用的字段，不直接吃数据库实体 ——
     * 这样这个文件不必依赖 `data/`（logic 包的独立性见 Models.kt 的说明）。
     */
    data class Entry(
        val cityName: String,
        val province: String,
        val checkinAt: Long,
        val datePrecision: Int,
    )

    /** 一条行程的统计输入。目前只用到花销。 */
    data class Trip(val totalCostCents: Int)

    /** 主页那几张卡要的数字。[rangeStart] / [rangeEnd] 为 null 表示一条完整日期的打卡都没有。 */
    data class Overview(
        val cities: Int,
        val provinces: Int,
        val visits: Int,
        val rangeStart: Long?,
        val rangeEnd: Long?,
        val mostVisitedCity: String?,
        val totalCostCents: Int,
    )

    /** 城市排名的一行。两个排序维度共用同一行结构。 */
    data class CityRow(
        val city: String,
        val province: String,
        val count: Int,
        val lastVisitAt: Long,
    )

    /** 排名维度。界面上的下拉框就这两个选项。 */
    enum class RankBy { COUNT, TIME }

    fun overview(entries: List<Entry>, trips: List<Trip>): Overview {
        val exact = entries.filter { DatePrecision.countsForRange(it.datePrecision) }
        val provinces = entries.mapNotNull { it.province.takeIf { p -> p.isNotBlank() } }.toSet()

        val counts = entries.groupingBy { it.cityName }.eachCount()
        // 并列时取名字字典序靠前的：map 的遍历顺序不可依赖，这里必须显式定序
        val mostVisited = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key

        return Overview(
            cities = counts.size,
            provinces = provinces.size,
            visits = entries.size,
            rangeStart = exact.minOfOrNull { it.checkinAt },
            rangeEnd = exact.maxOfOrNull { it.checkinAt },
            mostVisitedCity = mostVisited,
            totalCostCents = trips.sumOf { it.totalCostCents },
        )
    }

    /** 城市排名。同一座城市按打卡条数计次、按最近一条打卡取「最近到访」。 */
    fun rank(entries: List<Entry>, by: RankBy): List<CityRow> {
        if (entries.isEmpty()) return emptyList()

        val rows = entries
            .groupBy { it.cityName }
            .map { (city, list) ->
                CityRow(
                    city = city,
                    // 同一座城的省份应当一致（城市表是唯一来源）；取第一条，空串原样留着
                    province = list.first().province,
                    count = list.size,
                    lastVisitAt = list.maxOf { it.checkinAt },
                )
            }

        // 两个维度都用同一套 tie-break（城市名字典序），保证同一份数据每次渲染顺序一致
        return when (by) {
            RankBy.COUNT -> rows.sortedWith(compareByDescending<CityRow> { it.count }.thenBy { it.city })
            RankBy.TIME -> rows.sortedWith(compareByDescending<CityRow> { it.lastVisitAt }.thenBy { it.city })
        }
    }
}
