package top.qxwkstudio.travel.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import top.qxwkstudio.travel.R

/**
 * 自带水印的卡片容器。
 *
 * 为什么不在里面放一个超大的 TextView/自绘 View：子 View 要么把 wrap_content 的卡片撑高，
 * 要么因为 0×0 尺寸被跳过绘制。这里直接在容器自身的 [dispatchDraw] 里画字：
 *  - 不影响任何布局尺寸；
 *  - 文字画在卡片内容下面；
 *  - 超出卡片的部分由 `clipChildren` / `clipToOutline` 裁掉。
 */
class WatermarkLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var watermarkText: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var watermarkSizeSp: Float = 90f
        set(value) {
            field = value
            paint.textSize = value * resources.displayMetrics.density
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card)
        textSize = watermarkSizeSp * resources.displayMetrics.density
    }

    /** 卡片主色 + 透明度；打卡绿、足迹蓝、派生灰。 */
    fun setWatermarkColor(color: Int, alpha: Int = 40) {
        paint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(color, alpha)
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (watermarkText.isNotEmpty() && width > 0 && height > 0) {
            val fm = paint.fontMetrics
            val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
            val x = width - paint.measureText(watermarkText) - resources.displayMetrics.density * 8f
            canvas.drawText(watermarkText, x, baseline, paint)
        }
        super.dispatchDraw(canvas)
    }
}
