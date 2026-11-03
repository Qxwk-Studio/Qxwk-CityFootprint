package top.qxwkstudio.travel.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import top.qxwkstudio.travel.R

/**
 * 全 app 的**图标菜单**（右上角「+」、卡片上的「...」）。
 *
 * 为什么不用系统 `PopupMenu`：它的宽度按最长项 + 系统内边距算，三个中文短词会被撑成一条横带
 * （真机上反馈「太宽」），而且放不下图标。这里用 `PopupWindow` 自己铺行：18dp 图标 + 14sp 文字、
 * 44dp 行高、圆角卡片、宽度 wrap。
 *
 * 菜单**右对齐到锚点**（三个点在最右边，菜单从它下面往左展开），否则靠右的按钮弹出来会被屏幕边缘裁掉。
 */
internal data class MenuAction(
    val iconRes: Int,
    val labelRes: Int,
    val enabled: Boolean = true,
    val onSelect: () -> Unit,
)

internal fun showIconMenu(anchor: View, actions: List<MenuAction>) {
    val context = anchor.context
    val density = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()

    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        setBackgroundResource(R.drawable.bg_popup_menu)
    }
    var popup: PopupWindow? = null

    actions.forEach { action ->
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(28), dp(10))
            isEnabled = action.enabled
            setOnClickListener {
                if (!action.enabled) return@setOnClickListener
                popup?.dismiss()
                action.onSelect()
            }
        }
        val color = if (action.enabled) Palette.accent(context)
        else ContextCompat.getColor(context, R.color.text_tertiary)

        row.addView(ImageView(context).apply {
            setImageResource(action.iconRes)
            imageTintList = ColorStateList.valueOf(color)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        })
        row.addView(TextView(context).apply {
            setText(action.labelRes)
            textSize = 14f
            setPadding(dp(10), 0, 0, 0)
            setTextColor(
                if (action.enabled) ContextCompat.getColor(context, R.color.fg)
                else ContextCompat.getColor(context, R.color.text_tertiary),
            )
        })
        content.addView(row)
    }

    // 先量一次宽度，才能把菜单右对齐到锚点
    content.measure(
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
    )
    val width = content.measuredWidth

    popup = PopupWindow(
        content,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        true,
    ).apply {
        isOutsideTouchable = true
        elevation = dp(8).toFloat()
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }
    popup.showAsDropDown(anchor, anchor.width - width, dp(4))
}
