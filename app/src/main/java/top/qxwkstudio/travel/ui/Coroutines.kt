package top.qxwkstudio.travel.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「后台跑一段阻塞活，回主线程交结果」——取代旧的裸 Thread 版 Async。
 *
 * 用法上唯一要记住的一点：**它挂在调用方的 scope 上**：
 *  - Fragment 用 `viewLifecycleOwner.lifecycleScope.runIo(...)`；
 *  - Activity 用 `lifecycleScope.runIo(...)`。
 * 页面销毁时协程自动取消，所以回调里**不需要**再判 `_binding` 是否还在
 * （旧的 `val bd = _binding ?: return@run` 那一堆样板就是这么省掉的）。
 *
 * 约定（与本项目其余代码的契约）：
 *  - [work] 里只能碰纯逻辑与网络，**绝不能碰 View**（它在 Dispatchers.IO 上跑）；
 *  - [done] 一定在主线程回调，且 result 已包好异常 —— 不用自己 try/catch，失败看 exceptionOrNull()；
 *  - **取消时不回调 [done]**：见下面 CancellationException 的说明。
 */
internal fun <T> CoroutineScope.runIo(work: suspend () -> T, done: (Result<T>) -> Unit) {
    launch {
        val result = try {
            Result.success(withContext(Dispatchers.IO) { work() })
        } catch (e: CancellationException) {
            // 页面销毁/scope 被取消：静默退出，不回调 done。
            // 必须原样抛出而不能吞进 Result —— 否则会被当成一次「失败」交给界面，
            // 界面又会去碰已经没了的 View，正好是这套机制要避免的那类崩溃。
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
        done(result)
    }
}

/**
 * 进程级 scope：给「尽力而为、且不依赖任何界面存活」的请求用。
 *
 * 目前只有一个用处 —— 退出登录时通知通行证撤销会话。那一刻页面正在关闭，
 * 挂在 UI scope 上会被连带取消，请求就发不出去了；而它失败也无所谓（本地 token 已经清掉）。
 */
internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
