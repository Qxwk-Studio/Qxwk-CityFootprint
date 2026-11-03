package top.qxwkstudio.travel.ui

import android.content.Context
import android.widget.Toast

/**
 * 2.0 的**统一提示出口**：所有一次性的结果提示与错误提示都走 Toast。
 *
 * 为什么收口到一处：
 *  - 之前是「内联红字 + Toast」混着用，用户反馈内联那行在长表单里根本看不到；
 *  - 各页各写一遍 `Toast.makeText(...).show()`，收口后改时长/改成 Snackbar 只动这里。
 *
 * 什么**不**走这里：需要用户做决定的（删除确认、放弃修改、发现新版本）仍然是对话框 ——
 * 那是「确认」不是「通知」，用 Toast 会直接丢掉那个决定。
 */
internal fun Context.notify(message: CharSequence, long: Boolean = false) {
    Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

internal fun Context.notify(resId: Int, long: Boolean = false) {
    notify(getString(resId), long)
}
