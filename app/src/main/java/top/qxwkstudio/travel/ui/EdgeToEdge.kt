package top.qxwkstudio.travel.ui

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * 兼容 edge-to-edge 的公共实现。各页（主壳 / 登录 / 编辑足迹 / 城市选择 / 公告 / WebView）都调它，
 * 而不是各自抄一份 —— 同一件事抄六份，只要有一处漏了，那一页就会在状态栏后面露出半截底色。
 *
 * 做法：让窗口铺满整屏，再把被系统栏压住的那几截高度补成 [topBar] / [bottom] 的 padding。
 * 顶栏与底栏的底色本就与系统栏同色（sidebar），补完 padding 等于让这条横带延伸到屏幕边缘。
 *
 * 监听挂在两条栏的**共同父容器**上（各页里就是最外那层 LinearLayout），一次把 inset 分给两条栏。
 * 为什么不给两条栏各挂一个：insets 沿 View 树往下分发时，某个子 View 一旦返回 CONSUMED，
 * 父容器就不再把它往后发给剩下的兄弟（ViewGroup.dispatchApplyWindowInsets 的行为）——
 * 先吃到 inset 的顶栏会把底栏那一份一并吞掉，症状是底栏永远躲不开手势条，且只在真机上看得到。
 * 在父容器上一次性分配，就不依赖这个分发顺序。
 *
 * 四个易错点（改这里之前先读）：
 *  1. setDecorFitsSystemWindows 必须在 setContentView **之前**调用，否则首帧仍按「让出系统栏」布局；
 *  2. 「原始 padding 只取一次，每次回调都基于它重算」。insets 会因旋转、键盘、手势条显隐多次回调，
 *     在**上次结果**上再累加的话那一栏会越撑越高（这类 bug 只在真机上看得出来，很难查）；
 *  3. 两条栏都要吃 left/right：它们是上下两行不同的视图，横屏被挖孔/三键导航压住的边各自要躲；
 *  4. **键盘也算进 inset**（[WindowInsetsCompat.Type.ime]）。setDecorFitsSystemWindows(false) 之后
 *     系统不再为键盘缩窗口，**清单里那句 windowSoftInputMode="adjustResize" 因此形同空转**——
 *     不自己处理 ime inset，输入框就会被键盘压住（备注框在表单底部，最先遭殃）。
 *     同侧取最大值（见 [insetsForPadding]），键盘收起时这一项自然退化成原来的系统栏高度。
 *
 * 返回 CONSUMED：inset 在本层就消费掉，别让子 View（列表、地图）再吃一遍，否则内容会多缩进一次。
 * 代价是**子 View 再挂自己的监听就收不到回调了**（分发在本层被截断）——所以要在键盘弹出时做点什么
 * （例如把输入框滚进视野），用 [onImeShown] 回调，别在子 View 上另挂一个。
 *
 * @param topBar 顶部那条栏（吃 top/left/right）
 * @param bottom 底部那条栏；没有底栏的页面传**内容容器**（如 ScrollView / 列表外壳），
 *               这样滚动内容的末尾不会被手势条盖住（吃 bottom/left/right）。
 *               键盘弹出时它拿到的是键盘高度，滚动范围随之变长 —— 但 **ScrollView 的视口不会因此变矮**，
 *               系统也就不认为输入框被挡住了、不会自动滚，需要页面自己配合 [onImeShown]。
 * @param onImeShown 键盘**弹出那一刻**回调一次（同一轮弹出只调一次，收起后再弹会再调）。
 *                   适合做「把某个输入框滚进视野」这类一次性动作。
 */
fun Activity.applyEdgeToEdge(topBar: View, bottom: View? = null, onImeShown: (() -> Unit)? = null) {
    WindowCompat.setDecorFitsSystemWindows(window, false)

    // 原始 padding 各取一次（见文件头第 2 点）
    val topPad = intArrayOf(topBar.paddingLeft, topBar.paddingTop, topBar.paddingRight, topBar.paddingBottom)
    val bottomPad = bottom?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }

    // 键盘上一轮是否可见：只为识别「刚弹出来」这个瞬间，见文件头第 4 点与 onImeShown
    var imeWasShown = false

    // 监听挂的宿主：两条栏的共同父容器（各页里就是最外那层 LinearLayout）。
    // 调用发生在 setContentView 之前，此时布局层级已经建好，parent 一定有值。
    val host = topBar.parent as View
    ViewCompat.setOnApplyWindowInsetsListener(host) { _, insets ->
        val i = insets.insetsForPadding()
        topBar.updatePadding(
            left = topPad[0] + i.left,
            top = topPad[1] + i.top,
            right = topPad[2] + i.right,
        )
        if (bottom != null && bottomPad != null) {
            bottom.updatePadding(
                left = bottomPad[0] + i.left,
                right = bottomPad[2] + i.right,
                bottom = bottomPad[3] + i.bottom,
            )
        }
        // padding 已落到视图上再回调，页面那边拿到的就是补过键盘高度的滚动范围
        val imeShown = insets.isVisible(WindowInsetsCompat.Type.ime())
        if (imeShown && !imeWasShown) onImeShown?.invoke()
        imeWasShown = imeShown
        WindowInsetsCompat.CONSUMED
    }
}

/**
 * [WindowInsetsCompat.Type.systemBars] / [displayCutout] / [ime] 合并后的四条值。
 * 一个掩码里多条来源争同一侧时取**最大值**：键盘收起时 ime 是 0，结果就是系统栏那一份；
 * 弹出时底边换成键盘高度 —— 不必把两者相加，键盘本来就把导航栏盖住了。
 */
private fun WindowInsetsCompat.insetsForPadding() =
    getInsets(
        WindowInsetsCompat.Type.systemBars()
            or WindowInsetsCompat.Type.displayCutout()
            or WindowInsetsCompat.Type.ime()
    )