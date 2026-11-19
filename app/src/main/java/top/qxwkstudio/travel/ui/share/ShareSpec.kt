package top.qxwkstudio.travel.ui.share

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.ui.common.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 分享图的固定设计口径：墨色档位、字体、排列、日期格式、农历。
 *
 * 所有尺寸都在「设计坐标」里写（票根 2000×900、海报 1080×1440），
 * 预览按屏幕宽度整体缩放，导出按原尺寸出图 —— 两边画的是同一段代码，所见即所得。
 */

/** 分享图上可切换的墨色。全部落在浅色纸上，够清楚又不刺眼。 */
enum class InkColor {
    BLACK, ACCENT, GOLD, GRAY, RED, BLUE, GREEN, PLUM, TEAL, BROWN;

    fun resolve(context: Context): Int = when (this) {
        BLACK -> 0xFF1F1B16.toInt()
        ACCENT -> Palette.accent(context)
        GOLD -> 0xFFB08A3E.toInt()
        GRAY -> 0xFF8C8578.toInt()
        RED -> 0xFFB04A3A.toInt()
        BLUE -> 0xFF35597E.toInt()
        GREEN -> 0xFF3F6B4A.toInt()
        PLUM -> 0xFF7A4A62.toInt()
        TEAL -> 0xFF2F6F6A.toInt()
        BROWN -> 0xFF6B4A2F.toInt()
    }

    companion object {
        val ALL = listOf(BLACK, ACCENT, GOLD, GRAY, RED, BLUE, GREEN, PLUM, TEAL, BROWN)
    }
}

/** 邮戳样式。 */
enum class StampStyle { ROUND, DOUBLE, SQUARE, OVAL, PERFORATED }

/** 票根 / 海报的纸底色：都是浅色，保证墨字与印章始终看得清。 */
enum class PaperColor {
    CREAM, WHITE, GRAY, BLUE, GREEN, PINK, YELLOW;

    fun resolve(): Int = when (this) {
        CREAM -> 0xFFFBF6EA.toInt()
        WHITE -> 0xFFFFFFFF.toInt()
        GRAY -> 0xFFF1F0EC.toInt()
        BLUE -> 0xFFEAF1F8.toInt()
        GREEN -> 0xFFEAF3EC.toInt()
        PINK -> 0xFFF9EDEF.toInt()
        YELLOW -> 0xFFFBF3DC.toInt()
    }

    /**
     * 底板（画布最外层、卡片四周那一圈）颜色：由纸色压暗 8% 得到，
     * 换背景时底板跟着换，不会出现「浅蓝纸 + 米色底」的割裂。
     */
    fun backdrop(): Int = androidx.core.graphics.ColorUtils.blendARGB(resolve(), 0xFF2A2622.toInt(), 0.08f)

    companion object {
        val ALL = listOf(CREAM, WHITE, GRAY, BLUE, GREEN, PINK, YELLOW)
    }
}

/** 字体族：默认（系统无衬线）/ 手写（打包的毛笔楷书）/ 等宽（Noto Sans Mono 系列）。 */
enum class FontFamily {
    DEFAULT, HAND, MONO;

    fun base(context: Context): Typeface = when (this) {
        DEFAULT -> Typeface.create("sans-serif", Typeface.NORMAL)
        MONO -> Typeface.create("sans-serif-monospace", Typeface.NORMAL)
        // 打包的 OFL 毛笔楷书；万一缺字回退系统字体，宁可丑也不能是豆腐块
        HAND -> runCatching { ResourcesCompat.getFont(context, R.font.mashanzheng) }
            .getOrNull() ?: Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    /** 字距：手写拉开一点，等宽压紧。 */
    val letterSpacing: Float
        get() = when (this) {
            HAND -> 0.02f
            MONO -> -0.01f
            DEFAULT -> 0f
        }

    companion object {
        val ALL = listOf(DEFAULT, HAND, MONO)
    }
}

/** 字体样式：默认 / 粗体 / 细体 / 斜体。 */
enum class FontStyle {
    NORMAL, BOLD, LIGHT, ITALIC;

    val weight: Int
        get() = when (this) {
            BOLD -> Typeface.BOLD
            ITALIC -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }

    /** 细体只有系统 sans 有独立字族，其余场景靠字距拉开观感。 */
    val letterSpacing: Float get() = if (this == LIGHT) 0.05f else 0f

    companion object {
        val ALL = listOf(NORMAL, BOLD, LIGHT, ITALIC)
    }
}

/** 字体族的中文/英文标签。 */
val FontFamily.labelRes: Int
    get() = when (this) {
        FontFamily.DEFAULT -> R.string.share_font_default
        FontFamily.HAND -> R.string.share_font_hand
        FontFamily.MONO -> R.string.share_font_mono
    }

/** 字体样式标签。 */
val FontStyle.labelRes: Int
    get() = when (this) {
        FontStyle.NORMAL -> R.string.share_style_default
        FontStyle.BOLD -> R.string.share_style_bold
        FontStyle.LIGHT -> R.string.share_style_light
        FontStyle.ITALIC -> R.string.share_style_italic
    }

/** 标题字体 = 字体族 + 样式。画布只认这一个组合。 */
data class CardFont(
    val family: FontFamily = FontFamily.DEFAULT,
    val style: FontStyle = FontStyle.NORMAL,
) {
    fun typeface(context: Context): Typeface {
        if (style == FontStyle.LIGHT && family == FontFamily.DEFAULT) {
            return Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        return Typeface.create(family.base(context), style.weight)
    }

    val letterSpacing: Float get() = family.letterSpacing + style.letterSpacing

    val textScaleX: Float get() = 1f
}

/** 标题排列方向：横排 = 中英同一行；竖排 = 中英都竖着读。 */
enum class TextLayout { HORIZONTAL, VERTICAL }

/** 单条日期格式。 */
enum class DateStyle { DOT, CN, EN, SLASH, ISO, SPACED }

/** 时间跨度的精度。 */
enum class RangeUnit { DAY, MONTH, YEAR }

/** 历法。农历换算见 [LunarCalendar]。 */
enum class CalendarKind { GREGORIAN, LUNAR }

/** 日期跨度文案：按三档精度输出「起 — 止」。 */
object ShareDates {

    /** 时间跨度：始终写成「起 — 止」两段，哪怕起止同一天。 */
    fun range(start: Long, end: Long, unit: RangeUnit): String {
        val pattern = when (unit) {
            RangeUnit.DAY -> "yyyy.MM.dd"
            RangeUnit.MONTH -> "yyyy.MM"
            RangeUnit.YEAR -> "yyyy"
        }
        val format = SimpleDateFormat(pattern, Locale.US)
        return "${format.format(Date(start))} — ${format.format(Date(end))}"
    }
}
