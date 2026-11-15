package top.qxwkstudio.travel.ui.common

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 识别横向滑动，同时给纵向滚动让路。
 *
 * 一旦确认是横向手势，就消费后续 touch，避免松手后卡片又触发点击进入详情。
 * 未形成横向手势时仍返回 false，点击和纵向滚动不受影响。
 */
internal fun View.onHorizontalSwipe(
    thresholdDp: Int = 24,
    canSwipe: () -> Boolean = { true },
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
) {
    val threshold = thresholdDp * resources.displayMetrics.density
    val slop = ViewConfiguration.get(context).scaledTouchSlop
    var downX = 0f
    var downY = 0f
    var tracking = false
    var horizontal = false

    fun finishGesture(view: View) {
        view.parent?.requestDisallowInterceptTouchEvent(false)
        view.isPressed = false
        tracking = false
        horizontal = false
    }

    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                tracking = true
                horizontal = false
                false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                tracking = false
                horizontal = false
                false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking || !canSwipe()) return@setOnTouchListener false
                val dx = event.x - downX
                val dy = event.y - downY
                if (!horizontal &&
                    abs(dx) > slop &&
                    abs(dx) > abs(dy) * 1.3f
                ) {
                    horizontal = true
                    view.cancelLongPress()
                    view.isPressed = false
                    // 已确认横滑，阻止父级 ScrollView/RecyclerView 接管成纵向滚动
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                horizontal
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val isHorizontal = horizontal ||
                    (tracking && canSwipe() &&
                        abs(dx) > threshold && abs(dx) > abs(dy) * 1.3f)
                if (tracking && canSwipe() &&
                    abs(dx) > threshold && abs(dx) > abs(dy) * 1.3f
                ) {
                    if (dx < 0) onSwipeLeft() else onSwipeRight()
                }
                finishGesture(view)
                isHorizontal
            }

            MotionEvent.ACTION_CANCEL -> {
                val wasHorizontal = horizontal
                finishGesture(view)
                wasHorizontal
            }

            else -> false
        }
    }
}
