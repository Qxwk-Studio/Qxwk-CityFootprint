package top.qxwkstudio.travel.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 外观（浅色 / 深色 / 跟随系统）—— 「我的 → 设置 → 外观」里选的那一项，见 [Store.appearance]。
 *
 * [value] 是落进 SharedPreferences 的字符串：**存字符串而不是 ordinal**，这样以后调整枚举顺序
 * （或在中间插一档）不会把用户已经选好的外观读成另一档。
 */
enum class Appearance(val value: String) {
    /** 跟随系统深浅色（默认）。 */
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
}

/**
 * 本机**设备级偏好**（外观、主题色）。
 *
 * 2.0 是离线单机，没有登录态，所以这里只剩这两样 —— 1.x 的 token / 昵称 / 报文缓存都已随登录
 * 与网络层一起删掉。昵称、头像、徽章色这类「资料」放数据库（`app_settings`，见 data/SettingsRepo），
 * 那些要跟着数据走；而外观与主题色必须在 Activity `setContentView` **之前**同步读到，
 * 所以留在 SharedPreferences。等这些偏好整体迁库时，改成「启动时预读进内存」即可。
 *
 * 为什么用 applicationContext：别让一份偏好把 Activity 拽住。
 */
class Store(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * 外观偏好，默认 [Appearance.SYSTEM]。
     *
     * 读出来的值**认不出就当默认**（枚举里查不到 → SYSTEM）：这个值只有本 app 自己写，
     * 但换版本、手改、清数据几种情况都可能留下怪值，不能让它把界面卡成一片空白。
     *
     * 真正把它应用到界面的是 CityFootprintApp（启动时）与 MineFragment（用户改完立刻生效），
     * 本类只负责存取。
     */
    val appearance: Appearance
        get() = Appearance.entries.firstOrNull { it.value == sp.getString(KEY_APPEARANCE, null) }
            ?: Appearance.SYSTEM

    fun saveAppearance(appearance: Appearance) {
        sp.edit().putString(KEY_APPEARANCE, appearance.value).apply()
    }

    /**
     * 主题色预设的 key（见 ui/Palette）。同样是设备级偏好。
     * 空串表示没设过，[Palette.selected] 会退回默认预设。
     */
    val accentKey: String get() = sp.getString(KEY_ACCENT, "").orEmpty()

    fun saveAccentKey(key: String) {
        sp.edit().putString(KEY_ACCENT, key).apply()
    }

    private companion object {
        const val NAME = "city_footprint"

        /** 外观偏好，见 [appearance]。 */
        const val KEY_APPEARANCE = "appearance"

        /** 主题色预设 key，见 [accentKey]。 */
        const val KEY_ACCENT = "accent_key"
    }
}
