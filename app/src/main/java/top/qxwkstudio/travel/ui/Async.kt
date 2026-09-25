package top.qxwkstudio.travel.ui

import android.os.Handler
import android.os.Looper

/**
 * 「后台跑一段活，回主线程交结果」。
 *
 * 为什么是裸 Thread 而不是协程 / Executors：整个 app 一共十来个请求、全都是用户点一下才发一次，
 * 峰值并发不超过 2。引 kotlinx-coroutines 要加依赖 + R8 规则，Executors 要管线程池和关闭时机，
 * 换来的收益在这点并发下看不见。**没有线程池是有意的**：请求都是短命且稀少的，
 * 起一个线程用完就散比维护一个池更省事。
 *
 * 约定（与本项目其余代码的契约）：
 *  - [work] 里**只能碰纯逻辑与网络**，绝不能碰 View（在非主线程改 UI 会崩）；
 *  - [done] 一定在主线程回调，且 `result` 已经包好异常 —— 不用自己 try/catch，失败看 exceptionOrNull()。
 *
 * 已知取舍：[done] 回调时不检查页面是否还活着。页面已销毁时回调仍会执行，
 * （此时 `_binding` 已置 null，各调用方第一句就是 `?: return`），不会再碰死掉的 View。
 */
object Async {
    private val main = Handler(Looper.getMainLooper())

    fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
        Thread {
            val result = runCatching(work)
            main.post { done(result) }
        }
            // 名字只是给调试器看的：线程转储里一眼分清是哪个请求
            .apply { name = "city-footprint-io" }
            .start()
    }
}