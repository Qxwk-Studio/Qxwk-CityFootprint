package top.qxwkstudio.travel.data

import top.qxwkstudio.travel.Api
import top.qxwkstudio.travel.logic.VersionManifest
import top.qxwkstudio.travel.net.Http

/**
 * 取 App 清单（后端 `GET /api/manifest`，见 [Api.VERSION_MANIFEST]；数据在 D1 的四张 cf_ 表里）。
 * 两处用它：「检查更新」比 `android.version_code`，「公告」页与主页的公告横幅取 `notices`
 * （主页菜单栏目由 MainActivity 用同一份清单的 `menu`）。
 *
 * 放在 data/ 而不是 [GeoCache] 那种带磁盘缓存的对象里：
 * 前者是**一按按钮才发生一次**的请求，后者是进页面拉一次、失败就静默 ——
 * 都不值得缓存。「公告」更不能缓：缓存它会让刚发的公告要等过期才看得见、撤回的公告还挂在横幅上
 * （主页横幅只在页面创建与下拉刷新时拉，切 tab 不会重拉，见 ui/VisitsFragment）。
 */
internal object Update {

    /**
     * 取清单。网络不通 / 报文解不动都返回 null，由调用方给一句提示 ——
     * 这里不抛异常：检查更新失败没什么可补救的，用户重试一次就是了。
     */
    fun fetch(): VersionManifest? {
        val result = Http.request("GET", Api.VERSION_MANIFEST)
        if (!result.ok) return null
        return runCatching { json.decodeFromString(VersionManifest.serializer(), result.body) }.getOrNull()
    }
}