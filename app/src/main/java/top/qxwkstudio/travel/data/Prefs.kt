package top.qxwkstudio.travel.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.data.db.AppSettingEntity

/** 外观（浅色 / 深色 / 跟随系统）。[value] 是落库字符串，不存 ordinal。 */
enum class Appearance(val value: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
}

/**
 * 设备级偏好（外观 / 主题色）。**权威存储在 `app_settings` 表，SharedPreferences 只当启动缓存**。
 *
 * 为什么还要缓存：Activity 在 `setContentView` 之前就要应用主题与夜间模式，那一刻只能同步拿值；
 * 而启动时**不能在主线程开 Room**（上一版为了同步读开了 `allowMainThreadQueries`，那是坏味道）。
 * 所以写入走「内存 + SP + 异步落库」，启动只读 SP；后台再把库里的值同步回来，两边自愈。
 */
object Prefs {

    private const val SP_NAME = "city_footprint"
    private const val SP_APPEARANCE = "appearance"
    private const val SP_ACCENT = "accent_key"
    private const val SP_MIGRATED = "prefs_migrated"

    private const val KEY_APPEARANCE = "appearance"
    private const val KEY_ACCENT = "accent_key"
    private const val KEY_NETWORK = "network_allowed"

    @Volatile private var appearanceValue = Appearance.SYSTEM
    @Volatile private var accentValue = ""
    @Volatile private var networkValue = false

    fun init(context: Context) {
        val app = context.applicationContext
        val sp = app.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        appearanceValue = Appearance.entries.firstOrNull { it.value == sp.getString(SP_APPEARANCE, null) }
            ?: Appearance.SYSTEM
        accentValue = sp.getString(SP_ACCENT, null).orEmpty()
        networkValue = sp.getBoolean(KEY_NETWORK, false)

        // 后台把库里的值同步回来；若库里没有（首次迁库），把 SP 的值写进库
        Thread {
            runCatching {
                val dao = AppDatabase.get(app).settings()
                val savedAppearance = runBlocking { dao.get(KEY_APPEARANCE) }
                val savedAccent = runBlocking { dao.get(KEY_ACCENT) }
                // SP 是最近一次写入的同步副本：以它为准，并把库里的旧值纠正过来，
                // 避免「刚切完外观、下次启动又被旧 DB 值覆盖」。
                val spAppearance = sp.getString(SP_APPEARANCE, null)
                if (spAppearance == null && savedAppearance != null) {
                    appearanceValue = Appearance.entries.firstOrNull { it.value == savedAppearance }
                        ?: Appearance.SYSTEM
                    sp.edit().putString(SP_APPEARANCE, savedAppearance).apply()
                } else if (savedAppearance != appearanceValue.value) {
                    runBlocking { dao.put(AppSettingEntity(KEY_APPEARANCE, appearanceValue.value)) }
                }
                if (savedAccent != null) {
                    accentValue = savedAccent
                    sp.edit().putString(SP_ACCENT, savedAccent).apply()
                } else if (accentValue.isNotEmpty()) {
                    runBlocking { dao.put(AppSettingEntity(KEY_ACCENT, accentValue)) }
                }
                sp.edit().putBoolean(SP_MIGRATED, true).apply()
            }
        }.apply { name = "prefs-sync"; isDaemon = true }.start()
    }

    val appearance: Appearance get() = appearanceValue

    val accentKey: String get() = accentValue

    /** 可选「网络」开关：默认关闭，只影响检查更新这类主动联网。 */
    val networkAllowed: Boolean get() = networkValue

    fun saveAppearance(context: Context, appearance: Appearance) {
        appearanceValue = appearance
        write(context, KEY_APPEARANCE, appearance.value)
    }

    fun saveNetworkAllowed(context: Context, allowed: Boolean) {
        networkValue = allowed
        context.applicationContext
            .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NETWORK, allowed).apply()
    }

    fun saveAccentKey(context: Context, key: String) {
        accentValue = key
        write(context, KEY_ACCENT, key)
    }

    /** 内存 → SP（启动可读）→ 后台落库（权威存储）。 */
    private fun write(context: Context, key: String, value: String) {
        val app = context.applicationContext
        val sp = app.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        if (key == KEY_APPEARANCE) sp.edit().putString(SP_APPEARANCE, value).commit()
        if (key == KEY_ACCENT) sp.edit().putString(SP_ACCENT, value).commit()

        Thread {
            runCatching {
                runBlocking { AppDatabase.get(app).settings().put(AppSettingEntity(key, value)) }
            }
        }.apply { name = "prefs-write"; isDaemon = true }.start()
    }
}
