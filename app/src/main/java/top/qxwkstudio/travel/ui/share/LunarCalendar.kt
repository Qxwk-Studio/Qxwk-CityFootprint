package top.qxwkstudio.travel.ui.share

import android.icu.util.ChineseCalendar
import java.util.Calendar
import java.util.Date

/**
 * 农历换算：用系统自带的 `android.icu.util.ChineseCalendar`（API 24 起就有），
 * 不自己维护 1900–2100 的月表 —— 闰月规则手写一次就是一串只在个别年份出错的坑。
 *
 * 输出是文案，不是数值：票根上直接画「丙午年十月十八」这类字符串。
 */
object LunarCalendar {

    data class Value(
        val relatedYear: Int,
        val month: Int,
        val day: Int,
        val leap: Boolean,
    ) {
        /** 干支纪年，如「丙午」。 */
        val cycleYear: String
            get() {
                // 1984 是甲子年，(year - 1984) mod 60 即该年的干支序号
                val index = Math.floorMod(relatedYear - 1984, 60)
                val stem = "甲乙丙丁戊己庚辛壬癸"[index % 10]
                val branch = "子丑寅卯辰巳午未申酉戌亥"[index % 12]
                return "$stem$branch"
            }

        /** 正月 / 二月 … 冬月 / 腊月（闰月前面加「闰」）。 */
        val monthName: String
            get() {
                val names = arrayOf("正月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "冬月", "腊月")
                val base = names[(month - 1).coerceIn(0, 11)]
                return if (leap) "闰$base" else base
            }

        val dayName: String get() = dayName(day)
    }

    /** 取某个时刻对应的农历；解析失败返回 null（调用方回退公历）。 */
    fun of(millis: Long): Value? = runCatching {
        val calendar = ChineseCalendar()
        calendar.timeInMillis = millis
        val month = calendar.get(ChineseCalendar.MONTH) + 1
        val day = calendar.get(ChineseCalendar.DATE)
        val leap = calendar.get(ChineseCalendar.IS_LEAP_MONTH) == 1
        Value(relatedYear = relatedYear(millis, calendar), month = month, day = day, leap = leap)
    }.getOrNull()

    /**
     * 农历年对应的公历「相关年」。
     *
     * ICU 的 EXTENDED_YEAR 在不同版本里含义不完全一致，所以先看它像不像公历年（1900–2100），
     * 不像就用「公历 1、2 月还处在冬月/腊月 → 农历年减一」这条经验规则兜底。
     */
    private fun relatedYear(millis: Long, calendar: ChineseCalendar): Int {
        val extended = runCatching { calendar.get(ChineseCalendar.EXTENDED_YEAR) }.getOrNull()
        if (extended != null && extended in 1900..2100) return extended
        val solar = Calendar.getInstance().apply { time = Date(millis) }
        val solarYear = solar.get(Calendar.YEAR)
        val solarMonth = solar.get(Calendar.MONTH) + 1
        val lunarMonth = calendar.get(ChineseCalendar.MONTH) + 1
        return if (solarMonth <= 2 && lunarMonth >= 11) solarYear - 1 else solarYear
    }

    private fun dayName(day: Int): String {
        val digits = arrayOf("十", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        return when {
            day == 10 -> "初十"
            day == 20 -> "二十"
            day == 30 -> "三十"
            day < 10 -> "初${digits[day]}"
            day < 20 -> "十${digits[day - 10]}"
            day < 30 -> "廿${digits[day - 20]}"
            else -> "三十"
        }
    }

    /** 汉字数字年：二零二六（逐位读，0 用「零」）。 */
    private fun yearDigits(year: Int): String =
        year.toString().map { "零一二三四五六七八九"[it - '0'] }.joinToString("")

    /** 大写数字写法：二零二六年五月廿三（月/日沿用农历的「正月、廿三」叫法）。 */
    fun formatUppercase(value: Value): String =
        "${yearDigits(value.relatedYear)}年${value.monthName}${value.dayName}"

    /** 票根上的五种农历写法：全称 / 农历前缀 / 只到月日 / 只到年 / 大写数字。 */
    fun format(value: Value, style: Int): String = when (style) {
        0 -> "${value.cycleYear}年${value.monthName}${value.dayName}"
        1 -> "农历${value.monthName}${value.dayName}"
        2 -> "${value.monthName}${value.dayName}"
        3 -> "${value.cycleYear}年"
        else -> formatUppercase(value)
    }

    /** 只记得年月 / 只记得年时（记录精度不足），降到「干支年 + 月」或「干支年」。 */
    fun formatCoarse(value: Value, level: Int): String = when (level) {
        1 -> "${value.cycleYear}年${value.monthName}"
        else -> "${value.cycleYear}年"
    }
}
