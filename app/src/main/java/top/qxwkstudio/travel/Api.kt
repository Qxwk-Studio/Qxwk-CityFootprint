package top.qxwkstudio.travel

/**
 * 后端地址的唯一出处。改地址只改这里。
 *
 * 现在这里**只剩一个常量**：「检查更新」读 `GET /api/manifest`。
 * 同步接口（`/sync/register|push|pull`、`/auth/me`、`/server`）的 URL 由 [top.qxwkstudio.travel.data.SyncApi]
 * 按「用户填的服务器地址 + 路径」拼，不经过这里；1.x 的通行证 / 公告 / 栏目接口早已删掉。
 */
object Api {
    private const val TRAVEL_BASE = "https://api.travel.qxwkstudio.top/api"

    /**
     * App 清单：只读 `android` 一节（版本名 / version_code / 下载地址 / 更新说明）。
     * 数据在后端 D1 的 `cf_app_version`，由 Worker 的 `GET /api/manifest` 读出来。
     */
    const val VERSION_MANIFEST = "$TRAVEL_BASE/manifest"
}
