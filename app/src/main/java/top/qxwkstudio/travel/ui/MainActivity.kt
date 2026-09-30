package top.qxwkstudio.travel.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.Auth
import top.qxwkstudio.travel.data.MeResult
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.data.Update
import top.qxwkstudio.travel.databinding.ActivityMainBinding
import top.qxwkstudio.travel.databinding.ItemMenuBinding
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
 * 左上角那颗三横菜单拉开的抽屉也归这一页管（栏目 = 一行一个网页，清单在网页端的 version.json，
 * 见 setupDrawer / loadMenu）。抽屉是壳的一部分、不属于任何一个 tab，所以放 Activity 而不是 Fragment。
 */
class MainActivity : AppCompatActivity() {

    // 非空 lateinit：Activity 与视图同生共死（不必像 Fragment 那样在 onDestroyView 里置 null）。
    // 异步回调挂在 lifecycleScope 上（onDestroy 取消），所以回调里直接用 binding 是安全的。
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: Store

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
        // 第三个参数是抽屉面板：这一页最外层是 DrawerLayout，三块（顶栏/底栏/抽屉）都由那一个监听分
        // —— 少传它的话抽屉标题会被状态栏压住，理由见 EdgeToEdge 文件头第 4 点。
        applyEdgeToEdge(b.topBar.root, b.bottomBar, b.drawerPanel)
        setContentView(b.root)

        // M3 底栏默认把文案沉在 item 底边，与图标之间留一截空；拉到布局完成后再修（说明见 pinBottomNavLabels）
        b.bottomNav.doOnLayout { pinBottomNavLabels() }

        b.bottomNav.setOnItemSelectedListener { item ->
            show(tagOf(item.itemId))
            b.topBar.title.text = getString(titleOf(item.itemId))
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

        setupDrawer()
        verifySession()
    }

    /**
     * 左上角那颗三横菜单 + 它拉开的抽屉。
     *
     * 抽屉里是「栏目」：一行 = 一个网页，条目来自网页根下的 version.json（见 [loadMenu]）——
     * 以后加限时活动只改那个 JSON、push 一次就生效，**不用发新版本**。
     */
    private fun setupDrawer() {
        binding.topBar.btnMenu.visibility = View.VISIBLE
        binding.topBar.btnMenu.setOnClickListener { binding.drawer.openDrawer(GravityCompat.START) }

        // 返回键：抽屉开着时先关抽屉，而不是直接退出 App（默认行为是**退出**，
        // 用户按一下发现 App 没了，只会以为返回键失灵）。抽屉本来就是浮层，先收浮层是通例。
        // 没开抽屉就摘掉自己、交回默认处理，别把这颗返回键吃掉。
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.drawer.isDrawerOpen(GravityCompat.START)) {
                        binding.drawer.closeDrawer(GravityCompat.START)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )

        loadMenu()
    }

    /**
     * 拉清单里的栏目铺进抽屉。与公告同一口径：**只在页面创建时拉一次**，失败就给一句提示，
     * 不做重试按钮 —— 抽屉不是内容主体，拉不到不该挡着用户用 App（要更新就下拉刷新或重进主页）。
     */
    private fun loadMenu() {
        lifecycleScope.runIo({ Update.fetch() }) { result ->
            val manifest = result.getOrNull()
            if (manifest == null) {
                showMenuState(getString(R.string.menu_load_failed))
            } else {
                renderMenu(manifest.menu)
            }
        }
    }

    /**
     * 一行一个栏目。title 与 url **都必填**，缺一个整条跳过（理由见 logic/Models.kt 的 [MenuItem]）——
     * 所以「清单里写了两条、抽屉里只出现一条」是有意为之，不是渲染漏了。
     */
    private fun renderMenu(items: List<MenuItem>) {
        val usable = items.filter { it.title.isNotBlank() && it.url.isNotBlank() }
        if (usable.isEmpty()) {
            showMenuState(getString(R.string.menu_empty))
            return
        }

        binding.menuScroll.visibility = View.VISIBLE
        binding.menuState.visibility = View.GONE
        binding.menuList.removeAllViews()
        for (item in usable) {
            val row = ItemMenuBinding.inflate(layoutInflater, binding.menuList, false)
            row.textTitle.text = item.title
            row.root.setOnClickListener {
                // 先关抽屉再开页：不关的话从网页页返回时抽屉还敞着，看起来像刚才点错了
                binding.drawer.closeDrawer(GravityCompat.START)
                // withIdentity = true：自家页面的 WebView 里递一份当前身份过去
                // （网页默认是未登录态 —— token 在 App 的 SharedPreferences 里，不在 WebView 的 localStorage 里）
                startActivity(WebViewActivity.intent(this, item.url, item.title, withIdentity = true))
            }
            binding.menuList.addView(row.root)
        }
    }

    /** 抽屉的空态：拉不到 / 一个栏目都没有时**只显示这一行灰字**（与列表互斥，见布局注释）。 */
    private fun showMenuState(text: String) {
        binding.menuState.text = text
        binding.menuState.visibility = View.VISIBLE
        binding.menuScroll.visibility = View.GONE
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