package top.qxwkstudio.travel.net

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 一次 HTTP 往返的结果。
 * code == [Http.NETWORK_FAILED]（-1）表示连请求都没发出去（断网、DNS、超时）——
 * 与「服务端答了 500」是两回事，界面上给的提示也不同（一个让用户查网络，一个是我们自己的锅）。
 */
class HttpResult(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
}

/**
 * 极简 HTTP 客户端，只用 JDK 的 HttpURLConnection。
 *
 * 为什么不用 Retrofit/OkHttp：整个 app 一共十来个接口、全是「点一下发一次」，
 * 换来的是一份体积不小的依赖 + 一套 R8 规则 + 一个要跟着升的版本 —— 收益与维护成本不成比例。
 * 代价是并发、连接池这些要自己操心；这里也确实不需要（同一个页面最多同时发两个请求）。
 *
 * 约定：**本方法只在工作线程调用**（见 ui/Async），主线程发网络会抛 NetworkOnMainThreadException。
 */
object Http {
    const val NETWORK_FAILED = -1
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    fun request(
        method: String,
        url: String,
        token: String? = null,
        jsonBody: String? = null,
    ): HttpResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // 安卓这边没有 Origin 头，Accept 显式写一遍：通行证与足迹后端都可能返回 JSON 错误页
                setRequestProperty("Accept", "application/json")
                if (token != null) setRequestProperty("Authorization", "Bearer $token")
                if (jsonBody != null) {
                    doOutput = true
                    // charset 必须写进 Content-Type：中文城市名/备注是 UTF-8，不声明的话
                    // 后端可能按 Latin-1 解，落库变问号（那是最难查的一类脏数据）
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (jsonBody != null) {
                conn.outputStream.use { it.write(jsonBody.toByteArray(StandardCharsets.UTF_8)) }
            }

            val code = conn.responseCode
            // 4xx/5xx 的报文在 errorStream 里，不看它就拿不到后端那句「帐号或密码不正确」
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { reader -> reader.readText() }
            }.orEmpty()
            HttpResult(code, body)
        } catch (e: IOException) {
            HttpResult(NETWORK_FAILED, e.message ?: "网络不可用")
        } finally {
            conn?.disconnect()
        }
    }
}