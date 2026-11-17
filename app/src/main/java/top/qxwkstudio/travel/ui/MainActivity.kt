package top.qxwkstudio.travel.ui

import top.qxwkstudio.travel.ui.common.*
import top.qxwkstudio.travel.ui.home.HomeFragment
import top.qxwkstudio.travel.ui.trips.TripsFragment
import top.qxwkstudio.travel.ui.map.MapFragment
import top.qxwkstudio.travel.ui.achievements.AchievementsFragment
import top.qxwkstudio.travel.ui.mine.MineFragment
import top.qxwkstudio.travel.ui.trips.CheckinEditActivity
import top.qxwkstudio.travel.ui.trips.TripEditActivity
import top.qxwkstudio.travel.ui.trips.TripsSelectionHost

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import top.qxwkstudio.travel.ui.common.LocalizedActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.db.AppDatabase
import top.qxwkstudio.travel.data.Prefs
import top.qxwkstudio.travel.data.RenumberRepo
import top.qxwkstudio.travel.data.SyncScheduler
import androidx.lifecycle.lifecycleScope
import top.qxwkstudio.travel.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 主壳（2.0）：底部五个 tab —— 主页 / 行程 / 地图 / 成就 / 我的。
 *
 * 与 1.x 的区别：
 *  - **没有登录入口、没有启动时的 /api/me**：2.0 是离线单机，数据全在本地库；
 *  - 「全站统计」页删掉了（单机没有全站），统计搬进主页；「我的成就」收成「成就」；
 *  - 右上角那颗按钮从「栏目菜单（一行一个网页）」变成「**+ 快捷菜单**」（打卡 / 足迹）。
 *
 * Fragment 仍用 **add + hide/show** 而不是 replace：地图页 replace 一次就要重建整个 MapView、
 * 统计页也会重新跑一遍聚合。代价是隐藏的 Fragment 仍是 RESUMED，所以各页感知「被切回来」
 * 用 `onHiddenChanged` 而不是 `onResume`（主页与行程页都这么写）。
 *
 * 五个页面都是各自独立的实现。
 */
class MainActivity : LocalizedActivity(), TripsSelectionHost {

    private lateinit var binding: ActivityMainBinding

    /** 底栏选中态那颗共享药丸（见 [setupNavPill]）。 */
    private lateinit var navPill: View

    /** 上一次算出的药丸落点（底栏坐标系里的 x），见 [placeNavPill]。NaN 保证第一次一定落位。 */
    private var pillTargetX = Float.NaN

    /** 是否处于行程页多选模式：顶栏换返回键、底栏换多选功能区，返回键先退模式再退页面。 */
    private var selectingTrips = false

    /** 进入多选前的顶栏标题，退出时原样还回去。 */
    private var titleBeforeSelection = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题色叠层：必须在 setContentView 之前应用，Material 控件（按钮/开关/输入框）的颜色取自主题
        Palette.apply(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        val b = binding
        // 必须在 setContentView 之前（见 ui/EdgeToEdge.kt）
        applyEdgeToEdge(b.topBar.root, b.bottomArea, bottomIncludeIme = false)
        setContentView(b.root)

        b.bottomNav.doOnLayout { pinBottomNavLabels() }
        // 首次进入/进程恢复后也要把药丸摆好（只在用户点选时摆的话，恢复进地图等 tab 会没有滑块）
        b.bottomNav.post { placeNavPill(b.bottomNav.selectedItemId, animate = false) }
        setupNavPill()
        setupAddMenu()
        setupSelectionBar()
        showLegalConsentIfNeeded()

        b.bottomNav.setOnItemSelectedListener { item ->
            show(tagOf(item.itemId))
            b.topBar.title.text = getString(titleOf(item.itemId))
            // 落点用回调参数 itemId，不能读 selectedItemId：选中状态比这个回调晚一步更新
            // （时序说明见 1.x 的同一段注释，结论没变）
            placeNavPill(item.itemId, animate = true)
            updateTopMenuIcon(item.itemId)
            true
        }

        if (savedInstanceState == null) {
            b.bottomNav.selectedItemId = R.id.tab_home
        } else {
            b.topBar.title.text = getString(titleOf(b.bottomNav.selectedItemId))
        }
        updateTopMenuIcon()
    }

    // ────────────────────────── 右上角「+」 ──────────────────────────

    /**
     * 右上角「+」的快捷菜单：城市打卡 / 添加足迹。
     *
     * 浮层本体在 [showIconMenu]：自绘的图标 + 文字行，宽度 wrap —— 系统 PopupMenu 的宽度
     * 按最长项 + 系统内边距算，三个中文短词会被撑成一条横带（真机上反馈「太宽」）。
     */
    /** 首次启动必须先同意用户协议与免责声明；不同意则关闭应用。 */
    private fun showLegalConsentIfNeeded() {
        if (Prefs.legalAccepted) return
        val message = getString(R.string.legal_consent_message)
        val spannable = SpannableString(message)
        addLink(
            spannable,
            message,
            getString(R.string.legal_terms_link),
            "https://travel.qxwkstudio.top/legal/terms",
        )
        addLink(
            spannable,
            message,
            getString(R.string.legal_disclaimer_link),
            "https://travel.qxwkstudio.top/legal/disclaimer",
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.legal_consent_title)
            .setMessage(spannable)
            .setCancelable(false)
            .setPositiveButton(R.string.legal_consent_agree) { _, _ ->
                Prefs.saveLegalAccepted(this)
                SyncScheduler.request(500)
            }
            .setNegativeButton(R.string.legal_consent_exit) { _, _ ->
                finishAffinity()
            }
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod =
            LinkMovementMethod.getInstance()
    }

    private fun addLink(text: SpannableString, full: String, label: String, url: String) {
        val start = full.indexOf(label)
        if (start < 0) return
        text.setSpan(
            URLSpan(url),
            start,
            start + label.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun setupAddMenu() {
        binding.topBar.btnMenu.setOnClickListener { anchor ->
            val actions = mutableListOf(
                MenuAction(R.drawable.ic_locate, R.string.menu_checkin) {
                    startActivity(CheckinEditActivity.intent(this))
                },
                MenuAction(R.drawable.ic_luggage, R.string.menu_trip) {
                    startActivity(TripEditActivity.intent(this))
                },
            )
            if (binding.bottomNav.selectedItemId == R.id.tab_trips) {
                actions += MenuAction(R.drawable.ic_sync, R.string.menu_renumber) {
                    renumber()
                }
                // 多选只属于行程页：它要改顶栏与底栏，放在页面里做会够不着外壳
                actions += MenuAction(R.drawable.ic_check, R.string.trips_menu_multi_select) {
                    enterTripsSelection()
                }
            }
            showIconMenu(anchor, actions)
        }
        updateTopMenuIcon()
    }

    /**
     * 右上角按钮的形态按所在 tab 决定：首页是「+」，行程页是「⋮」（含重新编号 / 多选）。
     * 地图 / 成就 / 我的三页没有这个入口，直接隐藏。
     */
    private fun updateTopMenuIcon(itemId: Int = binding.bottomNav.selectedItemId) {
        val onTrips = itemId == R.id.tab_trips
        val visible = onTrips || itemId == R.id.tab_home
        binding.topBar.btnMenu.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        binding.topBar.btnMenu.setImageResource(if (onTrips) R.drawable.ic_more else R.drawable.ic_add)
        binding.topBar.btnMenu.contentDescription = getString(
            if (onTrips) R.string.menu_renumber else R.string.common_add,
        )
    }

    private fun renumber() {
        lifecycleScope.runIo({ RenumberRepo.renumber(AppDatabase.get(this)) }) { result ->
            if (result.isSuccess) {
                notify(R.string.renumber_done)
            } else {
                notify(R.string.renumber_failed, long = true)
            }
        }
    }

    // ────────────────────────── 行程页多选外壳 ──────────────────────────

    private fun setupSelectionBar() {
        binding.btnSelectAll.setOnClickListener { tripsFragment()?.selectAllVisible() }
        binding.btnSelectionDelete.setOnClickListener { tripsFragment()?.confirmDeleteSelected() }
        binding.btnSelectionExport.setOnClickListener { tripsFragment()?.exportSelected() }
        setSelectionActions(0, 0)
        // 多选模式下返回键先退模式，而不是直接退出应用
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectingTrips) {
                    exitTripsSelection()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    /**
     * 进入多选：顶栏换成「返回 + 统计」，右上角动作项全部让位（规格要求右侧留空），
     * 底栏从导航栏换成 全选 / 删除 / 导出。选择状态本身在 [TripsFragment] 里。
     */
    private fun enterTripsSelection() {
        if (selectingTrips) return
        val trips = tripsFragment() ?: return
        selectingTrips = true
        titleBeforeSelection = binding.topBar.title.text.toString()
        binding.topBar.btnMenu.visibility = View.GONE
        binding.topBar.btnBack.visibility = View.VISIBLE
        binding.topBar.btnBack.setOnClickListener { exitTripsSelection() }
        binding.topBar.title.text = getString(R.string.trips_selection_title, 0, 0)
        binding.bottomBar.visibility = View.GONE
        binding.multiBar.visibility = View.VISIBLE
        trips.enterSelectMode()
    }

    /** 退出多选：顶栏、底栏、标题全部还原，并清空页面里的选择状态（见 TripsFragment.exitSelectMode）。 */
    private fun exitTripsSelection() {
        if (!selectingTrips) return
        selectingTrips = false
        tripsFragment()?.exitSelectMode()
        binding.topBar.btnBack.setOnClickListener(null)
        binding.topBar.btnBack.visibility = View.GONE
        binding.topBar.title.text = titleBeforeSelection.ifBlank {
            getString(titleOf(binding.bottomNav.selectedItemId))
        }
        binding.multiBar.visibility = View.GONE
        binding.bottomBar.visibility = View.VISIBLE
        setSelectionActions(0, 0)
        updateTopMenuIcon()
    }

    override fun onTripsSelectionCounts(checkins: Int, trips: Int) {
        if (!selectingTrips) return
        binding.topBar.title.text = getString(R.string.trips_selection_title, checkins, trips)
        setSelectionActions(checkins, trips)
    }

    /** 没选任何内容时删除/导出置灰，避免「点了没反应」的困惑（规格允许置灰或提示，这里选置灰）。 */
    private fun setSelectionActions(checkins: Int, trips: Int) {
        val any = checkins + trips > 0
        listOf(binding.btnSelectionDelete, binding.btnSelectionExport).forEach {
            it.isEnabled = any
            it.alpha = if (any) 1f else 0.4f
        }
    }

    private fun tripsFragment(): TripsFragment? =
        supportFragmentManager.findFragmentByTag(TAG_TRIPS) as? TripsFragment

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

    }
}
