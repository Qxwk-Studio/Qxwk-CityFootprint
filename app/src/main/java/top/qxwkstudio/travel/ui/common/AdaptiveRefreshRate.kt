package top.qxwkstudio.travel.ui.common

import android.app.Activity
import android.os.Build
import android.view.Window

/**
 * 高刷新率 / 可变刷新率适配：
 * - 选择当前显示器支持的最高刷新率，交给系统决定实际刷新率；
 * - Android 15+ 打开触控帧率 boost，并关闭“均衡省电”对高帧率的压制。
 *
 * 不锁定 preferredDisplayModeId：LTPO / VRR 屏幕仍可自行降低刷新率省电。
 */
internal object AdaptiveRefreshRate {

    fun apply(activity: Activity) {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        } ?: return

        val maxRate = display.supportedModes
            .maxOfOrNull { it.refreshRate }
            ?: display.refreshRate.takeIf { it > 0f }
            ?: return

        val attrs = activity.window.attributes
        attrs.preferredRefreshRate = maxRate
        activity.window.attributes = attrs

        // API 35 的窗口级触控 boost / 省电均衡开关；compileSdk 34 下用反射调用。
        if (Build.VERSION.SDK_INT >= 35) {
            invokeWindowMethod(activity.window, "setFrameRateBoostOnTouchEnabled", true)
            invokeWindowMethod(activity.window, "setFrameRatePowerSavingsBalanced", false)
        }
    }

    private fun invokeWindowMethod(window: Window, name: String, value: Boolean) {
        runCatching {
            Window::class.java
                .getMethod(name, Boolean::class.javaPrimitiveType)
                .invoke(window, value)
        }
    }
}
