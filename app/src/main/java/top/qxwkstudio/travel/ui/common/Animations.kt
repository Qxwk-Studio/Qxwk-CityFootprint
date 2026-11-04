package top.qxwkstudio.travel.ui.common

import android.view.View
import android.view.animation.PathInterpolator

/**
 * 页面进入动画：淡入 + 轻微上移，200ms。
 *
 * 用了和底部导航药丸同一条缓动曲线（0.2, 0, 0, 1），整 app 的位移观感一致。
 * 只动 alpha / translationY，不改布局尺寸 —— 不会引起重新测量。
 */
internal fun View.fadeSlideIn(duration: Long = 200L) {
    if (visibility != View.VISIBLE) return
    val offset = 10f * resources.displayMetrics.density
    alpha = 0f
    translationY = offset
    animate()
        .alpha(1f)
        .translationY(0f)
        .setDuration(duration)
        .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
        .start()
}
