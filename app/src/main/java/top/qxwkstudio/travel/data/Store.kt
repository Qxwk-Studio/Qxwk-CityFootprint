package top.qxwkstudio.travel.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicInteger
import top.qxwkstudio.travel.logic.LoginSession
import top.qxwkstudio.travel.logic.Me

/**
 * 外观（浅色 / 深色 / 跟随系统）—— 「我的 → 设置 → 外观」里选的那一项，见 [Store.appearance]。
 *
 * [value] 是落进 SharedPreferences 的字符串：**存字符串而不是 ordinal**，这样以后调整枚举顺序
 * （或在中间插一档）不会把用户已经选好的外观读成另一档。
 *
 * 与 `logic` 包里的枚举（如 Transport）不同，它不进接口报文、纯本机偏好，所以没有 code 映射。
 */
enum class Appearance(val value: String) {
    /** 跟随系统深浅色（默认）。 */
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
}

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

    /** 本地用户 id（通行证签发）。「我的」页展示 UID 用，对应网页 account.js 里的 s.userId。 */
    val userId: Long get() = sp.getLong(KEY_UID, 0L)

    /**
     * 管理员标记。「我的」页的管理员徽章用它（对应网页 account.html 的 #adminBadge）。
     *
     * **只有 [saveMe]（/api/me）会把它写进来** —— 通行证的登录响应里根本没有 is_admin 这个字段
     * （见 data/Auth 的 LoginResponse），所以进主页那次 /api/me 是唯一来源；网络不通时它就是 false，
     * 与网页端同一行为（那边也只从 /api/me 拿）。
     */
    val isAdmin: Boolean get() = sp.getBoolean(KEY_IS_ADMIN, false)

    /** 登录成功后落库（token + 通行证顺手给的那几个字段，省得进主页再请求一次）。
     *  顺手作废报文缓存：换账号时不能把上一个人的足迹 / 统计留在这个文件里。 */
    fun saveSession(session: LoginSession) {
        invalidatePayloads()
        sp.edit()
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_NICKNAME, session.nickname)
            .putString(KEY_COLOR, session.color)
            .putString(KEY_AVATAR, session.avatar.orEmpty())
            .putLong(KEY_UID, session.userId)
            .apply()
    }

    /** /api/me 回来后刷新资料（昵称/头像可能在通行证那边改过）。is_admin 也在这里落库，见 [isAdmin]。 */
    fun saveMe(me: Me) {
        sp.edit()
            .putString(KEY_NICKNAME, me.nickname)
            .putString(KEY_COLOR, me.color)
            .putString(KEY_AVATAR, me.avatar.orEmpty())
            .putLong(KEY_UID, me.userId)
            .putBoolean(KEY_IS_ADMIN, me.isAdmin)
            .apply()
    }

    /**
     * 已读公告的最大 id（0 = 一条都没读过）。主页顶部那条公告横幅拿它跟清单里的公告比，
     * 比它大的就是未读（见 ui/VisitsFragment.refreshNoticeBanner）。
     *
     * 存**最大 id 一个数**而不是「已读 id 的集合」：公告只增不改，记住最新读的那条就等于记住了更早的全部。
     * 它是**设备级偏好、与账号无关**（谁登录看到的都是同一份公告），所以 [clearSession] 不把它一并清掉 ——
     * 那正是 clearSession 不写 clear() 的原因之一。
     */
    val noticeReadId: Int get() = sp.getInt(KEY_NOTICE_READ_ID, 0)

    /**
     * 记下已读公告的最大 id。**只涨不落**：调用方偶尔给来一个更小的值（并发、界面重放）时忽略掉，
     * 免得把已读进度往回拨、读过的公告又冒出横幅。
     */
    fun saveNoticeReadId(id: Int) {
        if (id <= noticeReadId) return
        sp.edit().putInt(KEY_NOTICE_READ_ID, id).apply()
    }

    /**
     * 外观偏好（浅色 / 深色 / 跟随系统），默认 [Appearance.SYSTEM]。
     *
     * 也是**设备级偏好、与账号无关**（换个人登录不该把外观也换掉），所以 [clearSession] 不清它；
     * 键名不带 `cache_` 前缀，[invalidatePayloads] 也不会碰（见那边的说明）。
     *
     * 读出来的值**认不出就当默认**（枚举里查不到 → SYSTEM）：这个值只有本 app 自己写，
     * 但换版本、手改、清数据几种情况都可能留下怪值，不能让它把界面卡成一片空白。
     *
     * 真正把它应用到界面的是 CityFootprintApp（启动时）与 ProfileFragment（用户改完立刻生效），
     * 本类只负责存取。
     */
    val appearance: Appearance
        get() = Appearance.entries.firstOrNull { it.value == sp.getString(KEY_APPEARANCE, null) }
            ?: Appearance.SYSTEM

    fun saveAppearance(appearance: Appearance) {
        sp.edit().putString(KEY_APPEARANCE, appearance.value).apply()
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
     * 用逐个 remove 而不是 clear()：本文件里已经有几条「与账号无关」的偏好（[noticeReadId]、
     * [appearance]），clear() 会连它们一起抹掉 —— 那种 bug 只在退出登录后出现一次，很难往回查。
     * 以后再加同类偏好，记得**只往这里加要清的键**，别图省事换成 clear()。
     * （报文缓存是账号数据，必须一起丢 —— 见 invalidatePayloads。）
     */
    fun clearSession() {
        invalidatePayloads()
        sp.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_NICKNAME)
            .remove(KEY_COLOR)
            .remove(KEY_AVATAR)
            .remove(KEY_UID)
            .remove(KEY_IS_ADMIN)
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
        const val KEY_UID = "uid"
        const val KEY_IS_ADMIN = "is_admin"

        /** 已读公告的最大 id，见 [noticeReadId]。**不在 [clearSession] 的清除名单里**（设备级偏好）。 */
        const val KEY_NOTICE_READ_ID = "notice_read_id"

        /** 外观偏好，见 [appearance]。同样**不在 [clearSession] 的清除名单里**（设备级偏好）。 */
        const val KEY_APPEARANCE = "appearance"

        /** 报文缓存的有效期：一天（一天内不重复拉，除非用户手动刷新）。 */
        const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

        /** 报文缓存 key 的前缀，见 [invalidatePayloads]。 */
        const val CACHE_PREFIX = "cache_"
    }
}