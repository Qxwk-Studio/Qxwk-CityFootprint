package top.qxwkstudio.travel.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import top.qxwkstudio.travel.R

/**
 * 全 app 的**胶囊 / 小标签**样式工厂。
 *
 * 为什么收口：分段控件、分类筛选、编辑页里的日期/时间块本质是同一类控件（一段可点的文字 + 圆角底），
 * 之前各页各写一套 —— 行程页只是文字变色（没有形状）、成就页自绘胶囊、编辑器甚至复用了「名次圆」
 * 那个**椭圆**背景，导致日期块看起来像两颗药丸。统一到这里后，形状、内边距、字号、选中态只有一份。
 *
 * 两种形态：
 *  - [pill]：分段控件 / 分类筛选（高 32dp、圆角 16dp、选中填充主色）
 *  - [tag]：表单里的日期/时间等小块（圆角 8dp、浅底、主色字）
 */
internal object Chips {

    /** 高 32dp 的胶囊。[onClick] 传 null 表示纯展示。 */
    fun pill(context: Context, text: CharSequence, selected: Boolean, onClick: (() -> Unit)? = null): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 16).toFloat()
                setColor(
                    if (selected) Palette.accent(context)
                    else ContextCompat.getColor(context, R.color.popover),
                )
                setStroke(dp(context, 1), ContextCompat.getColor(context, R.color.border))
            }
            setTextColor(
                if (selected) ContextCompat.getColor(context, R.color.on_accent)
                else ContextCompat.getColor(context, R.color.text_secondary),
            )
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
        }

    /** 圆角 8dp 的小块（日期 / 时间 / 城市）。默认主色字 + 浅底。 */
    fun tag(
        context: Context,
        text: CharSequence,
        muted: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.NORMAL)
        setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5))
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 8).toFloat()
            setColor(ContextCompat.getColor(context, R.color.popover))
            setStroke(dp(context, 1), ContextCompat.getColor(context, R.color.border))
        }
        setTextColor(
            ContextCompat.getColor(
                context,
                if (muted) R.color.text_tertiary else R.color.accent,
            ),
        )
        if (onClick != null) {
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** 把已有的 TextView 套成胶囊（布局里的控件不方便换实例时用）。 */
    fun styleAsPill(view: TextView, context: Context, selected: Boolean) {
        view.background = GradientDrawable().apply {
            cornerRadius = dp(context, 16).toFloat()
            setColor(
                if (selected) Palette.accent(context)
                else ContextCompat.getColor(context, R.color.popover),
            )
            setStroke(dp(context, 1), ContextCompat.getColor(context, R.color.border))
        }
        view.backgroundTintList = null
        view.setTextColor(
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.on_accent else R.color.text_secondary,
                ),
            ),
        )
        view.textSize = 13f
        view.gravity = android.view.Gravity.CENTER
        view.setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))
    }

    /** 触摸目标最小 48dp 的图标按钮（三点菜单等），视觉尺寸与可点区域分开。 */
    fun iconTarget(context: Context, minTargetDp: Int = 48): Int = dp(context, minTargetDp)

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

/** 让 [View] 的 measure 尺寸不小于 48dp 见方：视觉小、点得准。 */
internal fun View.ensureTouchTarget(context: Context, sizeDp: Int = 48) {
    val min = (sizeDp * context.resources.displayMetrics.density).toInt()
    post {
        val lp = layoutParams
        if (lp != null && (lp.width < min || lp.height < min)) {
            lp.width = maxOf(lp.width, min)
            lp.height = maxOf(lp.height, min)
            layoutParams = lp
        }
    }
}
