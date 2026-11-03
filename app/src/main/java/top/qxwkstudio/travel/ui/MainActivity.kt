package top.qxwkstudio.travel.ui

import android.os.Bundle
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.databinding.ActivityMainBinding

/**
 * 主壳（2.0）：底部五个 tab —— 主页 / 行程 / 地图 / 成就 / 我的。
 *
 * 与 1.x 的区别：
 *  - **没有登录入口、没有启动时的 /api/me**：2.0 是离线单机，数据全在本地库；
 *  - 「全站统计」页删掉了（单机没有全站），统计搬进主页；「我的成就」收成「成就」；
 *  - 右上角那颗按钮从「栏目菜单（一行一个网页）」变成「**+ 快捷菜单**」（打卡 / 足迹 / 自动记录）。
 *
 * Fragment 仍用 **add + hide/show** 而不是 replace：地图页 replace 一次就要重建整个 MapView、
 * 统计页也会重新跑一遍聚合。代价是隐藏的 Fragment 仍是 RESUMED，所以各页感知「被切回来」
 * 用 `onHiddenChanged` 而不是 `onResume`（主页与行程页都这么写）。
 *
 * 五个页面都是各自独立的实现；2.1 会把「自动记录」接进「+」菜单的第三项。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 底栏选中态那颗共享药丸（见 [setupNavPill]）。 */
    private lateinit var navPill: View

    /** 上一次算出的药丸落点（底栏坐标系里的 x），见 [placeNavPill]。NaN 保证第一次一定落位。 */
    private var pillTargetX = Float.NaN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题色叠层：必须在 setContentView 之前应用，Material 控件（按钮/开关/输入框）的颜色取自主题
        theme.applyStyle(Palette.selected(this).overlay, true)

        binding = ActivityMainBinding.inflate(layoutInflater)
        val b = binding
        // 必须在 setContentView 之前（见 ui/EdgeToEdge.kt）
        applyEdgeToEdge(b.topBar.root, b.bottomBar)
        setContentView(b.root)

        b.bottomNav.doOnLayout { pinBottomNavLabels() }
        setupNavPill()
        setupAddMenu()

        b.bottomNav.setOnItemSelectedListener { item ->
            show(tagOf(item.itemId))
            b.topBar.title.text = getString(titleOf(item.itemId))
            // 落点用回调参数 itemId，不能读 selectedItemId：选中状态比这个回调晚一步更新
            // （时序说明见 1.x 的同一段注释，结论没变）
            placeNavPill(item.itemId, animate = true)
            true
        }

        if (savedInstanceState == null) {
            b.bottomNav.selectedItemId = R.id.tab_home
        } else {
            b.topBar.title.text = getString(titleOf(b.bottomNav.selectedItemId))
        }
    }

    // ────────────────────────── 右上角「+」 ──────────────────────────

    /**
     * 快捷菜单：城市打卡 / 添加足迹 / 自动记录。
     *
     * 前两项对应的页面还没做（P3），点了先给一句「开发中」—— 主壳阶段先把入口形状定下来，
     * 免得后面又改一次菜单结构。第三项**置灰不可点**（自动记录整块推到 2.1，见口径 8/9）。
     */
    private fun setupAddMenu() {
        binding.topBar.btnMenu.visibility = View.VISIBLE
        binding.topBar.btnMenu.setOnClickListener {
            val popup = PopupMenu(this, binding.topBar.btnMenu)
            popup.menu.add(Menu.NONE, ID_CHECKIN, 0, R.string.menu_checkin)
            popup.menu.add(Menu.NONE, ID_TRIP, 1, R.string.menu_trip)
            popup.menu.add(Menu.NONE, ID_AUTO_RECORD, 2, R.string.menu_auto_record)
                .isEnabled = false
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    ID_CHECKIN -> startActivity(
                        CheckinEditActivity.intent(this)
                    )
                    ID_TRIP -> startActivity(
                        TripEditActivity.intent(this)
                    )
                    ID_AUTO_RECORD ->
                        notify(R.string.menu_wip_auto_record)
                }
                true
            }
            popup.show()
        }
    }

    // ────────────────────────── tab 路由 ──────────────────────────

    private fun show(tag: String) {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        tx.setCustomAnimations(R.anim.anim_fade_up, R.anim.anim_fade_out)
        // 先全 hide：show 一个之前没有 hide 的，会出现两层叠着（点击穿透到下层）
        fm.fragments.forEach { tx.hide(it) }

        val existing = fm.findFragmentByTag(tag)
        if (existing == null) tx.add(R.id.container, newFragment(tag), tag) else tx.show(existing)
        tx.commit()
    }

    private fun newFragment(tag: String): Fragment = when (tag) {
        TAG_TRIPS -> TripsFragment()
        TAG_MAP -> MapFragment()
        TAG_ACHV -> AchievementsFragment()
        TAG_MINE -> MineFragment()
        else -> HomeFragment()
    }

    private fun tagOf(itemId: Int): String = when (itemId) {
        R.id.tab_trips -> TAG_TRIPS
        R.id.tab_map -> TAG_MAP
        R.id.tab_achievements -> TAG_ACHV
        R.id.tab_profile -> TAG_MINE
        else -> TAG_HOME
    }

    private fun titleOf(itemId: Int): Int = when (itemId) {
        R.id.tab_trips -> R.string.tab_trips
        R.id.tab_map -> R.string.tab_map
        R.id.tab_achievements -> R.string.tab_achievements
        R.id.tab_profile -> R.string.tab_profile
        else -> R.string.tab_home
    }

    // ────────────────────────── 底栏观感（沿用 1.x） ──────────────────────────

    /**
     * 把底栏文案从「沉在 item 底边」挪到「贴在图标下面」。
     * 为什么 XML 属性解决不了、为什么只能用 translationY，见 1.x 的详细注释 —— 结论没变：
     * 文案组是 item 里唯一「直接装着 TextView」的子容器，图标容器是它的兄弟节点。
     */
    private fun pinBottomNavLabels() {
        val groups = ArrayList<ViewGroup>()
        collectLabelGroups(binding.bottomNav, groups)
        for (group in groups) {
            val item = group.parent as? ViewGroup ?: continue
            val iconBox = (0 until item.childCount)
                .map { item.getChildAt(it) }
                .firstOrNull { it is ViewGroup && it !== group } as? ViewGroup ?: continue
            group.translationY = (iconBox.bottom - group.top).toFloat()
        }
    }

    private fun collectLabelGroups(root: ViewGroup, out: MutableList<ViewGroup>) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as? ViewGroup ?: continue
            if ((0 until child.childCount).any { child.getChildAt(it) is TextView }) out.add(child)
            else collectLabelGroups(child, out)
        }
    }

    /**
     * 选中态药丸：一个共享 View 在 item 之间平滑移动（1.x 的同一套做法）。
     *
     * 药丸只能在代码里 addView：BottomNavigationView 的 XML 子标签另有含义（那是菜单的 <item>）。
     */
    private fun setupNavPill() {
        val bar = binding.bottomNav
        navPill = View(this)
        navPill.setBackgroundResource(R.drawable.nav_pill)
        navPill.isClickable = false
        // 插在 index 0：药丸要画在 item **下面**（直接 addView 会盖住图标与文字）
        bar.addView(navPill, 0, ViewGroup.LayoutParams(0, 0))
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeNavPill(bar.selectedItemId, animate = false) }
        bar.doOnLayout { placeNavPill(bar.selectedItemId, animate = false) }
    }

    private fun placeNavPill(itemId: Int, animate: Boolean) {
        val bar = binding.bottomNav
        val item = bar.findViewById<View>(itemId) ?: return
        if (item.width == 0) return
        val lp = navPill.layoutParams
        // 只在尺寸真的变了才写回：赋值会 requestLayout，而布局回调里又会回来调这个方法
        if (lp.width != item.width || lp.height != item.height) {
            lp.width = item.width
            lp.height = item.height
            navPill.layoutParams = lp
        }
        val targetX = (item.parent as View).left + item.x
        if (animate) {
            pillTargetX = targetX
            navPill.animate().x(targetX).setDuration(200L)
                .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
            return
        }
        // 落点没变说明这次布局与药丸无关，直接返回（否则会掐掉刚起步的动画）
        if (targetX == pillTargetX) return
        pillTargetX = targetX
        navPill.animate().cancel()
        navPill.x = targetX
    }

    private companion object {
        const val TAG_HOME = "home"
        const val TAG_TRIPS = "trips"
        const val TAG_MAP = "map"
        const val TAG_ACHV = "achievements"
        const val TAG_MINE = "mine"

        const val ID_CHECKIN = 1
        const val ID_TRIP = 2
        const val ID_AUTO_RECORD = 3
    }
}
