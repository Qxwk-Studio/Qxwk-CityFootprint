package top.qxwkstudio.travel.data

import android.content.Context
import top.qxwkstudio.travel.Api
import top.qxwkstudio.travel.net.Http
import java.io.File
import java.util.concurrent.Executors

/**
 * 边界数据（`/api/geo/{adcode}`）的**磁盘缓存**，与网页 `docs/index.js` 的 IndexedDB 那份同形态：
 * 每座城市一个文件、文件 mtime 就是写入时间，7 天算过期。
 *
 * 为什么不用 [Store] 那套报文缓存：那份是**账号数据**（换账号 / 写操作要整体作废，
 * 见 [Store.invalidatePayloads] 按 `cache_` 前缀扫），而边界是与账号无关的公共数据 ——
 * 混进去会让「退出登录」顺手把几百个城市边界一起抹掉，下次进地图又得全量重下。
 * 也刻意不装 HTTP 层缓存（`HttpResponseCache`）：net/Http.kt 是裸 HttpURLConnection，
 * 全局装一份要动每一个请求，收益却只落在这一个接口上。
 *
 * 过期与否**不影响返回什么**：调用方先拿旧内容画图，[refresh] 在后台把文件换成新的（SWR）。
 * 这里只经手报文原文，解析仍在 ui/GeoJson。
 */
internal object GeoCache {

    /** 一次读取的结果。[stale] = true 表示文件已过期 —— 内容还能用，但该后台重下。 */
    class Entry(val raw: String, val stale: Boolean)

    /** 读缓存：没有、或读不动，都回 null（当没缓存，转去走网络）。 */
    fun read(context: Context, adcode: Int): Entry? {
        val f = file(context, adcode)
        val raw = runCatching { f.readText() }.getOrNull()
        if (raw.isNullOrEmpty()) return null
        return Entry(raw, System.currentTimeMillis() - f.lastModified() > TTL_MS)
    }

    /**
     * 写缓存：先写临时文件再改名 —— 直接覆写时进程被杀会留下半截 JSON，
     * 那份会被当成**有效**缓存读出来（解析失败退回圆点，却因为 mtime 是新的、7 天内不再重下）。
     */
    fun write(context: Context, adcode: Int, body: String) {
        runCatching {
            val f = file(context, adcode)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(body)
            // Linux 下 rename 直接覆盖目标；万一失败就当这次没缓存上，别把临时文件留在那儿
            if (!tmp.renameTo(f)) tmp.delete()
        }
    }

    /**
     * 后台重下（SWR 里 revalidate 的那一半）：**只换文件，不通知谁重画** ——
     * 过期时调用方已经把旧几何铺上去了，刷新只为下次进页面 / 冷启动能拿到新的。
     * 失败静默：这本来就是尽力而为，报错也没人处理。
     *
     * 串行单线程：一次冷启动可能上百座城市同时过期，并发放出去会把手机与后端一起压垮；
     * 串行跑到哪算哪，剩下的留到下次。线程是 daemon，进程退出不会拖住。
     */
    fun refresh(context: Context, adcode: Int) {
        val app = context.applicationContext
        refresher.execute {
            runCatching {
                val result = Http.request("GET", Api.geo(adcode))
                if (result.ok) write(app, adcode, result.body)
            }
        }
    }

    /**
     * 清空全部边界缓存（「我的」页那颗「清除缓存」，对应网页 app.js 的 clearAppCache）。
     * 与 [Store.invalidatePayloads] 一起构成 App 的「本机缓存」全集 —— 那边管接口报文，这里管边界。
     *
     * 只删文件、目录留着：下次 [write] 直接往里写，不必每次读缓存都重新 mkdirs。
     * 删不动的（正好被读着 / 系统占用）忽略：清理是尽力而为，剩下的下次清理或过期重下都会覆盖。
     */
    fun clear(context: Context) {
        File(context.applicationContext.filesDir, DIR).listFiles()?.forEach { it.delete() }
    }

    /** `filesDir/geo/{adcode}.json`。用 applicationContext：别让缓存文件把 Activity 拽住。 */
    private fun file(context: Context, adcode: Int): File =
        File(File(context.applicationContext.filesDir, DIR).apply { mkdirs() }, "$adcode.json")

    private const val DIR = "geo"

    /** 与后端 `/api/geo` 的 Cache-Control 对齐：7 天。 */
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    private val refresher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "geo-cache-refresh").apply { isDaemon = true }
    }
}