package top.qxwkstudio.travel.data

import android.content.Context
import android.content.SharedPreferences
import top.qxwkstudio.travel.logic.LoginSession
import top.qxwkstudio.travel.logic.Me

/**
 * 本机状态：登录 token 与一点展示用资料。
 *
 * **token 是明文存在 MODE_PRIVATE 的 SharedPreferences 里的** —— 这是已知取舍：
 * 它与网页版把 token 放进 localStorage 属同一级别，安卓没有系统级加密存储，
 * 真机 root 后能被读走（与其它未加固 App 同级风险）。兜底靠三层：
 * 清单里 allowBackup=false（云备份不会把 token 带走）、全站 HTTPS、日志里不打印 token。
 * 也**刻意没上 EncryptedSharedPreferences**：那要求 targetSdk 升到 35+ 才稳定，
 * 且换库要迁移全部存量数据，收益（防 root 后读取）与改动不成比例。
 */
class Store(context: Context) {
    // applicationContext：别让一个 SharedPreferences 把 Activity 拽住
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    val token: String? get() = sp.getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() }

    val isLoggedIn: Boolean get() = token != null

    val nickname: String get() = sp.getString(KEY_NICKNAME, "").orEmpty()
    val color: String get() = sp.getString(KEY_COLOR, "").orEmpty()
    val avatar: String? get() = sp.getString(KEY_AVATAR, null)?.takeIf { it.isNotEmpty() }

    /** 登录成功后落库（token + 通行证顺手给的那几个字段，省得进主页再请求一次）。 */
    fun saveSession(session: LoginSession) {
        sp.edit()
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_NICKNAME, session.nickname)
            .putString(KEY_COLOR, session.color)
            .putString(KEY_AVATAR, session.avatar.orEmpty())
            .apply()
    }

    /** /api/me 回来后刷新资料（昵称/头像可能在通行证那边改过）。 */
    fun saveMe(me: Me) {
        sp.edit()
            .putString(KEY_NICKNAME, me.nickname)
            .putString(KEY_COLOR, me.color)
            .putString(KEY_AVATAR, me.avatar.orEmpty())
            .apply()
    }

    /**
     * 退出登录 / token 失效时清干净。
     * 用逐个 remove 而不是 clear()：以后往这个文件里加「与账号无关」的偏好（比如地图类型）
     * 时，clear() 会连它们一起抹掉，那种 bug 只在退出登录后出现一次，很难往回查。
     */
    fun clearSession() {
        sp.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_NICKNAME)
            .remove(KEY_COLOR)
            .remove(KEY_AVATAR)
            .apply()
    }

    private companion object {
        const val NAME = "city_footprint"
        const val KEY_TOKEN = "token"
        const val KEY_NICKNAME = "nickname"
        const val KEY_COLOR = "color"
        const val KEY_AVATAR = "avatar"
    }
}