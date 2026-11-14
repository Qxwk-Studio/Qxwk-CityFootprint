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
    onDrag: (Float) -> Unit = {},
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
) {
    val threshold = thresholdDp * resources.displayMetrics.density
    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    var downX = 0f
    var downY = 0f
    var tracking = false
    var horizontal = false
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                tracking = true
                horizontal = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> tracking = false

            MotionEvent.ACTION_MOVE -> {
                if (tracking && canSwipe()) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!horizontal && abs(dx) > slop && abs(dx) > abs(dy) * 1.4f) {
                        horizontal = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (horizontal) onDrag(dx)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (tracking && horizontal && canSwipe()) {
                    val dx = event.x - downX
                    if (abs(dx) > threshold) {
                        if (dx < 0) onSwipeLeft() else onSwipeRight()
                    } else {
                        onDrag(0f)
                    }
                } else if (horizontal) {
                    onDrag(0f)
                }
                view.parent?.requestDisallowInterceptTouchEvent(false)
                tracking = false
                horizontal = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (horizontal) onDrag(0f)
                view.parent?.requestDisallowInterceptTouchEvent(false)
                tracking = false
                horizontal = false
            }
        }
        false
    }
}
