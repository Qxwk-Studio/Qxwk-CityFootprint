package top.qxwkstudio.travel.logic

import java.util.Calendar

/**
 * 日期展示（输入一律是 UTC 毫秒）。
 *
 * 为什么不用 `java.time`：minSdk 是 24，`java.time` 要开 core library desugaring，
 * 为三个格式化函数引一整套脱糖不值得；`Calendar` 够用且零依赖。
 *
 * 两种形态刻意不同：
 *  - [ymd] 给列表行用（`2024-08-01`，定长、对齐好看）；
 *  - [range] 给主页「时间跨度」用（`2015.9.3`，与 2.0 设计文档 的示例一致、不补零）。
 *
 * 时区：用设备当前时区。记录时另存了时区偏移（timeline 的 `tz`），但统计与列表展示
 * 统一按本地时间看 —— 跨时区旅行的「当时几点」只在行程详情里有意义。
 */
object Dates {

    fun ymd(millis: Long): String {
        val c = calendar(millis)
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** 主页那张卡：`2015.9.3 → 2026.10.7`。任一端为 null 时由调用方显示占位符。 */
    fun range(start: Long?, end: Long?): String? {
        if (start == null || end == null) return null
        return "${dot(start)} → ${dot(end)}"
    }

    /** 不补零的 `年.月.日`（与 2.0 设计文档 的示例同形态）。 */
    fun dot(millis: Long): String {
        val c = calendar(millis)
        return "${c.get(Calendar.YEAR)}.${c.get(Calendar.MONTH) + 1}.${c.get(Calendar.DAY_OF_MONTH)}"
    }

    /** 只到年月：`2024.8`（打卡只记得年月时展示用）。 */
    fun dotMonth(millis: Long): String {
        val c = calendar(millis)
        return "${c.get(Calendar.YEAR)}.${c.get(Calendar.MONTH) + 1}"
    }

    /** 只到年：`2024`。 */
    fun year(millis: Long): String = calendar(millis).get(Calendar.YEAR).toString()

    private fun calendar(millis: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = millis }
}
