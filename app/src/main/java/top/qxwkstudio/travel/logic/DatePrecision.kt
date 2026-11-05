package top.qxwkstudio.travel.logic

/**
 * 打卡日期的精度。新数据三档，对应「下拉里选到哪一级」：
 *  - [YEAR]：只记得年份
 *  - [MONTH]：只记得年 + 月
 *  - [DAY]：完整日期
 *  - [UNKNOWN]：旧版「记不清了」，仅兼容历史数据，界面不再产生
 *
 * 放在 logic 而不是 data/db：统计（时间跨度只要完整日期）、表单（怎么补占位日期）与数据库列
 * 三处都要用同一组值，而 data 依赖 logic、logic 不依赖 data —— 放这里两边都能引，不用重复定义。
 *
 * 无论哪一档，`checkin_at` 都要存一个具体值用于排序：
 * [YEAR] → 当年 1 月 1 日，[MONTH] → 当月 1 日，[UNKNOWN] → 当年 1 月 1 日（占位，仅用于排序）。
 */
object DatePrecision {
    const val YEAR = 0
    const val MONTH = 1
    const val DAY = 2
    const val UNKNOWN = 3

    /** 参与「时间跨度」的只有完整日期：其余三档的日期是补出来的占位值。 */
    fun countsForRange(precision: Int): Boolean = precision == DAY
}
