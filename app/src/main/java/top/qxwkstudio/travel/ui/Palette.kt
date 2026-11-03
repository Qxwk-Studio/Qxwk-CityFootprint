package top.qxwkstudio.travel.ui

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.Store

/**
 * 「主题色」预设（口径 16）：一个色值同时喂给两处 ——
 *  1. [overlayRes]：Activity 主题叠加，让按钮/开关/底栏选中态这些**系统控件**跟着换色；
 *  2. [accent]：代码里自己画的强调色（名次圆、进度条、chip、地图城市面）。
 *
 * 为什么存在 SharedPreferences 而不是 `app_settings` 表：Activity 在 setContentView **之前**
 * 就要应用主题叠层，那一刻只能同步读；数据库是异步的。等外观偏好一起迁库时（P6 后半 / 2.1），
 * 这里换成「启动时把值预读进内存」即可，调用方不用改。
 *
 * 默认值 [DEFAULT] 与基础主题里的 appAccent 保持一致（@color/accent）。
 */
object Palette {

    /** 一个预设：key 落盘、label 给弹窗、color 是 #RRGGBB。 */
    data class Preset(val key: String, val label: String, val color: String, val overlay: Int)

    val presets: List<Preset> = listOf(
        Preset("blue", "蓝", "#2563EB", R.style.ThemeOverlay_App_Accent_Blue),
        Preset("green", "绿", "#16A34A", R.style.ThemeOverlay_App_Accent_Green),
        Preset("purple", "紫", "#7C3AED", R.style.ThemeOverlay_App_Accent_Purple),
        Preset("orange", "橙", "#EA580C", R.style.ThemeOverlay_App_Accent_Orange),
        Preset("rose", "玫瑰", "#DB2777", R.style.ThemeOverlay_App_Accent_Rose),
        Preset("teal", "青", "#0891B2", R.style.ThemeOverlay_App_Accent_Teal),
    )

    const val DEFAULT = "blue"

    /** 当前预设。认不出的值（换版本/手改）退回默认，别让界面卡成一片。 */
    fun selected(context: Context): Preset {
        val key = Store(context.applicationContext).accentKey
        return presets.firstOrNull { it.key == key } ?: presets.first()
    }

    /** 代码里画强调色时用这个（与主题叠加同一个色值）。 */
    fun accent(context: Context): Int {
        val preset = selected(context)
        return runCatching { Color.parseColor(preset.color) }
            .getOrDefault(ContextCompat.getColor(context, R.color.accent))
    }

    fun save(context: Context, key: String) {
        Store(context.applicationContext).saveAccentKey(key)
    }
}
