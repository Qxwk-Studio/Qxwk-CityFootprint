package top.qxwkstudio.travel.ui.common

import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 在不拦截纵向滚动的前提下，识别横向滑动。
 *
 * OnTouchListener 始终返回 false：真正的点击、列表滚动和输入框手势仍交给原 View。
 */
internal fun View.onHorizontalSwipe(
    thresholdDp: Int = 48,
    canSwipe: () -> Boolean = { true },
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
) {
    val threshold = thresholdDp * resources.displayMetrics.density
    var downX = 0f
    var downY = 0f
    var tracking = false
    setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                tracking = true
            }

            MotionEvent.ACTION_POINTER_DOWN -> tracking = false

            MotionEvent.ACTION_UP -> {
                if (tracking && canSwipe()) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > threshold && abs(dx) > abs(dy) * 1.4f) {
                        if (dx < 0) onSwipeLeft() else onSwipeRight()
                    }
                }
                tracking = false
            }

            MotionEvent.ACTION_CANCEL -> tracking = false
        }
        false
    }
}
