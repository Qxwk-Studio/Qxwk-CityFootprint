package top.qxwkstudio.travel.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicInteger
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

    /** 登录成功后落库（token + 通行证顺手给的那几个字段，省得进主页再请求一次）。
     *  顺手作废报文缓存：换账号时不能把上一个人的足迹 / 统计留在这个文件里。 */
    fun saveSession(session: LoginSession) {
        invalidatePayloads()
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
     * 报文缓存：整包原文 + 落库时间，有效期 [CACHE_TTL_MS]（一天）。
     * 请求成功时由 data/VisitRepo 存进来（key 见那边的 CACHE_*），一天内的读取直接命中、不再打网络；
     * 用户「下拉刷新」时带 force 跳过它。
     *
     * 存的是**原始 JSON**：万一报文结构变了（新版 App 读到旧缓存），解析那一步会失败，
     * 调用方要把「解析不过的缓存」当成没有缓存、转去走网络 —— 不能拿旧结构硬渲染。
     */
    fun cachedPayload(key: String): String? {
        val at = sp.getLong(atKey(key), 0L)
        if (at == 0L || System.currentTimeMillis() - at > CACHE_TTL_MS) return null
        return sp.getString(key, null)
    }

    fun savePayload(key: String, body: String) {
        sp.edit().putString(key, body).putLong(atKey(key), System.currentTimeMillis()).apply()
    }

    /**
     * 数据版本号，[invalidatePayloads] 每次自增。**只在内存里**，进程重启即归零 —— 够用就够：
     * 页面（目前只有地图页）拿它跟「自己已经画出来的那份数据」的版本比，判断切回 tab 时要不要重拉。
     * 刻意不落盘：重启后本来就要重新加载，落盘反而多出一份要维护、还可能过期的状态。
     *
     * 计数放 companion 而不是实例字段：`Store(context)` 是每个 Activity/Fragment 各 new 一个的
     * （全仓十来处），实例字段会各算各的 —— 写操作的 Activity 涨的那个号，地图页那个实例根本看不见。
     */
    val dataVersion: Int get() = versionCounter.get()

    /**
     * 缓存整体作废。两种时机：写操作成功后（自己的足迹变了，成就与统计跟着变）、换账号时。
     * 不按 key 精细区分 —— 就两份报文，一起丢最省心也不会漏。
     *
     * 按前缀扫而不是逐个列 key：**凡是 `cache_` 开头的偏好都归这里管**，
     * 以后要缓存别的接口，只要 key 用这个前缀（落库时间 key 是「key + _at」同样带前缀），
     * 不必记得回来改这一处；反过来，别把 `cache_` 前缀挪作它用，会被这里一起抹掉。
     */
    fun invalidatePayloads() {
        // 顺手记一笔版本：报文缓存丢了，但「已经画在界面上的旧数据」还在各页手里
        versionCounter.incrementAndGet()
        val edit = sp.edit()
        sp.all.keys.filter { it.startsWith(CACHE_PREFIX) }.forEach { edit.remove(it) }
        edit.apply()
    }

    private fun atKey(key: String): String = "${key}_at"

    /**
     * 退出登录 / token 失效时清干净。
     * 用逐个 remove 而不是 clear()：以后往这个文件里加「与账号无关」的偏好（比如地图类型）
     * 时，clear() 会连它们一起抹掉，那种 bug 只在退出登录后出现一次，很难往回查。
     * （报文缓存是账号数据，必须一起丢 —— 见 invalidatePayloads。）
     */
    fun clearSession() {
        invalidatePayloads()
        sp.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_NICKNAME)
            .remove(KEY_COLOR)
            .remove(KEY_AVATAR)
            .apply()
    }

    private companion object {
        /** 见 [dataVersion]。跨实例共享，所以是伴生对象里的静态字段。 */
        val versionCounter = AtomicInteger(0)

        const val NAME = "city_footprint"
        const val KEY_TOKEN = "token"
        const val KEY_NICKNAME = "nickname"
        const val KEY_COLOR = "color"
        const val KEY_AVATAR = "avatar"

        /** 报文缓存的有效期：一天（一天内不重复拉，除非用户手动刷新）。 */
        const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

        /** 报文缓存 key 的前缀，见 [invalidatePayloads]。 */
        const val CACHE_PREFIX = "cache_"
    }
}