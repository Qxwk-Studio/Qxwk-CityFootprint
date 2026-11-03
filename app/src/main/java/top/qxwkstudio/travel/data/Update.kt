package top.qxwkstudio.travel.data

import top.qxwkstudio.travel.Api
import top.qxwkstudio.travel.logic.VersionManifest
import top.qxwkstudio.travel.net.Http

/**
 * 取 App 清单（后端 `GET /api/manifest`，见 [Api.VERSION_MANIFEST]）。
 *
 * 2.0 只剩一个消费点：「我的 → 检查更新」比清单里的 `android.version_code`。
 * 公告与主页菜单栏目随对应页面一起删掉了（口径 2/3），所以这里不再缓存、也不再有更新说明以外的用途。
 *
 * 不缓存：这是一按按钮才发生一次的请求，缓存只会让「刚发的新版要等过期才看得见」。
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
