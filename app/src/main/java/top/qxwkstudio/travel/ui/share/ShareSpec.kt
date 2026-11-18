package top.qxwkstudio.travel.ui.share

import android.content.Context
import android.graphics.Typeface
import top.qxwkstudio.travel.ui.common.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 分享图的固定设计口径：墨色档位、字体、排列、日期格式、深链。
 *
 * 所有尺寸都在「设计坐标」里写（票根 1600×900、海报 1080×1440），
 * 预览按屏幕宽度整体缩放，导出按原尺寸出图 —— 两边画的是同一段代码，所见即所得。
 */

/** 分享图上可切换的墨色，四档全站统一，避免每个卡片各配一套色板。 */
enum class InkColor {
    BLACK, ACCENT, GOLD, GRAY;

    fun resolve(context: Context): Int = when (this) {
        BLACK -> 0xFF1F1B16.toInt()
        ACCENT -> Palette.accent(context)
        GOLD -> 0xFFB08A3E.toInt()
        GRAY -> 0xFF8C8578.toInt()
    }

    companion object {
        val ALL = listOf(BLACK, ACCENT, GOLD, GRAY)
    }
}

/** 邮戳样式。 */
enum class StampStyle { ROUND, DOUBLE, SQUARE, OVAL, PERFORATED }

/** 标题字体：全部用系统字体族，不打包字体文件。 */
enum class CardFont {
    DEFAULT, SERIF, MONO, LIGHT, CONDENSED, CURSIVE;

    fun typeface(): Typeface = when (this) {
        DEFAULT -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        SERIF -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        MONO -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        LIGHT -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
        CURSIVE -> Typeface.create("cursive", Typeface.NORMAL)
    }

    companion object {
        val ALL = listOf(DEFAULT, SERIF, MONO, LIGHT, CONDENSED, CURSIVE)
    }
}

/** 标题排列方向：竖排按字逐行落。 */
enum class TextLayout { HORIZONTAL, VERTICAL }

/** 单条日期格式。 */
enum class DateStyle { DOT, CN, EN, SLASH, ISO, SPACED }

/** 时间跨度的精度。 */
enum class RangeUnit { DAY, MONTH, YEAR }

/**
 * 分享深链。
 *
 * **网页端的落地页还没做**（需求确认时明确「二维码先用矩形占位」），所以 [BASE] 先留空：
 * 生成出来的内容是空串，票根/海报在二维码位置画一个虚线方块占位；
 * 网页路由确定后只改这一处常量，两个分享页与导出图会一起生效。
 */
object ShareLink {
    private const val BASE = ""

    fun checkin(uuid: String): String = if (BASE.isBlank()) "" else "${BASE}checkin/$uuid"

    fun trip(uuid: String): String = if (BASE.isBlank()) "" else "${BASE}trip/$uuid"
}

/** 日期文案：单条按三档格式，跨度按三档精度。 */
object ShareDates {

    fun single(millis: Long, style: DateStyle): String {
        val pattern = when (style) {
            DateStyle.DOT -> "yyyy.MM.dd"
            DateStyle.CN -> "yyyy年M月d日"
            DateStyle.EN -> "d MMM yyyy"
            DateStyle.SLASH -> "yyyy/MM/dd"
            DateStyle.ISO -> "yyyy-MM-dd"
            DateStyle.SPACED -> "yyyy  MM  dd"
        }
        return SimpleDateFormat(pattern, if (style == DateStyle.CN) Locale.CHINA else Locale.US)
            .format(Date(millis))
    }

    fun range(start: Long, end: Long, unit: RangeUnit): String {
        val pattern = when (unit) {
            RangeUnit.DAY -> "yyyy.MM.dd"
            RangeUnit.MONTH -> "yyyy.MM"
            RangeUnit.YEAR -> "yyyy"
        }
        val format = SimpleDateFormat(pattern, Locale.US)
        val from = format.format(Date(start))
        val to = format.format(Date(end))
        return if (from == to) from else "$from — $to"
    }
}
