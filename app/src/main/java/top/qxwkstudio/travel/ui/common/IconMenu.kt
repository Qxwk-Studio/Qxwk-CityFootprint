package top.qxwkstudio.travel.ui.common

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
        setPadding(dp(4), dp(4), dp(4), dp(4))
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
    val width = content.measuredWidth.coerceAtLeast(dp(150))

    // 分隔线在量完宽度之后插：它的宽度是 MATCH_PARENT，先插会把 WRAP_CONTENT 的
    // 弹窗撑成整屏宽（上一版菜单「非常长」就是这么来的）。
    var index = 1
    while (index < content.childCount) {
        content.addView(
            android.view.View(context).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.border))
            },
            index,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
        )
        index += 2
    }

    // 量出含分隔线后的真实高度：底部卡片的三点菜单要据此决定向上还是向下弹，
    // 否则靠近屏幕底部时菜单会被裁掉。
    content.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
    )
    val height = content.measuredHeight

    // bg_popup_menu 是一张带 8dp inset 的九宫格底：PopupWindow 会把这圈 inset
    // 作为内容内边距吃掉。窗口尺寸必须把 padding 加回去，否则文字/末行会被裁切。
    val background = ContextCompat.getDrawable(context, R.drawable.bg_popup_menu)
    val bgPadding = android.graphics.Rect()
    background?.getPadding(bgPadding)
    val bgHorizontal = bgPadding.left + bgPadding.right
    val bgVertical = bgPadding.top + bgPadding.bottom
    val windowWidth = width + bgHorizontal
    val windowHeight = height + bgVertical

    popup = PopupWindow(
        content,
        windowWidth,
        windowHeight,
        true,
    ).apply {
        isOutsideTouchable = true
        elevation = dp(8).toFloat()
        // 透明背景时 elevation 阴影不绘制；换成卡片底，阴影围绕圆角弹层
        setBackgroundDrawable(background)
    }
    // 右边缘对齐到锚点、下方留 4dp：菜单右上两侧的留白一致，不会一边宽一边贴边
    // 右对齐**屏幕边缘**（不是锚点），上方与右侧都留 6dp
    val gap = dp(2)
    val location = IntArray(2)
    anchor.getLocationOnScreen(location)
    val screenWidth = anchor.resources.displayMetrics.widthPixels
    val anchorLeft = location[0]
    // 右缘对齐三点按钮本身（不是屏幕边缘），左边至少留 6dp
    val xoff = (anchor.width - windowWidth)
        .coerceAtMost(screenWidth - gap - anchorLeft - windowWidth)
        .coerceAtLeast(gap - anchorLeft)
    // 默认向下贴近三点按钮；下方空间不够时改为向上弹，避免被屏幕底部裁断。
    val screenHeight = anchor.resources.displayMetrics.heightPixels
    val anchorTop = location[1]
    val anchorBottom = anchorTop + anchor.height
    val spaceBelow = screenHeight - anchorBottom
    val spaceAbove = anchorTop
    val showAbove = spaceBelow < windowHeight + gap && spaceAbove >= windowHeight + gap
    val yoff = if (showAbove) -(anchor.height + windowHeight + gap) else -dp(8)
    popup.showAsDropDown(anchor, xoff, yoff)
}
