package top.qxwkstudio.travel.ui.common

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.Prefs

/**
 * 「主题色」预设（口径 16）：一个色值同时喂给两处 ——
 *  1. [overlayRes]：Activity 主题叠加，让按钮/开关/底栏选中态这些**系统控件**跟着换色；
 *  2. [accent]：代码里自己画的强调色（名次圆、进度条、chip、地图城市面）。
 *
 * 为什么还要一层内存缓存：Activity 在 setContentView **之前**就要应用主题叠层，
 * 只能同步拿值 —— 进程启动时从 `app_settings` 读进内存（见 data/Prefs）。
 *
 * 默认值 [DEFAULT] 与基础主题里的 appAccent 保持一致（@color/accent）。
 */
object Palette {

    /** 一个预设：key 落盘、label 给弹窗、color 是 #RRGGBB。 */
    data class Preset(val key: String, val label: String, val color: String, val overlay: Int)

    val presets: List<Preset> = listOf(
        Preset("system", "跟随系统", "", 0),
        Preset("rose", "红", "#DC2626", R.style.ThemeOverlay_App_Accent_Rose),
        Preset("orange", "橙", "#EA580C", R.style.ThemeOverlay_App_Accent_Orange),
        Preset("green", "绿", "#16A34A", R.style.ThemeOverlay_App_Accent_Green),
        Preset("teal", "青", "#0891B2", R.style.ThemeOverlay_App_Accent_Teal),
        Preset("blue", "蓝", "#2563EB", R.style.ThemeOverlay_App_Accent_Blue),
        Preset("purple", "紫", "#7C3AED", R.style.ThemeOverlay_App_Accent_Purple),
    )

    const val DEFAULT = "teal"

    /** 当前预设。认不出的值（换版本/手改）退回默认，别让界面卡成一片。 */
    fun selected(): Preset {
        val key = Prefs.accentKey.ifBlank { DEFAULT }
        return presets.firstOrNull { it.key == key }
            ?: presets.firstOrNull { it.key == DEFAULT }
            ?: presets.first()
    }

    /** 代码里画强调色时用这个（与主题叠加同一个色值；跟随系统时走动态配色，失败回退青色）。 */
    fun accent(context: Context): Int {
        val preset = selected()
        if (preset.key == "system") {
            val teal = runCatching { Color.parseColor("#0891B2") }
                .getOrDefault(ContextCompat.getColor(context, R.color.accent))
            val dynamic = runCatching {
                com.google.android.material.color.DynamicColors.isDynamicColorAvailable()
            }.getOrDefault(false)
            if (!dynamic) return teal
            return runCatching {
                val wrapped = com.google.android.material.color.DynamicColors
                    .wrapContextIfAvailable(context)
                if (wrapped === context) return teal
                com.google.android.material.color.MaterialColors.getColor(
                    wrapped,
                    com.google.android.material.R.attr.colorPrimary,
                    teal,
                )
            }.getOrDefault(teal)
        }
        return runCatching { Color.parseColor(preset.color) }
            .getOrDefault(ContextCompat.getColor(context, R.color.accent))
    }

    /** 在 setContentView 之前把主题叠层应用好；「跟随系统」走 Android 12+ 壁纸取色。 */
    fun apply(activity: android.app.Activity) {
        if (selected().key == "system") {
            val dynamic = runCatching {
                com.google.android.material.color.DynamicColors.isDynamicColorAvailable()
            }.getOrDefault(false)
            val ok = dynamic && runCatching {
                com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(activity)
            }.isSuccess
            if (!ok) {
                // 壁纸取色不可用（低版本 / 厂商裁剪）时回退青色
                activity.theme.applyStyle(R.style.ThemeOverlay_App_Accent_Teal, true)
            }
        } else {
            activity.theme.applyStyle(selected().overlay, true)
        }
    }

    fun save(context: Context, key: String) {
        Prefs.saveAccentKey(context.applicationContext, key)
    }
}
