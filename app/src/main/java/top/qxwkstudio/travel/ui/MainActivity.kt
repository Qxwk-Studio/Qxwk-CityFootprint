package top.qxwkstudio.travel.ui

import android.os.Bundle
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.Auth
import top.qxwkstudio.travel.data.MeResult
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.Update
import top.qxwkstudio.travel.databinding.ActivityMainBinding
import top.qxwkstudio.travel.logic.MenuItem

/**
 * 主页：底部五个 tab（主页 / 我的成就 / 地图 / 全站统计 / 我的）。
 *
 * Fragment 用 **add + hide/show** 而不是 replace：
 * 地图页 replace 一次就要重建整个 MapView（重新拉瓦片、缩放位置全丢），
 * 统计页也会重新请求一次 —— 切 tab 这么频繁的动作不该有这种代价。
 * 代价是回调时机变了：隐藏的 Fragment **仍是 RESUMED**，切回来不会触发 onResume。
 * 注意：数据页（主页/成就/统计）**不**在这里重新拉数据 —— 切栏就打网络太浪费，
 * 它们只在首次创建时加载，要新的数据就下拉刷新（缓存见 data/Store）。目前只有
 * ProfileFragment 用 onHiddenChanged 重读一次本机状态（不涉及网络）。
 *
 * 右上角那颗三横菜单弹出的「栏目」浮层也归这一页管（栏目 = 一行一个网页，清单在网页端的
 * version.json，见 setupMenu / loadMenu）。它是壳的一部分、不属于任何一个 tab，所以放 Activity 而不是 Fragment。
 */
class MainActivity : AppCompatActivity() {

    // 非空 lateinit：Activity 与视图同生共死（不必像 Fragment 那样在 onDestroyView 里置 null）。
    // 异步回调挂在 lifecycleScope 上（onDestroy 取消），所以回调里直接用 binding 是安全的。
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: Store

    // 底栏选中态那颗共享药丸（见 setupNavPill）：在 onCreate 里建好，与 Activity 同生共死
    private lateinit var navPill: View

    // 上一次给这颗药丸算出的落点（底栏坐标系里的 x）：布局回调靠它认「这次布局跟药丸有没有关系」，
    // 见 placeNavPill。初值 NaN 保证第一次调用一定不相等、会真的落位。
    private var pillTargetX = Float.NaN

    // 弹出式菜单里的栏目（见 loadMenu）。拉不到 / 一个栏目都没有时是空表，
    // 这时点按钮只弹一句 Toast —— 提示语取 menuErrorRes（「拉不到」与「清单里没栏目」不是一回事）
    private var menuItems: List<MenuItem> = emptyList()
    private var menuErrorRes = R.string.menu_empty

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        // 兜底：正常流程进不来（LoginActivity 已经拦过），但进程被杀后重建、
        // 或用户在清除数据之后直接点图标，就别让用户看到一堆 401 弹窗
        if (!store.isLoggedIn) {
            gotoLogin()
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        val b = binding
        // 必须在 setContentView 之前（见 ui/EdgeToEdge.kt）。
        // topBar 是带 id 的 <include>，ViewBinding 里是 ViewTopBarBinding 而不是 View，取 .root 才是那条栏本身。
        applyEdgeToEdge(b.topBar.root, b.bottomBar)
        setContentView(b.root)

        // M3 底栏默认把文案沉在 item 底边，与图标之间留一截空；拉到布局完成后再修（说明见 pinBottomNavLabels）
        b.bottomNav.doOnLayout { pinBottomNavLabels() }
        setupNavPill()

        b.bottomNav.setOnItemSelectedListener { item ->
            show(tagOf(item.itemId))
            b.topBar.title.text = getString(titleOf(item.itemId))
            // 药丸滑到新选中的那一项（进场那一次由 setupNavPill 的布局回调直接落位，不走动画）
            placeNavPill(true)
            true
        }

        if (savedInstanceState == null) {
            // 选中态交给 BottomNavigationView 自己的 item 状态 —— 选中会回调上面的 listener，
            // 顺带把第一个 Fragment 装上、标题设好（只有一处真相来源）
            b.bottomNav.selectedItemId = R.id.tab_home
        } else {
            // 重建时 Fragment 由 FragmentManager 自己恢复（选中的那一项也恢复了），
            // 这里只需要把标题补上
            b.topBar.title.text = getString(titleOf(b.bottomNav.selectedItemId))
        }

        setupMenu()
        verifySession()
    }

    /**
     * 右上角那颗三横菜单 + 它弹出的栏目浮层。
     *
     * 浮层里是「栏目」：一行 = 一个网页，条目来自网页根下的 version.json（见 [loadMenu]）——
     * 以后加限时活动只改那个 JSON、push 一次就生效，**不用发新版本**。
     */
    private fun setupMenu() {
        binding.topBar.btnMenu.visibility = View.VISIBLE
        binding.topBar.btnMenu.setOnClickListener { showMenu() }
        loadMenu()
    }

    /**
     * 弹出式菜单：贴着右上角那颗按钮弹出一张浮层，一行一个栏目。
     *
     * 用 appcompat 的 PopupMenu 而不是自己搭 PopupWindow：锚点、贴近屏幕边缘时的避让、
     * 点外面关闭、返回键收掉，这几件事它都现成（早先是 DrawerLayout 的左侧抽屉，现按需求改浮层）。
     * 它自带返回键处理，所以 Activity 这边不用再注册 OnBackPressedCallback。
     */
    private fun showMenu() {
        val items = menuItems
        if (items.isEmpty()) {
            Toast.makeText(this, getString(menuErrorRes), Toast.LENGTH_SHORT).show()
            return
        }
        val popup = PopupMenu(this, binding.topBar.btnMenu)
        // 菜单项 id 直接用下标：点击时按下标取回那一项，省得另维护一张 id → url 的表
        items.forEachIndexed { index, item -> popup.menu.add(Menu.NONE, index, index, item.title) }
        popup.setOnMenuItemClickListener { clicked ->
            val item = items.getOrNull(clicked.itemId)
            if (item != null) {
                // withIdentity = true：自家页面的 WebView 里递一份当前身份过去
                // （网页默认是未登录态 —— token 在 App 的 SharedPreferences 里，不在 WebView 的 localStorage 里）
                startActivity(WebViewActivity.intent(this, item.url, item.title, withIdentity = true))
            }
            true
        }
        popup.show()
    }

    /**
     * 拉清单里的栏目。与公告同一口径：**只在页面创建时拉一次**，失败就记下提示语，
     * 点按钮时才弹一句 —— 栏目浮层不是内容主体，拉不到不该挡着用户用 App
     * （要更新就下拉刷新或重进主页），更不该在冷启动时先弹一个用户没请求过的错误。
     */
    private fun loadMenu() {
        lifecycleScope.runIo({ Update.fetch() }) { result ->
            val manifest = result.getOrNull()
            if (manifest == null) {
                menuErrorRes = R.string.menu_load_failed
                return@runIo
            }
            // 一行一个栏目。title 与 url **都必填**，缺一个整条跳过（理由见 logic/Models.kt 的 MenuItem）——
            // 所以「清单里写了两条、菜单里只出现一条」是有意为之，不是渲染漏了
            menuItems = manifest.menu.filter { it.title.isNotBlank() && it.url.isNotBlank() }
        }
    }

    /**
     * 把底栏文案从「沉在 item 底边」挪到「贴在图标下面」。
     *
     * 为什么 XML 属性解决不了：M3 的 item 是个 FrameLayout，图标容器贴顶（上外边距 = itemPaddingTop）、
     * 文案组贴底（下内边距 = itemPaddingBottom），两者之间那截空来自 **item 比内容高** ——
     * item 高度由 activity_main.xml 的 android:minHeight 撑住（itemPaddingTop 只决定图标位置），
     * 而「图标 20dp + 文案」的自然高度只有 ~45dp，多出来的高度默认全落在贴顶的图标与贴底的文案之间。
     * 调内边距类属性只会把留白在 item 里搬家：itemPaddingBottom 同时参与 item 的最小高度计算
     * （NavigationBarItemView.getSuggestedMinimumHeight），改它 item 也跟着变矮。
     *
     * 所以动文案组本身：用 translationY 把它从贴底的位置上移到图标底边。只能用 translationY、
     * 不能改用 layout_marginTop —— margin 会被 getSuggestedMinimumHeight 算进 item 最小高度，
     * item 反而被撑高，等于没改；translationY 只影响绘制、不参与测量。
     *
     * 文案组按结构认、不按 id：navigation_bar_item_labels_group / _icon_container 都不是 material 的
     * public 资源（public.txt 里没有这两项），app 侧引不到它们的 R.id。好在结构稳定：文案组是 item 里
     * 唯一「直接装着 TextView」的子容器，图标容器是它的兄弟节点 —— 两点足够定位，也不怕类名被混淆。
     */
    private fun pinBottomNavLabels() {
        val groups = ArrayList<ViewGroup>()
        collectLabelGroups(binding.bottomNav, groups)
        for (group in groups) {
            val item = group.parent as? ViewGroup ?: continue
            val iconBox = (0 until item.childCount)
                .map { item.getChildAt(it) }
                .firstOrNull { it is ViewGroup && it !== group } as? ViewGroup ?: continue
            // 图标底边在 item 里的位置：容器就是包着图标的那一层，M3 的活动指示器已在
            // Widget.App.NavIndicator 里压成 0dp，所以容器高度 = 图标高度，它的 bottom 即图标底边。
            // 用平移差值而不是改 gravity：改 gravity 要等下一次布局才生效（这里布局已完成，
            // 还得自己补 requestLayout），而「当前贴底位置 → 目标位置」的差值当下就能画对。
            group.translationY = (iconBox.bottom - group.top).toFloat()
        }
    }

    // ---------- 底栏选中态药丸 ----------

    /**
     * 底栏那颗选中药丸：原先由 item 自己画（itemBackground 的 state_list），切换只能瞬间跳。
     * 现在改成底栏里**一个共享的 View**，切 tab 时平移到目标项上（见 placeNavPill）——
     * 外观不变（主色 + 8dp 圆角、铺满 item），只是从「跳」变成「滑」。
     * itemBackground 相应地压成了透明，见 themes.xml 的 Widget.App.BottomNav。
     *
     * 药丸只能在代码里 addView、不能写进 activity_main.xml：BottomNavigationView 的 XML 子标签
     * 另有含义（那是菜单的 <item>），塞一个 <View> 进去会被当成菜单项解析、直接崩。
     */
    private fun setupNavPill() {
        val bar = binding.bottomNav
        navPill = View(this)
        navPill.setBackgroundResource(R.drawable.nav_pill)
        // 不吃触摸：药丸垫在 item 下面，正常收不到手势；写上是为了它哪天露出 item 之外也不会截走点击
        navPill.isClickable = false
        // 宽高等于 item 的宽高，要等布局完成才知道，所以先把尺寸留 0，对齐统一交给布局回调。
        // 插在 index 0：药丸是 itemBackground 的替身，必须画在 item **下面** ——
        // 直接 addView 会加到最上层，把那颗 tab 的图标和文字一起盖住
        bar.addView(navPill, 0, ViewGroup.LayoutParams(0, 0))
        // 窗口尺寸变化（旋转、多窗口）时这次布局回调会再来一遍，顺手重新对齐
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeNavPill(false) }
        bar.doOnLayout { placeNavPill(false) }
    }

    /**
     * 把药丸摆到当前选中项上：[animate] 为 true 平移过去，否则直接落位。
     *
     * 进程被杀后重建时不走 setOnItemSelectedListener（选中项是 FragmentManager 一起恢复的），
     * 那种情况下由布局回调直接落位 —— 所以只有「切 tab」才看得见滑动，进场不会。
     */
    private fun placeNavPill(animate: Boolean) {
        val bar = binding.bottomNav
        val item = bar.findViewById<View>(bar.selectedItemId) ?: return
        // 布局还没完成时 item 宽度是 0，这会儿算出来的药丸宽也是 0，摆没意义，等 doOnLayout 那次
        if (item.width == 0) return
        // 只在尺寸真的变了才写回 layoutParams：赋值会 requestLayout，而布局回调里又会回来调这个方法，
        // 无条件写就是每帧一次重排的死循环
        val lp = navPill.layoutParams
        if (lp.width != item.width || lp.height != item.height) {
            lp.width = item.width
            lp.height = item.height
            navPill.layoutParams = lp
        }
        // item 坐在 menu view 里，而底栏自己有 paddingStart/End（activity_main.xml 的 4dp），
        // 药丸是底栏的直接子 View，x 要补上 menu view 的左偏移，否则整颗药丸会偏向一侧
        val targetX = (item.parent as View).left + item.x
        if (animate) {
            pillTargetX = targetX
            // M3 的 emphasized 曲线（cubic-bezier 0.2, 0 / 0, 1，即 material 的
            // m3_sys_motion_easing_emphasized）：起步冲得快、后半段长收尾，比 decelerate 多一段加速。
            // 提醒一句：M3 规范里这条曲线是配 300ms 以上转场的，这里只有 200ms、位移也就一两个 tab 宽，
            // 若观感偏「急」，先把时长加到 250ms 看看，别急着换曲线
            navPill.animate().x(targetX).setDuration(200L)
                .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
            return
        }
        // 落点和上次算出来的一样，说明这次布局跟药丸没关系 —— 直接返回，别碰它。
        // 这个分支是给「窗口尺寸变了要重新对齐」用的（addOnLayoutChangeListener + doOnLayout），
        // 但那次回调**切 tab 时也会来一趟**：material 的 item 一进选中态就去改自己内部的文案组
        // （margin / 可见性 / activeIndicator），item 一 requestLayout，底栏就跟着重排，
        // 回调随即被触发。若不在这里拦一道，下面那句 cancel() 会掐掉刚起步的位移动画、
        // 把药丸当场钉到终点 —— 表现就是「瞬间跳、完全没有滑动」。
        // 判据只能是「上次的落点」而不是「药丸当前的 x」：动画跑起来之后 x 一直在中途变。
        if (targetX == pillTargetX) return
        pillTargetX = targetX
        navPill.animate().cancel()
        navPill.x = targetX
    }

    // 递归找文案组：直接装着 TextView 的那层容器（判定依据见 pinBottomNavLabels）
    private fun collectLabelGroups(root: ViewGroup, out: MutableList<ViewGroup>) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as? ViewGroup ?: continue
            if ((0 until child.childCount).any { child.getChildAt(it) is TextView }) out.add(child)
            else collectLabelGroups(child, out)
        }
    }

    /**
     * 拿本机 token 找足迹后端要一次 /api/me：
     *  - 200 → 顺手刷新昵称/头像（通行证那边可能改过）；
     *  - 401 → token 真的失效了，按统一流程清 token 回登录页；
     *  - 网络不通 → **什么都不做**。本机 token 还在，不能因为一次请求没发出去就把用户踢出去。
     */
    private fun verifySession() {
        val token = store.token ?: return
        // 挂在 lifecycleScope 上：这一屏销毁时请求自动取消，回调里不用再判 binding
        lifecycleScope.runIo({ Auth.me(token) }) { result ->
            when (val me = result.getOrNull()) {
                is MeResult.Ok -> store.saveMe(me.me)
                MeResult.Unauthorized -> Session.expired(this)
                else -> Unit
            }
        }
    }

    private fun show(tag: String) {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        // 切栏过渡：进来的淡入 + 轻微上移，旧的淡出（动画在 res/anim/anim_fade_*，与浮层出现同一份）。
        // 必须**每次事务都设**：动画是事务自己的属性，不是 Fragment 的，设一次不会留给下一次切换。
        tx.setCustomAnimations(R.anim.anim_fade_up, R.anim.anim_fade_out)
        // 先全 hide：show 一个之前没有 hide 的，会出现两层叠着（点击穿透到下层，很难查）
        fm.fragments.forEach { tx.hide(it) }

        val existing = fm.findFragmentByTag(tag)
        if (existing == null) tx.add(R.id.container, newFragment(tag), tag) else tx.show(existing)
        tx.commit()
    }

    private fun newFragment(tag: String): Fragment = when (tag) {
        TAG_ACHV -> AchievementsFragment()
        TAG_MAP -> MapFragment()
        TAG_STATS -> StatsFragment()
        TAG_PROFILE -> ProfileFragment()
        else -> VisitsFragment()
    }

    private fun tagOf(itemId: Int): String = when (itemId) {
        R.id.tab_achievements -> TAG_ACHV
        R.id.tab_map -> TAG_MAP
        R.id.tab_stats -> TAG_STATS
        R.id.tab_profile -> TAG_PROFILE
        else -> TAG_HOME
    }

    private fun titleOf(itemId: Int): Int = when (itemId) {
        R.id.tab_achievements -> R.string.tab_achievements
        R.id.tab_map -> R.string.tab_map
        R.id.tab_stats -> R.string.tab_stats
        R.id.tab_profile -> R.string.tab_profile
        else -> R.string.tab_home
    }

    private companion object {
        const val TAG_HOME = "home"
        const val TAG_ACHV = "achievements"
        const val TAG_MAP = "map"
        const val TAG_STATS = "stats"
        const val TAG_PROFILE = "profile"
    }
}