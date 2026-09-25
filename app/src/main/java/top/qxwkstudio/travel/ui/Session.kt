package top.qxwkstudio.travel.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.ApiException
import top.qxwkstudio.travel.data.Store
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 会话失效（token 过期 / 通行证那边撤销了它）的**唯一出口**。
 *
 * 为什么要集中一处：四个 tab 里的三个、加上两个编辑页都会发请求，任何一处拿到 401 都该做
 * 「清 token → 回登录页」。如果各页各写一遍，用户会先看到「登录已过期」的提示被弹三次
 * （足迹、统计、地图各一次），然后在还留在主页的情况下被反复踢。
 *
 * 清 token 是做在**客户端**的：只要后端说这个 token 不认了，本机那份留着也只会让后面每个请求都失败。
 */
object Session {

    /**
     * 同一时刻只处理一次失效。
     * 主线程并发场景很实际：地图页、统计页可能同时在拉数据，两个请求一起 401 ——
     * 没有这道闸门就会弹两次 Toast、并且启两个 LoginActivity。
     */
    private val handling = AtomicBoolean(false)

    fun expired(activity: Activity) {
        if (!handling.compareAndSet(false, true)) return
        Store(activity).clearSession()
        Toast.makeText(activity, R.string.common_session_expired, Toast.LENGTH_LONG).show()
        activity.gotoLogin()
        activity.finish()
    }

    /** 回到登录页时重新打开闸门（LoginActivity.onCreate 调用），否则下一次失效会被这道闸门吃掉。 */
    fun reset() {
        handling.set(false)
    }
}

/**
 * 各页面统一的失败处理：**401 走 [Session.expired]**，其余（网络不通、403 越权、5xx）
 * 弹后端给的那句话 —— 后端那些文案（「无权操作他人的记录」）是写给用户看的，客户端再编一遍只会不一致。
 *
 * 写成 Activity 的扩展是为了强调「必须在主线程、且有 Activity 才能用」；
 * Fragment 里一律用 `activity?.handleApiFailure(e)`（回调返回时 Fragment 可能已 detach）。
 */
fun Activity.handleApiFailure(e: Throwable) {
    if (e is ApiException && e.code == 401) {
        Session.expired(this)
        return
    }
    Toast.makeText(this, e.message ?: getString(R.string.common_error), Toast.LENGTH_LONG).show()
}

/**
 * 开局一个干净的 task 去登录页：CLEAR_TASK 让返回键不会退回「已经没有登录态的那一屏」。
 * 退出登录与 401 都走这里（两处行为必须一致，否则退出登录后按返回还能看到主页）。
 */
internal fun Context.gotoLogin() {
    startActivity(
        Intent(this, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    )
}