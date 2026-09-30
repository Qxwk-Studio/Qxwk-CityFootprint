package top.qxwkstudio.travel

import java.net.URLEncoder

/**
 * 域名与接口路径的**唯一出处**。改地址只改这里，别在调用处硬编码。
 *
 * 三个域名分工（都是 HTTPS，所以清单里不需要 network_security_config 的明文例外，
 * 只有 INTERNET 权限）：
 *   - ACCOUNT_BASE：「Qxwk 通行证」，管登录/登出/身份（另一个仓库 Qxwk-Account）。
 *     密码只发到这里，**不经过足迹后端** —— 足迹后端只认通行证签发的 Bearer token。
 *   - TRAVEL_BASE：本项目后端（Cloudflare Worker，backend/），管足迹数据与地图边界。
 *     `travel.qxwkstudio.top` 是页面（GitHub Pages），API 单独挂在 `api.travel.qxwkstudio.top`。
 *
 * 【阶段 2 已完成】页面已挪去 GitHub Pages、API 挪到 `api.travel.qxwkstudio.top`，
 * 正如当初设计，全程**只改了 TRAVEL_BASE 这一行**，其余代码都按相对路径拼。
 * 注意通行证那个域名不受影响 —— 它是独立服务，不跟着本次迁移走。
 *
 * 另外：安卓走原生 HTTP，不带 Origin 头，**不受前后端跨域那套 CORS 规矩约束**
 * （CORS 是浏览器的限制），所以后端给页面来源配的白名单不需要为 app 额外放行。
 */
object Api {
    /** 通行证站点根（不带 /api）。接口基址与「通行证中心」网页入口都从这里拼，见 [ACCOUNT_BASE] / [PASSPORT_CENTER]。 */
    const val ACCOUNT_ORIGIN = "https://account.qxwkstudio.top"
    const val ACCOUNT_BASE = "$ACCOUNT_ORIGIN/api"
    const val TRAVEL_BASE = "https://api.travel.qxwkstudio.top/api"

    /**
     * 「通行证中心」的网页入口（改昵称 / 颜色 / 密码、生成邀请码）。
     * 这些功能只在通行证那边有，App 内不做，只能跳系统浏览器打开（对应网页 account.html 那张卡）。
     */
    const val PASSPORT_CENTER = "$ACCOUNT_ORIGIN/account.html"

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

    /** 地图数据（城市 + 坐标 + 去过的人）；公开，但带 token 时自己的私密行程才可见。 */
    const val CITIES = "$TRAVEL_BASE/cities"

    /** 公开的城市边界（阿里 DataV GeoJSON，后端做了 7 天缓存）。 */
    fun geo(adcode: Int): String = "$TRAVEL_BASE/geo/$adcode"

    /**
     * 某座城市的行程明细（点开地图上的城市时为底部卡片按需拉取）。
     * 城市名直接进路径，必须百分号编码 —— 中文原样塞进 URL 会抛 MalformedURLException；
     * 编码后再把 `+` 还原成 `%20`：URLEncoder 把空格编成 `+`（那是表单语义），路径段里要的是 `%20`。
     */
    fun city(name: String): String =
        "$TRAVEL_BASE/city/${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}"
}