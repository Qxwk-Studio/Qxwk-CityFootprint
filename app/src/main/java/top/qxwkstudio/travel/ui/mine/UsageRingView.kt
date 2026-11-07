package top.qxwkstudio.travel.ui.mine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import top.qxwkstudio.travel.R
import java.util.Locale

/** 存储占用圆环：按分类画弧，中间写总占用。 */
class UsageRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var values = LongArray(0)
    private var colors = IntArray(0)
    private var totalText = ""

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        color = ContextCompat.getColor(context, R.color.fg)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }

    fun setData(values: LongArray, colors: IntArray, totalText: String) {
        this.values = values
        this.colors = colors
        this.totalText = totalText
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = dp(14f)
        ring.strokeWidth = stroke
        val pad = stroke / 2f + dp(2f)
        val rect = RectF(pad, pad, width - pad, height - pad)
        ring.color = ContextCompat.getColor(context, R.color.border)
        canvas.drawArc(rect, 0f, 360f, false, ring)

        val total = values.sum().coerceAtLeast(1L)
        var start = -90f
        for (i in values.indices) {
            val sweep = values[i].toFloat() / total * 360f
            if (sweep <= 0f) continue
            ring.color = colors.getOrElse(i) { ContextCompat.getColor(context, R.color.accent) }
            canvas.drawArc(rect, start, sweep, false, ring)
            start += sweep
        }

        big.textSize = dp(20f)
        small.textSize = dp(12f)
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawText(totalText.ifBlank { "--" }, cx, cy - dp(2f), big)
        canvas.drawText(context.getString(R.string.storage_total), cx, cy + dp(18f), small)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
