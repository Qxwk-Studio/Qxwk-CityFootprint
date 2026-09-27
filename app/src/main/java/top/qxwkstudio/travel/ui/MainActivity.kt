package top.qxwkstudio.travel.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import top.qxwkstudio.travel.R
import top.qxwkstudio.travel.data.Auth
import top.qxwkstudio.travel.data.MeResult
import top.qxwkstudio.travel.data.Store
import top.qxwkstudio.travel.databinding.ActivityMainBinding

/**
 * 主页：底部五个 tab（主页 / 我的成就 / 地图 / 全站统计 / 我的）。
 *
 * Fragment 用 **add + hide/show** 而不是 replace：
 * 地图页 replace 一次就要重建整个 MapView（重新拉瓦片、缩放位置全丢），
 * 统计页也会重新请求一次 —— 切 tab 这么频繁的动作不该有这种代价。
 * 代价是回调时机变了：隐藏的 Fragment **仍是 RESUMED**，切回来不会触发 onResume，
 * 所以各页需要感知「被切回来了」时用 onHiddenChanged（见 StatsFragment / ProfileFragment）。
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
        setContentView(b.root)

        b.bottomNav.setOnItemSelectedListener { item ->
            show(tagOf(item.itemId))
            b.title.text = getString(titleOf(item.itemId))
            true
        }

        // 顶栏右上角那颗「打开我的」：与底部「我的」是同一个入口，直接切 tab
        b.btnProfile.setOnClickListener { b.bottomNav.selectedItemId = R.id.tab_profile }

        if (savedInstanceState == null) {
            // 选中态交给 BottomNavigationView 自己的 item 状态 —— 选中会回调上面的 listener，
            // 顺带把第一个 Fragment 装上、标题设好（只有一处真相来源）
            b.bottomNav.selectedItemId = R.id.tab_home
        } else {
            // 重建时 Fragment 由 FragmentManager 自己恢复（选中的那一项也恢复了），
            // 这里只需要把标题补上
            b.title.text = getString(titleOf(b.bottomNav.selectedItemId))
        }

        verifySession()
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