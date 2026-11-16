package top.qxwkstudio.travel.ui.common

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import top.qxwkstudio.travel.R
import kotlin.math.abs
import kotlin.math.min

/**
 * 分段控件（滑块 + 等分标签）与 ViewPager2 的连续联动。
 *
 * 吃的是 `position + positionOffset`：手指拖到一半，滑块就停在一半；标签文字颜色按
 * 「离当前进度多远」在两个颜色之间插值，所以拖动、松手吸附、点标签平滑滚过去，全程都是
 * 同一条连续曲线，没有「切完再动」的跳变。
 *
 * 滑块宽度 = 可分配宽度 / 标签数，平移只按段宽走 —— FrameLayout 的子 View 已经落在
 * padding 内，再加 paddingLeft 会右偏并被裁掉（与 HomeFragment 的 placeSegPill 同一套口径）。
 */
internal class PagerSegments(
    private val track: FrameLayout,
    private val indicator: View,
    private val chipContainer: LinearLayout,
) {
    private val onColor = ContextCompat.getColor(chipContainer.context, R.color.on_accent)
    private val offColor = ContextCompat.getColor(chipContainer.context, R.color.text_secondary)
    private var progress = 0f

    init {
        // 首帧宽度可能是 0（add 与布局同一帧），宽度真变了就按当前进度重新落位
        track.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) apply()
        }
    }

    fun update(value: Float) {
        progress = value
        apply()
    }

    private fun apply() {
        val count = chipContainer.childCount
        if (count == 0) return
        for (index in 0 until count) {
            val chip = chipContainer.getChildAt(index) as TextView
            chip.setTextColor(ColorUtils.blendARGB(onColor, offColor, min(1f, abs(index - progress))))
        }
        val available = track.width - track.paddingLeft - track.paddingRight
        if (available <= 0) return
        val part = available / count
        val lp = indicator.layoutParams
        if (lp.width != part) {
            lp.width = part
            // lp 是同一个实例，直接赋回去不会触发重新布局，得显式请求一次
            indicator.requestLayout()
        }
        indicator.translationX = progress * part
    }
}
