package top.qxwkstudio.travel

/**
 * 域名与接口路径的**唯一出处**。改地址只改这里，别在调用处硬编码。
 *
 * 两个域名分工（都是 HTTPS，所以清单里不需要 network_security_config 的明文例外，
 * 只有 INTERNET 权限）：
 *   - ACCOUNT_BASE：「Qxwk 通行证」，管登录/登出/身份（另一个仓库 Qxwk-Account）。
 *     密码只发到这里，**不经过足迹后端** —— 足迹后端只认通行证签发的 Bearer token。
 *   - TRAVEL_BASE：本项目后端（Cloudflare Worker，backend/），管足迹数据与地图边界。
 *
 * 【阶段 2 的切换点】规划里阶段 2 会把 API 挪到 `api.travel.qxwkstudio.top`、页面挪去 Pages。
 * 届时**只改这一行**（TRAVEL_BASE），其余代码全部按相对路径拼，不用动。
 * 注意通行证那个域名不受影响 —— 它是独立服务，不跟着本次迁移走。
 */
object Api {
    const val ACCOUNT_BASE = "https://account.qxwkstudio.top/api"
    const val TRAVEL_BASE = "https://travel.qxwkstudio.top/api"

    /**
     * 登录时上报的「来源应用名」。安卓没有 Origin 头，通行证只能靠这个字段记来源；
     * 若通行证侧没登记过这个名字，会话会被记成「未登记来源」，**登录照常可用**（见 README）。
     */
    const val CLIENT_NAME = "CityFootprint Android"

    // ── 通行证 ──
    const val LOGIN = "$ACCOUNT_BASE/login"
    const val LOGOUT = "$ACCOUNT_BASE/logout"

    // ── 足迹后端 ──
    /** 校验 token 并取当前用户资料；token 失效时回 401。 */
    const val ME = "$TRAVEL_BASE/me"
    const val MY_VISITS = "$TRAVEL_BASE/my-visits"
    const val VISITS = "$TRAVEL_BASE/visits"
    /** 公开统计，不需要 Bearer。 */
    const val STATS = "$TRAVEL_BASE/stats"

    /** 公开的城市边界（阿里 DataV GeoJSON，后端做了 24 小时缓存）。 */
    fun geo(adcode: Int): String = "$TRAVEL_BASE/geo/$adcode"
}