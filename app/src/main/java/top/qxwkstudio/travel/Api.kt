package top.qxwkstudio.travel

/**
 * 后端地址的唯一出处。改地址只改这里。
 *
 * 2.0 是离线单机，**只剩一个请求**：「检查更新」读 `GET /api/manifest`。
 * 1.x 那套（通行证登录、足迹接口、地图边界代理、公告/栏目）随离线化一起删掉了，
 * 这里不再保留用不到的常量 —— 以后要加接口，按这个方法再加。
 */
object Api {
    private const val TRAVEL_BASE = "https://api.travel.qxwkstudio.top/api"

    /**
     * App 清单：只读 `android` 一节（版本名 / version_code / 下载地址 / 更新说明）。
     * 数据在后端 D1 的 `cf_app_version`，由 Worker 的 `GET /api/manifest` 读出来。
     */
    const val VERSION_MANIFEST = "$TRAVEL_BASE/manifest"
}
